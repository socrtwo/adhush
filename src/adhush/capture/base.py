"""CaptureSource ABC: open(), frames(), audio_blocks(), close(), caps().

Every backend produces the same normalized streams — FrameEvent (uint8 luma or
BGR) and AudioEvent (mono float32) — so detectors never care where pixels came
from. ``caps()`` declares which modalities exist so the engine can drop
detectors whose modality is missing.
"""

from __future__ import annotations

from abc import ABC, abstractmethod
from collections.abc import Iterator
from dataclasses import dataclass
from typing import Self

from adhush.events import AudioEvent, FrameEvent


class CaptureError(RuntimeError):
    """Raised when a capture backend cannot open or read its source."""


@dataclass(frozen=True, slots=True)
class CaptureCaps:
    video: bool
    audio: bool
    width: int = 0
    height: int = 0
    fps: float = 0.0
    sample_rate: int = 0
    realtime: bool = True  # False for file_replay: timestamps are media time


class CaptureSource(ABC):
    @abstractmethod
    def open(self) -> None:
        """Acquire the device or file. Must be called before iteration."""

    @abstractmethod
    def frames(self) -> Iterator[FrameEvent]:
        """Yield frames in timestamp order; empty iterator if no video."""

    @abstractmethod
    def audio_blocks(self) -> Iterator[AudioEvent]:
        """Yield audio blocks in timestamp order; empty iterator if no audio."""

    @abstractmethod
    def close(self) -> None:
        """Release the source. Safe to call twice."""

    @abstractmethod
    def caps(self) -> CaptureCaps:
        """Static capabilities; valid after open()."""

    def __enter__(self) -> Self:
        self.open()
        return self

    def __exit__(self, *exc: object) -> None:
        self.close()
