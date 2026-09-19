"""Letterbox/pillarbox transitions between program and ad (ADR 0021).

A channel keeps one picture shape for hours — 16:9 full frame on cable news,
2.35:1 in black bars for a film — and many commercials arrive in another:
4:3 spots pillarboxed, an old film clip letterboxed inside a 16:9 spot.
Comskip has used the same cue on recordings for twenty years. This detector
measures the bars on the shared decoded frame (dark, flat rows at the top
and bottom, dark, flat columns at the sides), turns what is left into an
active-picture aspect ratio, keeps a rolling mode of that shape as the
programme's baseline, and votes 1.0 while the picture holds a different
shape. It only sees the HDMI and screen-capture paths: a camera at the set
has its own crop, and a phone by the TV never runs it.

It never mutes alone (default weight): a news channel cutting to archive
footage changes shape too. What it adds is a vote that is *sustained* for
the length of the spot, which is what the transient boundary signals (black
frame, silence) lack and what lets loudness carry a mute without over-
reaching. It votes only while the shape is changed; the programme's own
shape is no evidence of anything, so it stays out of the fusion normaliser
the rest of the time. A whole black frame is not a shape and is left to
black_frame.
"""

from __future__ import annotations

from collections import Counter, deque
from typing import ClassVar

import numpy as np

from adhush.config import AspectChangeConfig
from adhush.detect.base import Detector
from adhush.events import DetectorVote, FrameEvent
from adhush.util.imageops import downscale, to_luma

# Work on at most ~180 rows: bars are wide features, and 720p/1080p rows are
# averaged four-to-one before anything is measured.
_TARGET_ROWS = 180
# Two shapes are the same shape within this: 16:9 read through noisy edges
# lands at 1.7–1.85, never at 4:3's 1.33 or 14:9's 1.56.
_SAME_SHAPE = 0.12
# Less picture than this in either direction is a black frame, not a shape.
_MIN_ACTIVE_FRACTION = 0.25
# Shape keys are the aspect rounded to this for the rolling mode.
_KEY_STEP = 0.1


def bars(
    luma: np.ndarray, bar_luma: float, bar_spread: float
) -> tuple[int, int, int, int]:
    """Count dark, flat rows from the top and bottom and columns from the
    left and right of a luma frame: (top, bottom, left, right)."""
    rows_mean = luma.mean(axis=1)
    rows_std = luma.std(axis=1)
    cols_mean = luma.mean(axis=0)
    cols_std = luma.std(axis=0)
    row_bar = (rows_mean <= bar_luma) & (rows_std <= bar_spread)
    col_bar = (cols_mean <= bar_luma) & (cols_std <= bar_spread)
    return _leading(row_bar), _leading(row_bar[::-1]), _leading(col_bar), _leading(col_bar[::-1])


def _leading(flags: np.ndarray) -> int:
    hits = np.flatnonzero(~flags)
    return int(hits[0]) if len(hits) else len(flags)


class AspectChangeDetector(Detector):
    name: ClassVar[str] = "aspect_change"
    needs_video: ClassVar[bool] = True

    def __init__(self, config: AspectChangeConfig) -> None:
        self._cfg = config
        self.warmup()

    def warmup(self) -> None:
        self._next_sample_ts = -np.inf
        self._shape: float | None = None  # the confirmed current shape
        self._candidate: tuple[float, float] | None = None  # (aspect, first seen ts)
        self._changed_ts: float | None = None
        self._changed_from = 0.0
        self._history: deque[tuple[float, float]] = deque()  # (ts, key) over baseline_s
        self._seen_s = 0.0
        self._last_bars = (0, 0, 0, 0)
        self._last_aspect: float | None = None

    # -- shape bookkeeping ---------------------------------------------------

    @staticmethod
    def _key(aspect: float) -> float:
        return round(aspect / _KEY_STEP) * _KEY_STEP

    @property
    def baseline(self) -> float | None:
        """The programme's shape: the mode of the confirmed shapes seen lately."""
        if self._seen_s < self._cfg.min_baseline_s or not self._history:
            return None
        counts = Counter(k for _, k in self._history)
        return counts.most_common(1)[0][0]

    @property
    def shape(self) -> float | None:
        return self._shape

    @property
    def voting(self) -> bool:
        """Only while the picture holds a shape other than the programme's.
        The same shape says nothing — most spots keep it — so a 0 vote would
        only dilute the detectors that do see something (fusion's normaliser);
        like the jingle, this one speaks only when it has evidence."""
        return self._changed_ts is not None

    def measure(self, frame: np.ndarray) -> float | None:
        """Active-picture aspect ratio of one frame, or None for a black frame."""
        luma = to_luma(frame)
        factor = max(1, luma.shape[0] // _TARGET_ROWS)
        small = downscale(luma, factor)
        h, w = small.shape
        top, bottom, left, right = bars(small, self._cfg.bar_luma, self._cfg.bar_spread)
        self._last_bars = (top, bottom, left, right)
        active_h = h - top - bottom
        active_w = w - left - right
        if active_h < _MIN_ACTIVE_FRACTION * h or active_w < _MIN_ACTIVE_FRACTION * w:
            return None
        return float(self._cfg.frame_aspect * (active_w / w) / (active_h / h))

    def observe_frame(self, event: FrameEvent) -> None:
        if event.ts < self._next_sample_ts:
            return
        self._next_sample_ts = event.ts + self._cfg.sample_interval_s
        aspect = self.measure(event.frame)
        if aspect is None:
            return  # black frame: black_frame's business, and no shape to learn
        self._last_aspect = aspect
        if self._shape is None:
            self._shape = aspect
        elif abs(aspect - self._shape) > _SAME_SHAPE:
            # A different shape must hold for confirm_s before it counts: a
            # single dark, flat frame of a shot is not a bar.
            if self._candidate is None or abs(aspect - self._candidate[0]) > _SAME_SHAPE:
                self._candidate = (aspect, event.ts)
            elif event.ts - self._candidate[1] >= self._cfg.confirm_s:
                self._shape = aspect
                self._candidate = None
        else:
            self._candidate = None
        key = self._key(self._shape)
        if self._changed_ts is None:
            base = self.baseline
            if base is not None and key != base:
                self._changed_ts = event.ts
                self._changed_from = base
                return
            # Only the programme's own shape feeds the rolling mode: the
            # baseline freezes while the picture is changed, as loudness's does.
            self._history.append((event.ts, key))
            self._seen_s += self._cfg.sample_interval_s
            while self._history and event.ts - self._history[0][0] > self._cfg.baseline_s:
                self._history.popleft()
        elif key == self._changed_from:
            self._changed_ts = None
        elif event.ts - self._changed_ts >= self._cfg.max_change_s:
            # Changed longer than any break: this is the programme now.
            self._history.clear()
            self._history.append((event.ts, key))
            self._changed_ts = None

    def vote(self, ts: float) -> DetectorVote:
        base = self.baseline
        if base is None or self._shape is None:
            return self._vote(ts, 0.0, f"aspect_learning seen_s={self._seen_s:.0f}")
        if self._changed_ts is not None:
            t, b, left, r = self._last_bars
            return self._vote(
                ts,
                1.0,
                f"aspect_changed from={base:.2f} to={self._shape:.2f}"
                f" bars={t},{b},{left},{r} age_s={ts - self._changed_ts:.1f}",
            )
        return self._vote(ts, 0.0, f"aspect_same shape={self._shape:.2f}")

    def describe(self) -> str:
        base = self.baseline
        if base is None:
            return f"learning the picture shape ({self._seen_s:.0f} s seen)"
        now = f"{self._shape:.2f}" if self._shape is not None else "?"
        return f"programme {base:.2f}, now {now}"
