"""Detector fixture tests: each Phase 1 detector replayed over labeled ground
truth synthesized through the file_replay path, plus targeted edge cases."""

from pathlib import Path

import numpy as np

from adhush.capture.file_replay import FileReplaySource, write_fixture
from adhush.config import BlackFrameConfig, LoudnessConfig, SilenceConfig
from adhush.detect.base import Detector
from adhush.detect.black_frame import BlackFrameDetector
from adhush.detect.loudness import LoudnessDetector
from adhush.detect.silence import SilenceDetector
from adhush.events import AudioEvent
from tests.synth import RATE, Timeline, synthesize

TIMELINE: Timeline = [
    ("program", 8.0),
    ("boundary", 1.0),
    ("ad", 15.0),
    ("program", 8.0),
]
POD_START, POD_END = 8.0, 24.0
BOUNDARY_END = 9.0


def _replay(tmp_path: Path, detector: Detector, *, video: bool, audio: bool) -> dict[float, float]:
    """Replay the shared timeline through a fixture; confidence keyed by ts."""
    frames, frame_ts, samples, labels = synthesize(TIMELINE, video=video, audio=audio)
    assert labels == [labels[0]] and labels[0].start_ts == POD_START
    assert labels[0].start_ts + labels[0].duration_s == POD_END

    path = tmp_path / "fixture.npz"
    write_fixture(path, frames=frames, frame_ts=frame_ts, audio=samples, audio_rate=RATE)
    votes: dict[float, float] = {}
    with FileReplaySource(path) as source:
        detector.warmup()
        for frame in source.frames():
            detector.observe_frame(frame)
            votes[frame.ts] = detector.vote(frame.ts).confidence
        for block in source.audio_blocks():
            detector.observe_audio(block)
            votes[block.ts] = detector.vote(block.ts).confidence
    return votes


def _max_conf(votes: dict[float, float], t0: float, t1: float) -> float:
    return max((c for ts, c in votes.items() if t0 <= ts < t1), default=0.0)


class TestBlackFrame:
    def test_fires_only_during_labeled_boundary(self, tmp_path: Path) -> None:
        detector = BlackFrameDetector(BlackFrameConfig())
        votes = _replay(tmp_path, detector, video=True, audio=False)
        assert _max_conf(votes, 0.0, POD_START) == 0.0  # program before the pod
        assert _max_conf(votes, POD_START, BOUNDARY_END) == 1.0  # black run
        assert _max_conf(votes, 12.0, POD_END) == 0.0  # bright ad body

    def test_short_run_ignored(self) -> None:
        detector = BlackFrameDetector(BlackFrameConfig(min_run_frames=3))
        from adhush.events import FrameEvent

        black = np.zeros((48, 64), dtype=np.uint8)
        bright = np.full((48, 64), 120, dtype=np.uint8)
        for i, frame in enumerate([bright, black, black, bright]):
            detector.observe_frame(FrameEvent(ts=i / 10, frame=frame))
        assert detector.vote(0.4).confidence == 0.0

    def test_reason_is_machine_readable(self) -> None:
        detector = BlackFrameDetector(BlackFrameConfig())
        vote = detector.vote(0.0)
        assert vote.reason.startswith("no_black ")
        assert "luma=" in vote.reason


class TestSilence:
    def test_fires_only_during_labeled_boundary(self, tmp_path: Path) -> None:
        detector = SilenceDetector(SilenceConfig())
        votes = _replay(tmp_path, detector, video=False, audio=True)
        assert _max_conf(votes, 0.0, POD_START) == 0.0
        assert _max_conf(votes, POD_START, BOUNDARY_END) == 1.0
        assert _max_conf(votes, 12.0, POD_END) == 0.0

    def test_quiet_dialogue_is_not_silence(self) -> None:
        # A tone below the dBFS threshold but spectrally structured must not
        # count as boundary silence.
        detector = SilenceDetector(SilenceConfig(dbfs_threshold=-50.0, min_run_ms=200))
        t = np.arange(RATE, dtype=np.float64) / RATE
        quiet_tone = (2e-3 * np.sin(2 * np.pi * 300 * t)).astype(np.float32)  # ~ -57 dBFS
        for i in range(10):
            block = quiet_tone[i * 800 : (i + 1) * 800]
            detector.observe_audio(AudioEvent(ts=i * 0.1, samples=block, sample_rate=RATE))
        assert detector.vote(1.0).confidence == 0.0

    def test_digital_silence_counts(self) -> None:
        detector = SilenceDetector(SilenceConfig(min_run_ms=200))
        zeros = np.zeros(800, dtype=np.float32)
        for i in range(5):
            detector.observe_audio(AudioEvent(ts=i * 0.1, samples=zeros, sample_rate=RATE))
        assert detector.vote(0.5).confidence == 1.0


class TestLoudness:
    def test_hot_ad_beats_program_baseline(self, tmp_path: Path) -> None:
        detector = LoudnessDetector(LoudnessConfig(window_s=1.5, baseline_s=30.0))
        votes = _replay(tmp_path, detector, video=False, audio=True)
        assert _max_conf(votes, 6.5, POD_START) == 0.0  # warmed up, program level
        assert _max_conf(votes, 12.0, POD_END) == 1.0  # ad mixed ~11 dB hot
        assert _max_conf(votes, 28.0, 32.0) < 0.05  # program resumes

    def test_votes_zero_while_warming(self) -> None:
        detector = LoudnessDetector(LoudnessConfig(window_s=3.0))
        t = np.arange(800, dtype=np.float64) / RATE
        loud = (0.4 * np.sin(2 * np.pi * 880 * t)).astype(np.float32)
        detector.observe_audio(AudioEvent(ts=0.0, samples=loud, sample_rate=RATE))
        vote = detector.vote(0.1)
        assert vote.confidence == 0.0
        assert vote.reason.startswith("warming ")

    def test_baseline_freezes_during_elevation(self) -> None:
        # Feed long program, then a long hot stretch: the baseline must not
        # chase the hot level, so the delta (and vote) stays high throughout.
        detector = LoudnessDetector(LoudnessConfig(window_s=1.5, baseline_s=30.0))
        t = np.arange(800, dtype=np.float64) / RATE
        program = (0.1 * np.sin(2 * np.pi * 440 * t)).astype(np.float32)
        hot = (0.35 * np.sin(2 * np.pi * 880 * t)).astype(np.float32)
        ts = 0.0
        for _ in range(100):  # 10 s program
            detector.observe_audio(AudioEvent(ts=ts, samples=program, sample_rate=RATE))
            ts += 0.1
        for _ in range(600):  # 60 s hot
            detector.observe_audio(AudioEvent(ts=ts, samples=hot, sample_rate=RATE))
            ts += 0.1
        assert detector.vote(ts).confidence == 1.0
