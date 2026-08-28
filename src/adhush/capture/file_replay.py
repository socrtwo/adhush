"""Deterministic replay of recorded A/V for offline testing and CI.

Two input forms:

- ``.npz`` fixtures — numpy archives that need no external tooling, the format
  CI fixtures use. Keys: ``frames`` (N,H,W[,3] uint8), ``frame_ts`` (N float64,
  seconds), ``audio`` (M float32 mono), ``audio_rate`` (int). Any modality may
  be absent. ``write_fixture`` produces them.
- any media file ffmpeg can decode — video is streamed as bgr24 raw frames,
  audio as mono f32le, each through its own ffmpeg subprocess.

Timestamps are media time, so replay is bit-identical run to run.
"""

from __future__ import annotations

import shutil
import subprocess
from collections.abc import Iterator
from pathlib import Path

import numpy as np
import numpy.typing as npt

from adhush.capture.base import CaptureCaps, CaptureError, CaptureSource
from adhush.events import AudioEvent, FrameEvent


def write_fixture(
    path: Path,
    *,
    frames: npt.NDArray[np.uint8] | None = None,
    frame_ts: npt.NDArray[np.float64] | None = None,
    audio: npt.NDArray[np.float32] | None = None,
    audio_rate: int = 0,
) -> None:
    """Write a replay fixture; tests and capture tools share this format."""
    payload: dict[str, np.ndarray] = {}
    if frames is not None:
        if frame_ts is None or len(frame_ts) != len(frames):
            raise ValueError("frames require matching frame_ts")
        payload["frames"] = frames
        payload["frame_ts"] = np.asarray(frame_ts, dtype=np.float64)
    if audio is not None:
        if audio_rate <= 0:
            raise ValueError("audio requires audio_rate")
        payload["audio"] = np.asarray(audio, dtype=np.float32)
        payload["audio_rate"] = np.asarray(audio_rate)
    if not payload:
        raise ValueError("fixture needs at least one modality")
    np.savez_compressed(path, **payload)  # type: ignore[arg-type]


class FileReplaySource(CaptureSource):
    def __init__(
        self,
        path: Path,
        *,
        block_ms: int = 100,
        width: int = 0,
        height: int = 0,
        fps: float = 0.0,
        audio_rate: int = 48000,
    ) -> None:
        self._path = Path(path)
        self._block_ms = block_ms
        self._want_w, self._want_h, self._want_fps = width, height, fps
        self._want_rate = audio_rate
        self._npz: dict[str, np.ndarray] | None = None
        self._caps: CaptureCaps | None = None
        self._opened = False

    # -- lifecycle -----------------------------------------------------------

    def open(self) -> None:
        if not self._path.is_file():
            raise CaptureError(f"replay input not found: {self._path}")
        if self._path.suffix == ".npz":
            self._open_npz()
        else:
            self._open_media()
        self._opened = True

    def _open_npz(self) -> None:
        with np.load(self._path) as archive:
            self._npz = {key: archive[key] for key in archive.files}
        frames = self._npz.get("frames")
        audio = self._npz.get("audio")
        if frames is None and audio is None:
            raise CaptureError(f"{self._path}: fixture has neither frames nor audio")
        fps = 0.0
        if frames is not None and len(frames) > 1:
            ts = self._npz["frame_ts"]
            fps = (len(ts) - 1) / float(ts[-1] - ts[0]) if ts[-1] > ts[0] else 0.0
        self._caps = CaptureCaps(
            video=frames is not None,
            audio=audio is not None,
            width=int(frames.shape[2]) if frames is not None else 0,
            height=int(frames.shape[1]) if frames is not None else 0,
            fps=fps,
            sample_rate=int(self._npz["audio_rate"]) if audio is not None else 0,
            realtime=False,
        )

    def _open_media(self) -> None:
        if shutil.which("ffprobe") is None or shutil.which("ffmpeg") is None:
            raise CaptureError("media replay requires ffmpeg/ffprobe on PATH")
        probe = subprocess.run(
            [
                "ffprobe", "-v", "error", "-select_streams", "v:0",
                "-show_entries", "stream=width,height,avg_frame_rate",
                "-of", "csv=p=0", str(self._path),
            ],
            capture_output=True,
            text=True,
            check=False,
        )
        video = probe.returncode == 0 and bool(probe.stdout.strip())
        width = height = 0
        fps = 0.0
        if video:
            w_s, h_s, rate_s = probe.stdout.strip().split(",")[:3]
            width, height = int(w_s), int(h_s)
            num, _, den = rate_s.partition("/")
            fps = float(num) / float(den or 1)
            if self._want_w and self._want_h:
                width, height = self._want_w, self._want_h
            if self._want_fps:
                fps = self._want_fps
        audio_probe = subprocess.run(
            [
                "ffprobe", "-v", "error", "-select_streams", "a:0",
                "-show_entries", "stream=codec_type", "-of", "csv=p=0", str(self._path),
            ],
            capture_output=True,
            text=True,
            check=False,
        )
        audio = audio_probe.returncode == 0 and bool(audio_probe.stdout.strip())
        if not video and not audio:
            raise CaptureError(f"{self._path}: no decodable audio or video stream")
        self._caps = CaptureCaps(
            video=video,
            audio=audio,
            width=width,
            height=height,
            fps=fps,
            sample_rate=self._want_rate if audio else 0,
            realtime=False,
        )

    def close(self) -> None:
        self._npz = None
        self._opened = False

    def caps(self) -> CaptureCaps:
        if self._caps is None:
            raise CaptureError("caps() before open()")
        return self._caps

    # -- streams -------------------------------------------------------------

    def frames(self) -> Iterator[FrameEvent]:
        self._require_open()
        if not self.caps().video:
            return iter(())
        if self._npz is not None:
            return self._npz_frames()
        return self._media_frames()

    def audio_blocks(self) -> Iterator[AudioEvent]:
        self._require_open()
        if not self.caps().audio:
            return iter(())
        if self._npz is not None:
            return self._npz_audio()
        return self._media_audio()

    def _require_open(self) -> None:
        if not self._opened:
            raise CaptureError("iterate after open()")

    def _npz_frames(self) -> Iterator[FrameEvent]:
        assert self._npz is not None
        frames = self._npz["frames"]
        ts = self._npz["frame_ts"]
        for i in range(len(frames)):
            yield FrameEvent(ts=float(ts[i]), frame=frames[i])

    def _npz_audio(self) -> Iterator[AudioEvent]:
        assert self._npz is not None
        audio = self._npz["audio"].astype(np.float32, copy=False)
        rate = int(self._npz["audio_rate"])
        block = max(1, rate * self._block_ms // 1000)
        for start in range(0, len(audio), block):
            samples = audio[start : start + block]
            if len(samples) == 0:
                break
            yield AudioEvent(ts=start / rate, samples=samples, sample_rate=rate)

    def _media_frames(self) -> Iterator[FrameEvent]:
        caps = self.caps()
        frame_bytes = caps.width * caps.height * 3
        args = ["ffmpeg", "-v", "error", "-i", str(self._path)]
        if self._want_w and self._want_h:
            args += ["-vf", f"scale={caps.width}:{caps.height}"]
        if self._want_fps:
            args += ["-r", f"{caps.fps}"]
        args += ["-f", "rawvideo", "-pix_fmt", "bgr24", "-an", "pipe:1"]
        proc = subprocess.Popen(args, stdout=subprocess.PIPE)
        assert proc.stdout is not None
        try:
            index = 0
            while True:
                chunk = proc.stdout.read(frame_bytes)
                if len(chunk) < frame_bytes:
                    break
                frame = np.frombuffer(chunk, dtype=np.uint8).reshape(
                    caps.height, caps.width, 3
                )
                yield FrameEvent(ts=index / caps.fps, frame=frame)
                index += 1
        finally:
            proc.stdout.close()
            proc.wait()

    def _media_audio(self) -> Iterator[AudioEvent]:
        rate = self.caps().sample_rate
        block = max(1, rate * self._block_ms // 1000)
        proc = subprocess.Popen(
            [
                "ffmpeg", "-v", "error", "-i", str(self._path),
                "-f", "f32le", "-ac", "1", "-ar", str(rate), "-vn", "pipe:1",
            ],
            stdout=subprocess.PIPE,
        )
        assert proc.stdout is not None
        try:
            start = 0
            while True:
                chunk = proc.stdout.read(block * 4)
                if not chunk:
                    break
                samples = np.frombuffer(chunk, dtype=np.float32)
                yield AudioEvent(ts=start / rate, samples=samples, sample_rate=rate)
                start += len(samples)
        finally:
            proc.stdout.close()
            proc.wait()
