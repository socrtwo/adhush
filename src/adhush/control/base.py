"""MuteController ABC: mute(), unmute(), state(), supports_discrete().

Controllers never inspect frames. They declare whether they can issue discrete
mute-on/off (vs toggle only) and whether they can read the device's mute state
back; fusion-side audio verification compensates where they cannot.
"""

from __future__ import annotations

from abc import ABC, abstractmethod


class ControlError(RuntimeError):
    """Raised when a control backend cannot reach or drive the device."""


class MuteController(ABC):
    @abstractmethod
    def mute(self) -> None:
        """Drive the device to muted. Idempotent where the backend allows."""

    @abstractmethod
    def unmute(self) -> None:
        """Drive the device to unmuted. Idempotent where the backend allows."""

    def state(self) -> bool | None:
        """Device mute state if the backend can read it back, else None."""
        return None

    @abstractmethod
    def supports_discrete(self) -> bool:
        """True if mute()/unmute() are discrete commands rather than a toggle."""

    def close(self) -> None:
        """Release any device handle. Safe to call twice."""
