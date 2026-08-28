"""Mute controller registry."""

from __future__ import annotations

from adhush.config import ControlConfig
from adhush.control.base import ControlError, MuteController
from adhush.control.ir_lirc import IrLircController
from adhush.control.rs232_sharp import SharpRs232Controller

__all__ = ["ControlError", "MuteController", "build_controller"]


class NullController(MuteController):
    """Records actions instead of driving hardware; replay and doctor use it."""

    def __init__(self) -> None:
        self.actions: list[tuple[float | None, str]] = []

    def mute(self) -> None:
        self.actions.append((None, "mute"))

    def unmute(self) -> None:
        self.actions.append((None, "unmute"))

    def supports_discrete(self) -> bool:
        return True


def build_controller(config: ControlConfig) -> MuteController:
    """Instantiate the configured backend. Later phases extend this table."""
    if config.backend == "rs232_sharp":
        return SharpRs232Controller(config.options)
    if config.backend == "ir_lirc":
        return IrLircController(config.options)
    raise ControlError(f"control backend '{config.backend}' is not implemented yet (see roadmap)")
