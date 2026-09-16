"""ATSC A/335 / DVB-TA video watermark presence (ADR 0023). Experimental.

ATSC 3.0 (A/335) and DVB-TA carry a data watermark in the luma of the top
one or two lines of the picture: the line is divided into symbols eight
pixels wide (240 across a 1920-pixel frame), each held high or low, and a
frame's payload opens with a fixed run-in pattern. Set-top boxes and HDMI
pass it through untouched because it *is* picture; A/336 and HbbTV-TA use
it to tell a TV behind a box where the breaks are.

AdHush does not decode the A/336 messages — that needs the broadcaster's
recovery server. It uses the one thing the mark gives away for free:
**presence**. A network feed that carries the mark loses it the moment a
locally inserted spot replaces the picture, and the line reverts to
ordinary video. So the detector reads the top ``lines`` of every sampled
frame, decides whether they hold a symbol-aligned two-level pattern (the
run-in seen, or a bimodal line whose transitions sit on symbol boundaries),
keeps a baseline of "present" over ``baseline_s``, and votes 1.0 while the
mark has been *absent* for ``confirm_s`` on a channel where it is normally
present. Inert on a channel with no mark — most cable channels today — and
inert while the mark is where it should be. The last payload's bits are
kept for ``describe`` so an owner can see what the channel emits.

Symbol geometry, run-in and thresholds are configurable because this was
written from the spec's published shape, not verified against a live
ATSC 3.0 feed; treat it as a probe until it has been.
"""

from __future__ import annotations

from collections import deque
from itertools import pairwise
from typing import ClassVar

import numpy as np

from adhush.config import WatermarkConfig
from adhush.detect.base import Detector
from adhush.events import DetectorVote, FrameEvent
from adhush.util.imageops import to_luma


def read_symbols(line: np.ndarray, symbols: int) -> tuple[list[int], float]:
    """Slice one luma line into ``symbols`` equal cells and threshold each at
    the line's midpoint. Returns (bits, contrast) where contrast is the
    spread between the two levels in luma units (0 for a flat line)."""
    cells = np.array_split(line.astype(np.float64), symbols)
    means = np.array([c.mean() for c in cells])
    lo, hi = float(np.percentile(means, 10)), float(np.percentile(means, 90))
    contrast = hi - lo
    if contrast <= 0.0:
        return [0] * symbols, 0.0
    mid = (lo + hi) / 2.0
    return [int(m >= mid) for m in means], contrast


def alignment(line: np.ndarray, symbols: int) -> float:
    """How well the line's transitions sit on symbol boundaries: the share of
    large luma steps that fall within one pixel of a boundary. ~1 for a
    watermark, ~symbol_width/width for ordinary picture."""
    n = len(line)
    diff = np.abs(np.diff(line.astype(np.float64)))
    if diff.size == 0:
        return 0.0
    big = np.flatnonzero(diff >= max(8.0, diff.max() * 0.5))
    if len(big) == 0:
        return 0.0
    cell = n / symbols
    on = 0
    for i in big:
        pos = (i + 1) % cell
        if pos <= 1.0 or pos >= cell - 1.0:
            on += 1
    return on / len(big)


class WatermarkDetector(Detector):
    name: ClassVar[str] = "watermark"
    needs_video: ClassVar[bool] = True

    def __init__(self, config: WatermarkConfig) -> None:
        self._cfg = config
        self.warmup()

    def warmup(self) -> None:
        self._next_sample_ts = -np.inf
        self._history: deque[tuple[float, bool]] = deque()
        self._seen_s = 0.0
        self._present = False
        self._absent_since: float | None = None
        self._changed_ts: float | None = None
        self._last_bits: list[int] = []
        self._last_contrast = 0.0
        self._last_alignment = 0.0
        self._run_in_seen = 0

    def _run_in(self) -> list[int]:
        bits = []
        for i in range(self._cfg.run_in_bits - 1, -1, -1):
            bits.append((self._cfg.run_in >> i) & 1)
        return bits

    def measure(self, frame: np.ndarray) -> bool:
        """True when the top lines look like a symbol-aligned two-level mark."""
        luma = to_luma(frame)
        lines = luma[: self._cfg.lines]
        line = lines.mean(axis=0) if lines.shape[0] > 1 else lines[0]
        symbols = max(16, round(luma.shape[1] / self._cfg.symbol_px))
        bits, contrast = read_symbols(line, symbols)
        self._last_bits = bits
        self._last_contrast = contrast
        self._last_alignment = alignment(line, symbols)
        if contrast < self._cfg.min_contrast:
            return False
        run_in = self._run_in()
        if bits[: len(run_in)] == run_in:
            self._run_in_seen += 1
            return True
        flips = sum(1 for a, b in pairwise(bits) if a != b)
        return self._last_alignment >= self._cfg.min_alignment and flips >= symbols // 8

    @property
    def baseline_present(self) -> bool | None:
        if self._seen_s < self._cfg.min_baseline_s or not self._history:
            return None
        present = sum(1 for _, p in self._history if p)
        return present * 2 >= len(self._history)

    def observe_frame(self, event: FrameEvent) -> None:
        if event.ts < self._next_sample_ts:
            return
        self._next_sample_ts = event.ts + self._cfg.sample_interval_s
        self._present = self.measure(event.frame)
        base = self.baseline_present
        if self._changed_ts is None:
            if base is True and not self._present:
                if self._absent_since is None:
                    self._absent_since = event.ts
                elif event.ts - self._absent_since >= self._cfg.confirm_s:
                    self._changed_ts = self._absent_since
                return
            self._absent_since = None
            self._history.append((event.ts, self._present))
            self._seen_s += self._cfg.sample_interval_s
            while self._history and event.ts - self._history[0][0] > self._cfg.baseline_s:
                self._history.popleft()
        elif self._present:
            self._changed_ts = None
            self._absent_since = None
        elif event.ts - self._changed_ts >= self._cfg.max_absent_s:
            # Gone longer than any break: the channel stopped marking.
            self._history.clear()
            self._history.append((event.ts, False))
            self._changed_ts = None
            self._absent_since = None

    @property
    def voting(self) -> bool:
        return self._changed_ts is not None

    def vote(self, ts: float) -> DetectorVote:
        if self._changed_ts is not None:
            return self._vote(ts, 1.0, f"watermark_absent age_s={ts - self._changed_ts:.1f} contrast={self._last_contrast:.0f}")
        state = "present" if self._present else "absent"
        return self._vote(ts, 0.0, f"watermark_{state} align={self._last_alignment:.2f} contrast={self._last_contrast:.0f}")

    def describe(self) -> str:
        base = self.baseline_present
        if base is None:
            return f"probing ({self._seen_s:.0f} s seen)"
        payload = "".join(str(b) for b in self._last_bits[:32])
        return ("mark normally present" if base else "no mark on this channel") + (
            f"; run-in seen {self._run_in_seen}×; last bits {payload}…" if self._present else ""
        )
