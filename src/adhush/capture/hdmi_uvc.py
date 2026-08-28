"""USB HDMI-to-UVC dongle capture (V4L2/ffmpeg). Primary Pi 4 path.

Video is read from the V4L2 device and audio from ALSA, each through its own
ffmpeg subprocess streaming raw data over a pipe. Timestamps come from the
monotonic clock at read time, normalized to the session start so downstream
timing matches replay semantics.
"""

from __future__ import annotations

import shutil
import subprocess
from collections.abc import Iterator

import numpy as np

from adhush.capture.base import CaptureCaps, CaptureError, CaptureSource
from adhush.config import CaptureConfig
from adhush.events import AudioEvent, FrameEvent
from adhush.util.timing import Clock, monotonic_clock


class HdmiUvcSource(CaptureSource):
    def __init__(self, config: CaptureConfig, clock: Clock = monotonic_clock) -> None:
        self._cfg = config
        self._clock = clock
        self._video_proc: subprocess.Popen[bytes] | None = None
        self._audio_proc: subprocess.Popen[bytes] | None = None
        self._t0: float | None = None

    def open(self) -> None:
        if shutil.which("ffmpeg") is None:
            raise CaptureError("hdmi_uvc requires ffmpeg on PATH")
        cfg = self._cfg
        self._video_proc = subprocess.Popen(
            [
                "ffmpeg", "-v", "error",
                "-f", "v4l2",
                "-framerate", str(cfg.fps),
                "-video_size", f"{cfg.width}x{cfg.height}",
                "-i", cfg.device,
                "-f", "rawvideo", "-pix_fmt", "bgr24", "pipe:1",
            ],
            stdout=subprocess.PIPE,
        )
        self._audio_proc = subprocess.Popen(
            [
                "ffmpeg", "-v", "error",
                "-f", "alsa",
                "-i", cfg.audio_device,
                "-f", "f32le", "-ac", "1", "-ar", str(cfg.audio_rate), "pipe:1",
            ],
            stdout=subprocess.PIPE,
        )
        self._t0 = self._clock()

    def close(self) -> None:
        for proc in (self._video_proc, self._audio_proc):
            if proc is not None:
                proc.terminate()
                proc.wait()
        self._video_proc = None
        self._audio_proc = None

    def caps(self) -> CaptureCaps:
        return CaptureCaps(
            video=True,
            audio=True,
            width=self._cfg.width,
            height=self._cfg.height,
            fps=float(self._cfg.fps),
            sample_rate=self._cfg.audio_rate,
            realtime=True,
        )

    def _now(self) -> float:
        assert self._t0 is not None
        return self._clock() - self._t0

    def frames(self) -> Iterator[FrameEvent]:
        proc = self._video_proc
        if proc is None or proc.stdout is None:
            raise CaptureError("iterate after open()")
        frame_bytes = self._cfg.width * self._cfg.height * 3
        while True:
            chunk = proc.stdout.read(frame_bytes)
            if len(chunk) < frame_bytes:
                return
            frame = np.frombuffer(chunk, dtype=np.uint8).reshape(
                self._cfg.height, self._cfg.width, 3
            )
            yield FrameEvent(ts=self._now(), frame=frame)

    def audio_blocks(self) -> Iterator[AudioEvent]:
        proc = self._audio_proc
        if proc is None or proc.stdout is None:
            raise CaptureError("iterate after open()")
        rate = self._cfg.audio_rate
        block = max(1, rate * self._cfg.audio_block_ms // 1000)
        while True:
            chunk = proc.stdout.read(block * 4)
            if not chunk:
                return
            samples = np.frombuffer(chunk, dtype=np.float32)
            yield AudioEvent(
                ts=self._now() - len(samples) / rate, samples=samples, sample_rate=rate
            )
