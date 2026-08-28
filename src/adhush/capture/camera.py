"""Camera-pointed-at-screen capture: keystone correction, screen-quad detection, glare handling.

Video comes from the platform camera (v4l2 / avfoundation / dshow) and audio
from the device microphone. The lit screen is found as the dominant bright
rectangle (``detect_screen_bbox``): rows and columns whose brightness clears
an adaptive threshold, re-estimated every few seconds so a nudged tripod
self-corrects. Frames are cropped to that box before hitting the bus — an
axis-aligned approximation of keystone correction that holds for a roughly
front-on camera; strong perspective needs a better mount, not more math.
Glare is tolerated rather than corrected: highlight pixels are excluded from
the brightness profile so a lamp reflection doesn't drag the box.
"""

from __future__ import annotations

import shutil
import subprocess
import sys
from collections.abc import Iterator

import numpy as np
import numpy.typing as npt

from adhush.capture.base import CaptureCaps, CaptureError, CaptureSource
from adhush.capture.microphone import audio_ffmpeg_args
from adhush.config import CaptureConfig
from adhush.events import AudioEvent, FrameEvent
from adhush.util.imageops import downscale, to_luma
from adhush.util.timing import Clock, monotonic_clock

# How often the screen box is re-estimated.
_REDETECT_INTERVAL_S = 5.0
_DETECT_DOWNSCALE = 8
# The detected box must cover at least this fraction of the frame to be
# believed; otherwise the full frame passes through uncropped.
_MIN_AREA_FRACTION = 0.08
# Pixels this close to saturation are glare and don't vote for the box.
_GLARE_LUMA = 250


def detect_screen_bbox(
    luma: npt.NDArray[np.uint8],
) -> tuple[int, int, int, int] | None:
    """Bright-rectangle detector; returns (x0, y0, x1, y1) or None.

    Works on a downscaled luma frame. The threshold adapts to the frame:
    halfway between the dark surround and the lit screen.
    """
    values = luma.astype(np.float64)
    values[values >= _GLARE_LUMA] = 0.0
    low = float(np.percentile(values, 20))
    high = float(np.percentile(values, 95))
    if high - low < 20.0:
        return None  # no lit-screen contrast in view
    threshold = (low + high) / 2
    bright = values > threshold
    # A row/column belongs to the screen if a third of it is lit — the screen
    # can occupy well under half the field of view on a wide shot.
    rows = np.flatnonzero(bright.mean(axis=1) > 0.35)
    cols = np.flatnonzero(bright.mean(axis=0) > 0.35)
    if rows.size == 0 or cols.size == 0:
        return None
    x0, x1 = int(cols[0]), int(cols[-1]) + 1
    y0, y1 = int(rows[0]), int(rows[-1]) + 1
    if (x1 - x0) * (y1 - y0) < _MIN_AREA_FRACTION * luma.size:
        return None
    return x0, y0, x1, y1


def camera_ffmpeg_args(config: CaptureConfig, platform: str) -> list[str]:
    """ffmpeg argv for raw bgr24 frames from the platform camera."""
    size = f"{config.width}x{config.height}"
    head = ["ffmpeg", "-v", "error"]
    if platform == "linux":
        head += ["-f", "v4l2", "-framerate", str(config.fps), "-video_size", size,
                 "-i", config.device or "/dev/video0"]
    elif platform == "darwin":
        index = config.device if config.device.isdigit() else "0"
        head += ["-f", "avfoundation", "-framerate", str(config.fps),
                 "-video_size", size, "-i", f"{index}:none"]
    elif platform == "win32":
        head += ["-f", "dshow", "-video_size", size, "-framerate", str(config.fps),
                 "-i", f"video={config.device}"]
    else:
        raise CaptureError(f"camera capture is not supported on platform {platform}")
    return head + ["-f", "rawvideo", "-pix_fmt", "bgr24", "-an", "pipe:1"]


class CameraSource(CaptureSource):
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
        self._bbox: tuple[int, int, int, int] | None = None
        self._next_detect_ts = 0.0

    @property
    def _audio_enabled(self) -> bool:
        return self._cfg.audio_device not in ("", "none")

    def open(self) -> None:
        if shutil.which("ffmpeg") is None:
            raise CaptureError("camera capture requires ffmpeg on PATH")
        self._video_proc = subprocess.Popen(
            camera_ffmpeg_args(self._cfg, self._platform), stdout=subprocess.PIPE
        )
        if self._audio_enabled:
            self._audio_proc = subprocess.Popen(
                audio_ffmpeg_args(self._cfg, self._platform), stdout=subprocess.PIPE
            )
        self._t0 = self._clock()
        self._bbox = None
        self._next_detect_ts = 0.0

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

    def _crop(self, frame: npt.NDArray[np.uint8], ts: float) -> npt.NDArray[np.uint8]:
        if not self._cfg.autocrop:
            return frame
        if ts >= self._next_detect_ts:
            self._next_detect_ts = ts + _REDETECT_INTERVAL_S
            small = downscale(to_luma(frame), _DETECT_DOWNSCALE)
            box = detect_screen_bbox(small)
            if box is not None:
                s = _DETECT_DOWNSCALE
                self._bbox = (box[0] * s, box[1] * s, box[2] * s, box[3] * s)
        if self._bbox is None:
            return frame
        x0, y0, x1, y1 = self._bbox
        return frame[y0:y1, x0:x1]

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
            ts = self._now()
            yield FrameEvent(ts=ts, frame=self._crop(frame, ts))

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
