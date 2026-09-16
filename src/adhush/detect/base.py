"""Detector ABC: warmup(), observe(frame|audio), vote() -> DetectorVote(confidence, reason).

Detectors are independent plugins. Each owns only its rolling baselines, never
calls a controller, and returns a confidence in [0, 1] with a machine-readable
reason string (space-separated ``tag key=value ...``).
"""

from __future__ import annotations

from abc import ABC, abstractmethod
from typing import ClassVar

from adhush.events import AudioEvent, CueEvent, DetectorVote, FrameEvent


class Detector(ABC):
    """Base class for all detectors.

    ``needs_video`` / ``needs_audio`` let the engine disable a detector when
    the capture source lacks a modality (audio-only mode drops every video
    detector automatically, per docs/architecture.md).
    """

    name: ClassVar[str]
    needs_video: ClassVar[bool] = False
    needs_audio: ClassVar[bool] = False
    # Frames are welcome but not required: the detector still runs on an
    # audio-only source (ad_units takes its separators from either).
    wants_video: ClassVar[bool] = False

    @property
    def voting(self) -> bool:
        """False while the detector cannot see what it needs (a camera with no
        whole screen in view, a logo never yet sighted). An inert detector
        casts no vote and stays out of the fusion normalizer: it neither adds
        evidence nor dilutes it. Audio detectors are always voting."""
        return True

    def user_says_program(self, ts: float) -> None:
        """"Not an ad" / "Show's back": whatever this detector was sure of a
        moment ago was the program. Detectors that hold a belief drop it."""

    def audio_ducked(self, ts: float, ducked: bool) -> None:
        """The engine turned the set down (or back up) at ``ts``, and the
        audio this detector hears comes from a microphone in the room. A
        detector that judges levels compensates or falls silent (ADR 0016);
        the engine never calls this for a line tap, which hears the broadcast
        whatever the set does."""

    def warmup(self) -> None:
        """Reset rolling state before a capture session starts."""

    def observe_frame(self, event: FrameEvent) -> None:
        """Consume one shared decoded frame. Must not mutate or retain it."""

    def observe_audio(self, event: AudioEvent) -> None:
        """Consume one block of mono float32 audio."""

    def observe_cue(self, event: CueEvent) -> None:
        """Consume an out-of-band marker from the capture path (ADR 0024)."""

    def tick(self, wall: float) -> None:
        """Wall-clock time passes (the break clock, a schedule, a shared feed).
        Never called during a replay, which has no wall clock."""

    @property
    def program_present(self) -> bool:
        """True while this detector has *positive* evidence the programme is
        on (the logo back, the closing sting, a rating box, a title card): the
        engine may end a fingerprint or user hold early on it. Absence of ad
        evidence is not presence of the programme; most detectors say False."""
        return False

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
