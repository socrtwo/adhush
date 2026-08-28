"""Black/near-black frame runs, typical of pod boundaries.

Cheap, precise, low recall: a qualifying run votes 1.0 while it lasts, then
decays over a short hold so fusion's dwell window can see it. It is a boundary
refiner; profile weights keep it from muting alone.
"""

from __future__ import annotations

from typing import ClassVar

from adhush.config import BlackFrameConfig
from adhush.detect.base import Detector
from adhush.events import DetectorVote, FrameEvent
from adhush.util.imageops import downscale, mean_luma, to_luma

_DOWNSCALE_FACTOR = 8
_DECAY_S = 2.5


class BlackFrameDetector(Detector):
    name: ClassVar[str] = "black_frame"
    needs_video: ClassVar[bool] = True

    def __init__(self, config: BlackFrameConfig) -> None:
        self._cfg = config
        self._run_frames = 0
        self._last_luma = 255.0
        self._run_ended_ts: float | None = None
        self._ended_run_frames = 0

    def warmup(self) -> None:
        self._run_frames = 0
        self._last_luma = 255.0
        self._run_ended_ts = None
        self._ended_run_frames = 0

    def observe_frame(self, event: FrameEvent) -> None:
        small = downscale(to_luma(event.frame), _DOWNSCALE_FACTOR)
        self._last_luma = mean_luma(small)
        if self._last_luma <= self._cfg.luma_threshold:
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
                f"black_run frames={self._run_frames} luma={self._last_luma:.1f}",
            )
        if self._run_ended_ts is not None:
            age = ts - self._run_ended_ts
            if 0.0 <= age < _DECAY_S:
                confidence = 1.0 - age / _DECAY_S
                return self._vote(
                    ts,
                    confidence,
                    f"black_run_ended frames={self._ended_run_frames} age_s={age:.2f}",
                )
            self._run_ended_ts = None
        return self._vote(ts, 0.0, f"no_black luma={self._last_luma:.1f}")
