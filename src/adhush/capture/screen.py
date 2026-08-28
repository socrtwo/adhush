"""OS screen-grab capture for Windows/macOS/Linux/ChromeOS/Web (streaming apps).

Video comes from the platform's grabber (x11grab on Linux/ChromeOS-Crostini,
avfoundation screen devices on macOS, gdigrab on Windows) and audio from the
same stack as the microphone source — point ``audio_device`` at a system
loopback (e.g. ``pulse:....monitor``) to hear what the app plays, or leave a
real microphone to hear the TV; ``none`` disables audio. Each stream is its
own ffmpeg subprocess; argv builders are pure functions for testing.
"""

from __future__ import annotations

import shutil
import subprocess
import sys
from collections.abc import Iterator

import numpy as np

from adhush.capture.base import CaptureCaps, CaptureError, CaptureSource
from adhush.capture.microphone import audio_ffmpeg_args
from adhush.config import CaptureConfig
from adhush.events import AudioEvent, FrameEvent
from adhush.util.timing import Clock, monotonic_clock


def screen_ffmpeg_args(config: CaptureConfig, platform: str) -> list[str]:
    """ffmpeg argv for raw bgr24 frames of the screen."""
    size = f"{config.width}x{config.height}"
    head = ["ffmpeg", "-v", "error"]
    if platform == "linux":
        device = config.device if config.device.startswith(":") else ":0.0"
        head += ["-f", "x11grab", "-framerate", str(config.fps), "-video_size", size,
                 "-i", device]
    elif platform == "darwin":
        # avfoundation screen devices: "<index>:none"; device holds the index.
        index = config.device if config.device.isdigit() else "1"
        head += ["-f", "avfoundation", "-framerate", str(config.fps),
                 "-i", f"{index}:none"]
    elif platform == "win32":
        head += ["-f", "gdigrab", "-framerate", str(config.fps), "-i", "desktop"]
    else:
        raise CaptureError(f"screen capture is not supported on platform {platform}")
    return head + [
        "-vf", f"scale={config.width}:{config.height}",
        "-f", "rawvideo", "-pix_fmt", "bgr24", "-an", "pipe:1",
    ]


class ScreenSource(CaptureSource):
    def __init__(
        self,
        config: CaptureConfig,
        clock: Clock = monotonic_clock,
        platform: str | None = None,
    ) -> None:
        self._cfg = config
        self._clock = clock
        self._platform = platform if platform is not None else sys.platform
        self._video_proc: subprocess.Popen[bytes] | None = None
        self._audio_proc: subprocess.Popen[bytes] | None = None
        self._t0: float | None = None

    @property
    def _audio_enabled(self) -> bool:
        return self._cfg.audio_device not in ("", "none")

    def open(self) -> None:
        if shutil.which("ffmpeg") is None:
            raise CaptureError("screen capture requires ffmpeg on PATH")
        self._video_proc = subprocess.Popen(
            screen_ffmpeg_args(self._cfg, self._platform), stdout=subprocess.PIPE
        )
        if self._audio_enabled:
            self._audio_proc = subprocess.Popen(
                audio_ffmpeg_args(self._cfg, self._platform), stdout=subprocess.PIPE
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
            audio=self._audio_enabled,
            width=self._cfg.width,
            height=self._cfg.height,
            fps=float(self._cfg.fps),
            sample_rate=self._cfg.audio_rate if self._audio_enabled else 0,
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
        if proc is None:
            return iter(())
        return self._read_audio(proc)

    def _read_audio(self, proc: subprocess.Popen[bytes]) -> Iterator[AudioEvent]:
        assert proc.stdout is not None
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
