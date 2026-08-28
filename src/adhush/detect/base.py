"""Detector ABC: warmup(), observe(frame|audio), vote() -> DetectorVote(confidence, reason).

Detectors are independent plugins. Each owns only its rolling baselines, never
calls a controller, and returns a confidence in [0, 1] with a machine-readable
reason string (space-separated ``tag key=value ...``).
"""

from __future__ import annotations

from abc import ABC, abstractmethod
from typing import ClassVar

from adhush.events import AudioEvent, DetectorVote, FrameEvent


class Detector(ABC):
    """Base class for all detectors.

    ``needs_video`` / ``needs_audio`` let the engine disable a detector when
    the capture source lacks a modality (audio-only mode drops every video
    detector automatically, per docs/architecture.md).
    """

    name: ClassVar[str]
    needs_video: ClassVar[bool] = False
    needs_audio: ClassVar[bool] = False

    def warmup(self) -> None:
        """Reset rolling state before a capture session starts."""

    def observe_frame(self, event: FrameEvent) -> None:
        """Consume one shared decoded frame. Must not mutate or retain it."""

    def observe_audio(self, event: AudioEvent) -> None:
        """Consume one block of mono float32 audio."""

    @abstractmethod
    def vote(self, ts: float) -> DetectorVote:
        """Current opinion at media time ``ts``: 0 = program, 1 = ad."""

    def _vote(self, ts: float, confidence: float, reason: str) -> DetectorVote:
        return DetectorVote(
            detector=self.name,
            ts=ts,
            confidence=max(0.0, min(1.0, confidence)),
            reason=reason,
        )
