"""Monotonic clocks, dwell timers, slot snapping (15/30/45/60s)."""

from __future__ import annotations

import time
from collections.abc import Callable, Sequence

Clock = Callable[[], float]


def monotonic_clock() -> float:
    """Default wall-independent clock for live capture paths."""
    return time.monotonic()


class DwellTimer:
    """Tracks how long a boolean condition has held continuously.

    Fusion and the state machine use these to require that a condition dwell
    for a minimum time before acting on it, which is what keeps one glitchy
    frame from muting the set.
    """

    def __init__(self, dwell_s: float) -> None:
        if dwell_s < 0:
            raise ValueError("dwell_s must be >= 0")
        self.dwell_s = dwell_s
        self._since: float | None = None

    def update(self, condition: bool, now: float) -> bool:
        """Feed the current condition; returns True once it has dwelled."""
        if not condition:
            self._since = None
            return False
        if self._since is None:
            self._since = now
        return (now - self._since) >= self.dwell_s

    def reset(self) -> None:
        self._since = None

    @property
    def active_since(self) -> float | None:
        return self._since


def snap_to_slot(duration_s: float, slots: Sequence[float]) -> float:
    """Snap an observed ad duration to the nearest standard slot.

    The snap is a prior, not a guarantee; callers decide when the learned
    duration should win instead (see docs/detection-strategies.md).
    """
    if not slots:
        return duration_s
    return min(slots, key=lambda s: abs(s - duration_s))
