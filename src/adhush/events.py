"""Event dataclasses: FrameEvent, AudioEvent, DetectorVote, MuteDecision, AdSegment."""

from __future__ import annotations

from dataclasses import dataclass, field

import numpy as np
import numpy.typing as npt


@dataclass(frozen=True, slots=True)
class FrameEvent:
    """One decoded video frame on the shared bus.

    ``frame`` is uint8, either (H, W) luma or (H, W, 3) BGR. Detectors must
    treat it as read-only; no detector re-decodes or copies at full resolution.
    ``ts`` is media time in seconds, monotonic within a capture session.
    """

    ts: float
    frame: npt.NDArray[np.uint8]

    @property
    def height(self) -> int:
        return int(self.frame.shape[0])

    @property
    def width(self) -> int:
        return int(self.frame.shape[1])

    @property
    def is_color(self) -> bool:
        return self.frame.ndim == 3


@dataclass(frozen=True, slots=True)
class AudioEvent:
    """One block of mono audio samples, float32 in [-1, 1].

    ``ts`` is the media time of the first sample in the block.
    """

    ts: float
    samples: npt.NDArray[np.float32]
    sample_rate: int

    @property
    def duration(self) -> float:
        return len(self.samples) / self.sample_rate


@dataclass(frozen=True, slots=True)
class DetectorVote:
    """A single detector's opinion at a moment in time.

    ``confidence`` is in [0, 1]: 0 means "this is program", 1 means "this is
    an ad". ``reason`` is machine-readable: space-separated ``key=value``
    tokens prefixed with a stable tag, e.g. ``black_run frames=12 luma=7.3``.
    """

    detector: str
    ts: float
    confidence: float
    reason: str

    def __post_init__(self) -> None:
        if not 0.0 <= self.confidence <= 1.0:
            raise ValueError(f"confidence {self.confidence} outside [0, 1]")


@dataclass(frozen=True, slots=True)
class MuteDecision:
    """Fusion output: what the mute state should be right now and why."""

    ts: float
    mute: bool
    confidence: float
    reasons: tuple[str, ...] = field(default=())


@dataclass(frozen=True, slots=True)
class AdSegment:
    """A confirmed ad span, as observed or as learned from fingerprints."""

    start_ts: float
    duration_s: float
    source: str  # "fusion" | "fingerprint"
