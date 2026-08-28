"""Audio-only capture for devices with no video path (phones, laptops).

One ffmpeg subprocess streams mono float32 from the platform's audio stack.
The device string may carry an explicit input format prefix — ``alsa:hw:1``,
``pulse:...``, ``avfoundation::0``, ``dshow:audio=Microphone`` — otherwise
the platform default applies (Linux: alsa, macOS: avfoundation, Windows:
dshow). Argument construction is a pure function so every platform's command
line is testable without ffmpeg.
"""

from __future__ import annotations

import shutil
import subprocess
import sys
from collections.abc import Iterator

import numpy as np

from adhush.capture.base import CaptureCaps, CaptureError, CaptureSource
from adhush.config import CaptureConfig
from adhush.events import AudioEvent, FrameEvent
from adhush.util.timing import Clock, monotonic_clock

_FORMATS = ("alsa", "pulse", "avfoundation", "dshow", "openal")
_PLATFORM_DEFAULT_FORMAT = {"linux": "alsa", "darwin": "avfoundation", "win32": "dshow"}


def parse_audio_device(device: str, platform: str) -> tuple[str, str]:
    """Split an ``fmt:device`` string; default the format from the platform."""
    for fmt in _FORMATS:
        prefix = fmt + ":"
        if device.startswith(prefix):
            return fmt, device[len(prefix) :]
    default = _PLATFORM_DEFAULT_FORMAT.get(platform)
    if default is None:
        raise CaptureError(f"no default audio input format for platform {platform}")
    return default, device


def audio_ffmpeg_args(config: CaptureConfig, platform: str) -> list[str]:
    """ffmpeg argv for a mono float32 stream from the configured device."""
    fmt, device = parse_audio_device(config.audio_device, platform)
    if fmt == "dshow" and not device.startswith("audio="):
        device = f"audio={device}"
    if fmt == "avfoundation" and ":" not in device:
        device = f":{device or '0'}"  # audio-only avfoundation spec
    return [
        "ffmpeg", "-v", "error",
        "-f", fmt,
        "-i", device,
        "-f", "f32le", "-ac", "1", "-ar", str(config.audio_rate), "pipe:1",
    ]


class MicrophoneSource(CaptureSource):
    def __init__(
        self,
        config: CaptureConfig,
        clock: Clock = monotonic_clock,
        platform: str | None = None,
    ) -> None:
        self._cfg = config
        self._clock = clock
        self._platform = platform if platform is not None else sys.platform
        self._proc: subprocess.Popen[bytes] | None = None
        self._t0: float | None = None

    def open(self) -> None:
        if shutil.which("ffmpeg") is None:
            raise CaptureError(f"{self._cfg.backend} capture requires ffmpeg on PATH")
        self._proc = subprocess.Popen(
            audio_ffmpeg_args(self._cfg, self._platform), stdout=subprocess.PIPE
        )
        self._t0 = self._clock()

    def close(self) -> None:
        if self._proc is not None:
            self._proc.terminate()
            self._proc.wait()
        self._proc = None

    def caps(self) -> CaptureCaps:
        return CaptureCaps(
            video=False,
            audio=True,
            sample_rate=self._cfg.audio_rate,
            realtime=True,
        )

    def frames(self) -> Iterator[FrameEvent]:
        return iter(())

    def audio_blocks(self) -> Iterator[AudioEvent]:
        proc = self._proc
        if proc is None or proc.stdout is None or self._t0 is None:
            raise CaptureError("iterate after open()")
        rate = self._cfg.audio_rate
        block = max(1, rate * self._cfg.audio_block_ms // 1000)
        while True:
            chunk = proc.stdout.read(block * 4)
            if not chunk:
                return
            samples = np.frombuffer(chunk, dtype=np.float32)
            yield AudioEvent(
                ts=self._clock() - self._t0 - len(samples) / rate,
                samples=samples,
                sample_rate=rate,
            )
