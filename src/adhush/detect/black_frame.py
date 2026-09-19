"""Black/near-black — or any uniform-colour — frame runs at pod boundaries.

Cheap, precise, low recall: a qualifying run votes 1.0 while it lasts, then
decays over a short hold so fusion's dwell window can see it. It is a boundary
refiner; profile weights keep it from muting alone.

Since ADR 0023 a frame also qualifies when it is *uniform*: a white flash,
a solid colour card, a broadcaster's grey slate — the separators European
networks use instead of black, and the ones comskip's ``non_uniformity``
catches. A frame is uniform when the standard deviation of its downscaled
luma is at most ``uniform_spread`` (0 turns this off). A dark, busy scene has
a spread far above it.
"""

from __future__ import annotations

from typing import ClassVar

import numpy as np

from adhush.config import BlackFrameConfig
from adhush.detect.base import Detector
from adhush.events import DetectorVote, FrameEvent
from adhush.util.imageops import downscale, mean_luma, to_luma

_DOWNSCALE_FACTOR = 8
_SPREAD_STRIDE = 4
_DECAY_S = 2.5


class BlackFrameDetector(Detector):
    name: ClassVar[str] = "black_frame"
    needs_video: ClassVar[bool] = True

    def __init__(self, config: BlackFrameConfig) -> None:
        self._cfg = config
        self._run_frames = 0
        self._last_luma = 255.0
        self._last_spread = 255.0
        self._run_kind = "black"
        self._run_ended_ts: float | None = None
        self._ended_run_frames = 0

    def warmup(self) -> None:
        self._run_frames = 0
        self._last_luma = 255.0
        self._last_spread = 255.0
        self._run_kind = "black"
        self._run_ended_ts = None
        self._ended_run_frames = 0

    def observe_frame(self, event: FrameEvent) -> None:
        luma = to_luma(event.frame)
        small = downscale(luma, _DOWNSCALE_FACTOR)
        self._last_luma = mean_luma(small)
        # Spread on a strided sample of the raw pixels: area-averaging would
        # smooth a busy dark scene into a "flat" one.
        self._last_spread = float(np.std(luma[::_SPREAD_STRIDE, ::_SPREAD_STRIDE].astype(np.float32)))
        black = self._last_luma <= self._cfg.luma_threshold
        uniform = 0.0 < self._cfg.uniform_spread and self._last_spread <= self._cfg.uniform_spread
        if black or uniform:
            if self._run_frames == 0:
                self._run_kind = "black" if black else "uniform"
            self._run_frames += 1
            self._run_ended_ts = None
        else:
            if self._run_frames >= self._cfg.min_run_frames:
                self._run_ended_ts = event.ts
                self._ended_run_frames = self._run_frames
            self._run_frames = 0

    def vote(self, ts: float) -> DetectorVote:
        if self._run_frames >= self._cfg.min_run_frames:
            return self._vote(
                ts,
                1.0,
                f"{self._run_kind}_run frames={self._run_frames} luma={self._last_luma:.1f} spread={self._last_spread:.1f}",
            )
        if self._run_ended_ts is not None:
            age = ts - self._run_ended_ts
            if 0.0 <= age < _DECAY_S:
                confidence = 1.0 - age / _DECAY_S
                return self._vote(
                    ts,
                    confidence,
                    f"{self._run_kind}_run_ended frames={self._ended_run_frames} age_s={age:.2f}",
                )
            self._run_ended_ts = None
        return self._vote(ts, 0.0, f"no_black luma={self._last_luma:.1f}")
