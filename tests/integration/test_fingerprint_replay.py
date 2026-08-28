"""End-to-end fingerprint memory: learn an ad on its first airing through the
fusion path, then recognize its second airing, jump straight to AD, and mute
for the learned duration.

The second airing deliberately has no black/silence boundary, so a fast mute
there depends on recognition (promotion), not boundary refiners.
"""

import json
from pathlib import Path

from adhush.capture.file_replay import FileReplaySource, write_fixture
from adhush.cli import main
from adhush.config import (
    DetectConfig,
    FingerprintConfig,
    FusionConfig,
    LogoAbsenceConfig,
    LoudnessConfig,
    RoiConfig,
)
from adhush.control import NullController
from adhush.detect import build_detectors
from adhush.detect.fusion import Fusion
from adhush.detect.logo_absence import build_template, save_template
from adhush.engine import Pipeline, evaluate_onsets, run_offline
from adhush.fingerprint import open_fingerprints
from adhush.state import Action, AdStateMachine
from adhush.util.imageops import extract_roi
from tests.synth import LOGO_ROI, RATE, Timeline, synthesize

TIMELINE: Timeline = [
    ("program", 16.0),
    ("boundary", 1.0),
    ("ad_fp", 15.0),
    ("program", 8.0),
    ("ad_fp", 15.0),  # second airing: same ad, no boundary lead-in
    ("program", 6.0),
]
POD1 = (16.0, 32.0)
POD2 = (40.0, 55.0)

ROI = RoiConfig(x=LOGO_ROI[0], y=LOGO_ROI[1], w=LOGO_ROI[2], h=LOGO_ROI[3])
FUSION_CFG = FusionConfig()


def _write_calibration(tmp_path: Path) -> Path:
    frames, _, _, _ = synthesize([("program", 3.0)], audio=False)
    assert frames is not None
    rois = [extract_roi(f, ROI.x, ROI.y, ROI.w, ROI.h) for f in frames]
    template_path = tmp_path / "logo.npz"
    save_template(template_path, build_template(list(rois)))
    return template_path


def _detect_cfg(template: Path) -> DetectConfig:
    return DetectConfig(
        loudness=LoudnessConfig(window_s=1.5, baseline_s=30.0),
        logo_absence=LogoAbsenceConfig(roi=ROI, absence_frames=8, template=str(template)),
    )


def _fp_cfg(tmp_path: Path) -> FingerprintConfig:
    return FingerprintConfig(store=str(tmp_path / "ads.sqlite"), audio_min_agreement=0.6)


def test_second_airing_is_promoted_and_muted_for_learned_duration(tmp_path: Path) -> None:
    frames, frame_ts, samples, labels = synthesize(TIMELINE)
    assert [(s.start_ts, s.start_ts + s.duration_s) for s in labels] == [POD1, POD2]
    fixture = tmp_path / "broadcast.npz"
    write_fixture(fixture, frames=frames, frame_ts=frame_ts, audio=samples, audio_rate=RATE)

    fp_cfg = _fp_cfg(tmp_path)
    store, matcher, learner = open_fingerprints(fp_cfg)
    detect_cfg = _detect_cfg(_write_calibration(tmp_path))
    controller = NullController()
    with FileReplaySource(fixture) as source:
        detectors = build_detectors(
            detect_cfg, video=True, audio=True, fingerprint=(fp_cfg, matcher)
        )
        assert {d.name for d in detectors} == {
            "black_frame", "silence", "loudness", "logo_absence", "scene_cut", "fingerprint",
        }
        fusion = Fusion(FUSION_CFG, {}, [d.name for d in detectors])
        pipeline = Pipeline(
            detectors,
            fusion,
            AdStateMachine(FUSION_CFG),
            controller,
            learner=learner,
            matcher=matcher,
        )
        transitions = run_offline(source, pipeline)

    assert [t.action for t in transitions] == [
        Action.MUTE, Action.UNMUTE, Action.MUTE, Action.UNMUTE,
    ], transitions

    mute, unmute = evaluate_onsets(
        transitions, labels, mute_tolerance_s=4.0, unmute_tolerance_s=2.5
    )
    assert mute.precision == 1.0 and mute.recall == 1.0, transitions
    assert unmute.precision == 1.0 and unmute.recall == 1.0, transitions

    # First airing: learned through the fusion path.
    first_mute, _, second_mute, second_unmute = transitions
    assert not any(r.startswith("fingerprint:promote") for r in first_mute.reasons)
    assert store.count() == 1
    record = store.ads()[0]
    assert record.sample_count == 2  # first airing learned it, second confirmed
    assert 14.0 <= record.duration_s <= 20.0

    # Second airing: recognized and promoted, despite no boundary signals.
    assert any(r.startswith("fingerprint:promote") for r in second_mute.reasons), second_mute
    assert second_mute.ts - POD2[0] <= 1.5, "promotion should beat the fusion dwell path"
    # Muted through the learned window, unmuted near the true ad end.
    assert abs(second_unmute.ts - POD2[1]) <= 2.5


def test_cli_calibrate_learn_replay_flow(tmp_path: Path, capsys) -> None:
    frames, frame_ts, samples, labels = synthesize(TIMELINE)
    fixture = tmp_path / "broadcast.npz"
    write_fixture(fixture, frames=frames, frame_ts=frame_ts, audio=samples, audio_rate=RATE)
    program_only, program_ts, _, _ = synthesize([("program", 3.0)], audio=False)
    calib_clip = tmp_path / "program.npz"
    write_fixture(calib_clip, frames=program_only, frame_ts=program_ts)
    labels_path = tmp_path / "labels.json"
    labels_path.write_text(
        json.dumps([{"start_ts": s.start_ts, "duration_s": s.duration_s} for s in labels])
    )

    profiles = tmp_path / "profiles"
    profiles.mkdir()
    (profiles / "generic.toml").write_text(
        '[identity]\nmake = "generic"\nmodel = "generic"\n'
        '[control]\nbackends = ["local_audio"]\n'
        "[roi]\nlogo = { x = 0.84, y = 0.80, w = 0.14, h = 0.14 }\n"
    )
    config_path = tmp_path / "adhush.toml"
    config_path.write_text(
        f"""
[device]
profile = "generic"
[detect.logo_absence]
absence_frames = 8
template = "{tmp_path / 'logo.npz'}"
[detect.loudness]
window_s = 1.5
baseline_s = 30.0
[fingerprint]
store = "{tmp_path / 'ads.sqlite'}"
audio_min_agreement = 0.6
[log]
level = "warning"
"""
    )

    assert main(["calibrate", "--config", str(config_path), "--input", str(calib_clip)]) == 0
    assert (tmp_path / "logo.npz").is_file()
    capsys.readouterr()

    assert main(
        ["learn", str(fixture), "--config", str(config_path), "--labels", str(labels_path)]
    ) == 0
    out = capsys.readouterr().out
    assert "holds" in out and "ad(s)" in out

    assert main(
        [
            "replay", str(fixture),
            "--config", str(config_path),
            "--labels", str(labels_path),
            "--unmute-tolerance", "2.5",
        ]
    ) == 0
    out = capsys.readouterr().out
    # With the store seeded by `learn`, both airings are recognized.
    assert "fingerprint:promote" in out
    assert "mute-onset:" in out and "unmute-onset:" in out
