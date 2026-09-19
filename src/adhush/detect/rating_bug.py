"""The parental-rating box as an unmute cue (ADR 0023, after MythTV).

US networks flash the rating box — "TV-14", "TV-PG DL" — in the upper-left
corner for ten to fifteen seconds at the start of a programme and again
after every commercial break. Commercials never carry one. MythTV scores a
block for it; here it is *positive programme evidence*: the moment the box
appears, the programme is back, and the engine may end a mute early on it
(the late unmute being the failure this project fears most).

No OCR: the box is a bright, filled rectangle with dark text appearing
where nothing bright was a moment before. Inside the configured ROI the
detector finds the bounding box of bright pixels and asks whether it is
box-shaped (wider than tall, mostly filled, a good share of the ROI) and
whether it *appeared* — the ROI was not bright over the previous seconds —
and then stays for at least ``min_present_s``. It votes 0 (programme) with
weight while the box is up and for ``hold_s`` after, is inert otherwise, and
never votes for an ad.
"""

from __future__ import annotations

from typing import ClassVar

import numpy as np

from adhush.config import RatingBugConfig
from adhush.detect.base import Detector
from adhush.events import DetectorVote, FrameEvent
from adhush.util.imageops import downscale, extract_roi, to_luma

_TARGET_ROWS = 90


def box_score(roi: np.ndarray, bright: int) -> tuple[float, float, float]:
    """(fill, aspect, share) of the bright bounding box in a luma ROI:
    fill = bright pixels / box area, aspect = box w / h, share = box area /
    ROI area. All zero when nothing is bright."""
    mask = roi >= bright
    ys, xs = np.nonzero(mask)
    if len(ys) < 8:
        return 0.0, 0.0, 0.0
    h = int(ys.max() - ys.min() + 1)
    w = int(xs.max() - xs.min() + 1)
    area = float(h * w)
    return float(mask.sum()) / area, w / h, area / float(roi.size)


class RatingBugDetector(Detector):
    name: ClassVar[str] = "rating_bug"
    needs_video: ClassVar[bool] = True

    def __init__(self, config: RatingBugConfig) -> None:
        self._cfg = config
        self.warmup()

    def warmup(self) -> None:
        self._next_sample_ts = -np.inf
        self._quiet_since: float | None = None  # the ROI has had no box since
        self._box_since: float | None = None  # a box has been up since
        self._present_until = -np.inf
        self._last = (0.0, 0.0, 0.0)
        self._sightings = 0

    def _boxed(self, frame: np.ndarray) -> bool:
        luma = to_luma(frame)
        factor = max(1, luma.shape[0] // _TARGET_ROWS)
        small = downscale(luma, factor)
        r = self._cfg.roi
        roi = extract_roi(small, r.x, r.y, r.w, r.h)
        fill, aspect, share = box_score(roi, self._cfg.bright)
        self._last = (fill, aspect, share)
        return (
            fill >= self._cfg.min_fill
            and self._cfg.min_aspect <= aspect <= self._cfg.max_aspect
            and self._cfg.min_share <= share <= self._cfg.max_share
        )

    def observe_frame(self, event: FrameEvent) -> None:
        if event.ts < self._next_sample_ts:
            return
        self._next_sample_ts = event.ts + self._cfg.sample_interval_s
        boxed = self._boxed(event.frame)
        if not boxed:
            self._box_since = None
            if self._quiet_since is None:
                self._quiet_since = event.ts
            return
        # A box is up. It counts only if it *appeared*: the ROI had been
        # box-free for appear_after_s (a permanent bright corner is scenery).
        if self._box_since is None:
            if self._quiet_since is None or event.ts - self._quiet_since < self._cfg.appear_after_s:
                return
            self._box_since = event.ts
            self._quiet_since = None
        if event.ts - self._box_since >= self._cfg.min_present_s:
            if self._present_until < event.ts:
                self._sightings += 1
            self._present_until = event.ts + self._cfg.hold_s

    @property
    def program_present(self) -> bool:
        return self._next_sample_ts - self._cfg.sample_interval_s < self._present_until

    @property
    def voting(self) -> bool:
        return self.program_present

    def vote(self, ts: float) -> DetectorVote:
        fill, aspect, share = self._last
        if ts < self._present_until:
            return self._vote(ts, 0.0, f"rating_box fill={fill:.2f} aspect={aspect:.1f} share={share:.2f} left_s={self._present_until - ts:.1f}")
        return self._vote(ts, 0.0, f"no_rating_box fill={fill:.2f}")

    def describe(self) -> str:
        return f"{self._sightings} rating box(es) seen" + (" — programme" if self.program_present else "")
