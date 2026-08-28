from pathlib import Path

import numpy as np
import pytest

from adhush.capture.base import CaptureError
from adhush.capture.file_replay import FileReplaySource, write_fixture
from adhush.events import DetectorVote


def _av_fixture(path: Path) -> None:
    frames = np.zeros((20, 8, 8), dtype=np.uint8)
    frame_ts = np.arange(20, dtype=np.float64) / 10.0
    audio = np.zeros(8000, dtype=np.float32)  # 1 s at 8 kHz
    write_fixture(path, frames=frames, frame_ts=frame_ts, audio=audio, audio_rate=8000)


def test_npz_round_trip_caps_and_streams(tmp_path: Path) -> None:
    path = tmp_path / "clip.npz"
    _av_fixture(path)
    with FileReplaySource(path, block_ms=100) as source:
        caps = source.caps()
        assert caps.video and caps.audio
        assert (caps.width, caps.height) == (8, 8)
        assert caps.fps == pytest.approx(10.0)
        assert caps.sample_rate == 8000
        assert not caps.realtime

        frames = list(source.frames())
        blocks = list(source.audio_blocks())
    assert len(frames) == 20
    assert frames[3].ts == pytest.approx(0.3)
    assert len(blocks) == 10
    assert all(len(b.samples) == 800 for b in blocks)
    assert blocks[4].ts == pytest.approx(0.4)


def test_audio_only_fixture(tmp_path: Path) -> None:
    path = tmp_path / "audio.npz"
    write_fixture(path, audio=np.zeros(4000, dtype=np.float32), audio_rate=8000)
    with FileReplaySource(path) as source:
        assert not source.caps().video
        assert source.caps().audio
        assert list(source.frames()) == []


def test_replay_is_deterministic(tmp_path: Path) -> None:
    path = tmp_path / "clip.npz"
    _av_fixture(path)
    with FileReplaySource(path) as source:
        first = [(f.ts, f.frame.sum()) for f in source.frames()]
    with FileReplaySource(path) as source:
        second = [(f.ts, f.frame.sum()) for f in source.frames()]
    assert first == second


def test_missing_file_rejected(tmp_path: Path) -> None:
    with pytest.raises(CaptureError, match="not found"):
        FileReplaySource(tmp_path / "nope.npz").open()


def test_iteration_before_open_rejected(tmp_path: Path) -> None:
    path = tmp_path / "clip.npz"
    _av_fixture(path)
    with pytest.raises(CaptureError, match="open"):
        list(FileReplaySource(path).frames())


def test_empty_fixture_rejected(tmp_path: Path) -> None:
    with pytest.raises(ValueError, match="modality"):
        write_fixture(tmp_path / "empty.npz")


def test_vote_confidence_bounds_enforced() -> None:
    with pytest.raises(ValueError, match="confidence"):
        DetectorVote(detector="x", ts=0.0, confidence=1.5, reason="r")
