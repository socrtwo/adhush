"""logo_absence and scene_cut fixture tests over labeled synthesized material."""

from pathlib import Path

import numpy as np

from adhush.capture.file_replay import FileReplaySource, write_fixture
from adhush.config import LogoAbsenceConfig, RoiConfig, SceneCutConfig
from adhush.detect.logo_absence import (
    LogoAbsenceDetector,
    build_template,
    load_template,
    save_template,
)
from adhush.detect.scene_cut import SceneCutDetector
from adhush.events import FrameEvent
from adhush.util.imageops import extract_roi
from tests.synth import LOGO_ROI, Timeline, synthesize

ROI = RoiConfig(x=LOGO_ROI[0], y=LOGO_ROI[1], w=LOGO_ROI[2], h=LOGO_ROI[3])
LOGO_CFG = LogoAbsenceConfig(roi=ROI, absence_frames=8, present_threshold=0.4)

TIMELINE: Timeline = [("program", 6.0), ("ad_fp", 10.0), ("program", 6.0)]
AD_START, AD_END = 6.0, 16.0


def _calibrated_detector() -> LogoAbsenceDetector:
    frames, _, _, _ = synthesize([("program", 3.0)], audio=False)
    assert frames is not None
    rois = [extract_roi(f, ROI.x, ROI.y, ROI.w, ROI.h) for f in frames]
    return LogoAbsenceDetector(LOGO_CFG, template=build_template(list(rois)))


def _votes(detector, tmp_path: Path) -> dict[float, float]:
    frames, frame_ts, _, labels = synthesize(TIMELINE, audio=False)
    assert [(s.start_ts, s.duration_s) for s in labels] == [(AD_START, AD_END - AD_START)]
    path = tmp_path / "clip.npz"
    write_fixture(path, frames=frames, frame_ts=frame_ts)
    votes: dict[float, float] = {}
    with FileReplaySource(path) as source:
        detector.warmup()
        for frame in source.frames():
            detector.observe_frame(frame)
            votes[frame.ts] = detector.vote(frame.ts).confidence
    return votes


def _max_conf(votes: dict[float, float], t0: float, t1: float) -> float:
    return max((c for ts, c in votes.items() if t0 <= ts < t1), default=0.0)


class TestLogoAbsence:
    def test_absent_during_ad_present_during_program(self, tmp_path: Path) -> None:
        detector = _calibrated_detector()
        votes = _votes(detector, tmp_path)
        assert _max_conf(votes, 1.0, AD_START) == 0.0  # logo on screen
        assert _max_conf(votes, AD_START + 2.0, AD_END) == 1.0  # bug gone
        assert _max_conf(votes, AD_END + 2.0, AD_END + 5.0) == 0.0  # back again

    def test_program_present_flag_tracks_logo(self, tmp_path: Path) -> None:
        detector = _calibrated_detector()
        frames, _, _, _ = synthesize([("program", 1.0)], audio=False)
        assert frames is not None
        for i, frame in enumerate(frames):
            detector.observe_frame(FrameEvent(ts=i / 10, frame=frame))
        assert detector.program_present
        ad_frames, _, _, _ = synthesize([("ad_fp", 2.0)], audio=False)
        assert ad_frames is not None
        for i, frame in enumerate(ad_frames):
            detector.observe_frame(FrameEvent(ts=1.0 + i / 10, frame=frame))
        assert not detector.program_present

    def test_uncalibrated_detector_is_inert(self) -> None:
        detector = LogoAbsenceDetector(LOGO_CFG, template=None)
        assert not detector.calibrated
        assert not detector.program_present
        vote = detector.vote(0.0)
        assert vote.confidence == 0.0
        assert vote.reason == "uncalibrated"

    def test_template_save_load_round_trip(self, tmp_path: Path) -> None:
        template = np.random.default_rng(1).random((7, 9))
        path = tmp_path / "logos" / "logo.npz"
        save_template(path, template)
        loaded = load_template(path)
        assert loaded is not None
        np.testing.assert_allclose(loaded, template)
        assert load_template(tmp_path / "missing.npz") is None


class TestSceneCut:
    def test_rapid_cuts_during_ad_only(self, tmp_path: Path) -> None:
        detector = SceneCutDetector(SceneCutConfig())
        votes = _votes(detector, tmp_path)
        assert _max_conf(votes, 0.0, AD_START) == 0.0  # static program
        assert _max_conf(votes, AD_START + 4.0, AD_END) == 1.0  # 0.4 s shots

    def test_reason_reports_rate(self) -> None:
        detector = SceneCutDetector(SceneCutConfig())
        vote = detector.vote(0.0)
        assert vote.reason.startswith("cut_rate ")
