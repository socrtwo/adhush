"""End-to-end file_replay tests: capture -> detectors -> fusion -> state -> control.

Ground truth comes from the synthesized timeline's labels. Precision/recall
are asserted separately for mute-onset and unmute-onset, with a tighter
tolerance on unmute: a late unmute is the worse failure (CLAUDE.md).
"""

import json
from pathlib import Path

from adhush.capture.file_replay import FileReplaySource, write_fixture
from adhush.cli import main
from adhush.config import DetectConfig, FusionConfig, LoudnessConfig
from adhush.control import NullController
from adhush.detect import build_detectors
from adhush.detect.fusion import Fusion
from adhush.engine import Pipeline, evaluate_onsets, run_offline
from adhush.state import Action, AdStateMachine
from tests.synth import RATE, Timeline, synthesize

TIMELINE: Timeline = [
    ("program", 8.0),
    ("boundary", 1.0),
    ("ad", 15.0),
    ("program", 8.0),
    ("boundary", 1.0),
    ("ad", 15.0),
    ("program", 5.0),
]
MUTE_TOLERANCE_S = 4.0
UNMUTE_TOLERANCE_S = 2.5  # tighter: late unmute is the worse failure

DETECT_CFG = DetectConfig(loudness=LoudnessConfig(window_s=1.5, baseline_s=30.0))
FUSION_CFG = FusionConfig()  # example-config defaults


def _run(tmp_path: Path, *, video: bool, audio: bool):
    frames, frame_ts, samples, labels = synthesize(TIMELINE, video=video, audio=audio)
    path = tmp_path / "broadcast.npz"
    write_fixture(path, frames=frames, frame_ts=frame_ts, audio=samples, audio_rate=RATE)

    controller = NullController()
    with FileReplaySource(path) as source:
        caps = source.caps()
        assert caps.video is video and caps.audio is audio
        detectors = build_detectors(DETECT_CFG, video=caps.video, audio=caps.audio)
        fusion = Fusion(FUSION_CFG, {}, [d.name for d in detectors])
        pipeline = Pipeline(detectors, fusion, AdStateMachine(FUSION_CFG), controller)
        transitions = run_offline(source, pipeline)
    return transitions, labels, controller, detectors


def test_av_replay_detects_both_ad_pods(tmp_path: Path) -> None:
    transitions, labels, controller, detectors = _run(tmp_path, video=True, audio=True)
    assert {d.name for d in detectors} == {"black_frame", "silence", "loudness"}
    assert len(labels) == 2

    mute, unmute = evaluate_onsets(
        transitions,
        labels,
        mute_tolerance_s=MUTE_TOLERANCE_S,
        unmute_tolerance_s=UNMUTE_TOLERANCE_S,
    )
    # Reported and asserted separately, mute-onset vs unmute-onset:
    assert mute.precision == 1.0, f"spurious mutes: {transitions}"
    assert mute.recall == 1.0, f"missed pods: {transitions}"
    assert unmute.precision == 1.0, f"spurious unmutes: {transitions}"
    assert unmute.recall == 1.0, f"missed/late unmutes: {transitions}"

    # The controller saw exactly the transition sequence, mute leading.
    assert [a for _, a in controller.actions] == ["mute", "unmute", "mute", "unmute"]
    assert [t.action for t in transitions] == [
        Action.MUTE, Action.UNMUTE, Action.MUTE, Action.UNMUTE,
    ]
    # Never muted past the hard ceiling.
    for m, u in zip(transitions[::2], transitions[1::2]):
        assert u.ts - m.ts <= FUSION_CFG.max_mute_s


def test_audio_only_replay_still_works_with_reduced_set(tmp_path: Path) -> None:
    transitions, labels, _, detectors = _run(tmp_path, video=False, audio=True)
    assert {d.name for d in detectors} == {"silence", "loudness"}

    mute, unmute = evaluate_onsets(
        transitions,
        labels,
        mute_tolerance_s=MUTE_TOLERANCE_S,
        unmute_tolerance_s=UNMUTE_TOLERANCE_S,
    )
    assert mute.precision == 1.0 and mute.recall == 1.0, transitions
    assert unmute.precision == 1.0 and unmute.recall == 1.0, transitions


def test_program_material_with_lone_pause_never_mutes(tmp_path: Path) -> None:
    # A dramatic pause (short fade to black + quiet) inside program content
    # must not mute: fail quiet, not loud.
    timeline: Timeline = [("program", 10.0), ("boundary", 0.5), ("program", 10.0)]
    frames, frame_ts, samples, labels = synthesize(timeline)
    assert labels == []  # a lone boundary is not an ad pod
    path = tmp_path / "pause.npz"
    write_fixture(path, frames=frames, frame_ts=frame_ts, audio=samples, audio_rate=RATE)

    controller = NullController()
    with FileReplaySource(path) as source:
        detectors = build_detectors(DETECT_CFG, video=True, audio=True)
        fusion = Fusion(FUSION_CFG, {}, [d.name for d in detectors])
        pipeline = Pipeline(detectors, fusion, AdStateMachine(FUSION_CFG), controller)
        transitions = run_offline(source, pipeline)
    assert transitions == []
    assert controller.actions == []


def test_cli_replay_reports_separate_onset_scores(tmp_path: Path, capsys) -> None:
    frames, frame_ts, samples, labels = synthesize(TIMELINE)
    fixture = tmp_path / "broadcast.npz"
    write_fixture(fixture, frames=frames, frame_ts=frame_ts, audio=samples, audio_rate=RATE)
    labels_path = tmp_path / "labels.json"
    labels_path.write_text(
        json.dumps([{"start_ts": s.start_ts, "duration_s": s.duration_s} for s in labels])
    )

    code = main(
        [
            "replay", str(fixture),
            "--labels", str(labels_path),
            "--mute-tolerance", "4.0",
            "--unmute-tolerance", "4.0",
        ]
    )
    out = capsys.readouterr().out
    assert code == 0
    assert "mute-onset:" in out
    assert "unmute-onset:" in out
