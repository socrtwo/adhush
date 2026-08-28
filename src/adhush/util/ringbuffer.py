"""Fixed-capacity A/V ring buffers sized for a 2 GB Pi 4.

Capacity always comes from configuration (seconds of history times the
configured rate); nothing here hardcodes sizes.
"""

from __future__ import annotations

from collections import deque
from typing import Generic, TypeVar

T = TypeVar("T")


class RingBuffer(Generic[T]):
    """Bounded FIFO that silently drops the oldest item when full."""

    def __init__(self, capacity: int) -> None:
        if capacity < 1:
            raise ValueError("capacity must be >= 1")
        self._items: deque[T] = deque(maxlen=capacity)

    @classmethod
    def for_duration(cls, seconds: float, rate_hz: float) -> RingBuffer[T]:
        """Size a buffer to hold ``seconds`` of items arriving at ``rate_hz``."""
        return cls(max(1, round(seconds * rate_hz)))

    def push(self, item: T) -> None:
        self._items.append(item)

    def __len__(self) -> int:
        return len(self._items)

    def __iter__(self):  # type: ignore[no-untyped-def]
        return iter(self._items)

    @property
    def capacity(self) -> int:
        assert self._items.maxlen is not None
        return self._items.maxlen

    @property
    def full(self) -> bool:
        return len(self._items) == self.capacity

    def latest(self) -> T:
        if not self._items:
            raise IndexError("ring buffer is empty")
        return self._items[-1]

    def clear(self) -> None:
        self._items.clear()
