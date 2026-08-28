"""Cut-rate spike detection; ads average far shorter shot lengths than program.

A shot change is a downscaled mean-abs luma delta above ``diff_threshold``.
Cut timestamps within the rolling window give a cuts-per-minute rate mapped
linearly to confidence between ``low_cpm`` and ``high_cpm``. A soft,
corroborating signal by design — its profile weight keeps it from ever
mattering alone.
"""

from __future__ import annotations

from collections import deque
from typing import ClassVar

import numpy as np
import numpy.typing as npt

from adhush.config import SceneCutConfig
from adhush.detect.base import Detector
from adhush.events import DetectorVote, FrameEvent
from adhush.util.imageops import downscale, to_luma

_DOWNSCALE_FACTOR = 8


class SceneCutDetector(Detector):
    name: ClassVar[str] = "scene_cut"
    needs_video: ClassVar[bool] = True

    def __init__(self, config: SceneCutConfig) -> None:
        self._cfg = config
        self._prev: npt.NDArray[np.float32] | None = None
        self._cuts: deque[float] = deque()
        self._last_ts = 0.0

    def warmup(self) -> None:
        self._prev = None
        self._cuts.clear()
        self._last_ts = 0.0

    def observe_frame(self, event: FrameEvent) -> None:
        small = downscale(to_luma(event.frame), _DOWNSCALE_FACTOR).astype(np.float32)
        self._last_ts = event.ts
        if self._prev is not None and self._prev.shape == small.shape:
            diff = float(np.abs(small - self._prev).mean())
            if diff > self._cfg.diff_threshold:
                self._cuts.append(event.ts)
        self._prev = small
        while self._cuts and event.ts - self._cuts[0] > self._cfg.window_s:
            self._cuts.popleft()

    def _rate_cpm(self) -> float:
        window = min(self._cfg.window_s, max(self._last_ts, 1e-9))
        return len(self._cuts) * 60.0 / window

    def vote(self, ts: float) -> DetectorVote:
        rate = self._rate_cpm()
        span = self._cfg.high_cpm - self._cfg.low_cpm
        confidence = max(0.0, min(1.0, (rate - self._cfg.low_cpm) / span))
        return self._vote(ts, confidence, f"cut_rate cpm={rate:.1f} cuts={len(self._cuts)}")
