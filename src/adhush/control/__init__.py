"""Mute controller registry."""

from __future__ import annotations

from typing import Any

from adhush.config import ControlConfig, Profile
from adhush.control.base import ControlError, MuteController
from adhush.control.cec import CecController
from adhush.control.ir_blaster_net import IrBlasterNetController
from adhush.control.ir_lirc import IrLircController
from adhush.control.ir_pigpio import IrPigpioController
from adhush.control.local_audio import LocalAudioController
from adhush.control.network_ip import NetworkIpController
from adhush.control.relay_hdmi import RelayHdmiController
from adhush.control.rs232_sharp import SharpRs232Controller

__all__ = [
    "IMPLEMENTED_BACKENDS",
    "ControlError",
    "MuteController",
    "NullController",
    "build_controller",
    "resolve_options",
]

IMPLEMENTED_BACKENDS = (
    "rs232_sharp",
    "ir_lirc",
    "ir_pigpio",
    "cec",
    "ir_blaster_net",
    "network_ip",
    "local_audio",
    "relay_hdmi",
)
_IR_BACKENDS = ("ir_lirc", "ir_pigpio", "ir_blaster_net")


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


def _deep_merge(base: dict[str, Any], override: dict[str, Any]) -> dict[str, Any]:
    merged = dict(base)
    for key, value in override.items():
        if isinstance(value, dict) and isinstance(merged.get(key), dict):
            merged[key] = _deep_merge(merged[key], value)
        else:
            merged[key] = value
    return merged


def resolve_options(
    config: ControlConfig, profile: Profile | None, backend: str
) -> dict[str, Any]:
    """Backend options: profile-supplied settings under main-config overrides.

    Device specifics live in profiles (CLAUDE.md). IR backends additionally
    inherit the profile's shared ``[ir]`` section (protocol, carrier, codes),
    which any ``[<backend>]`` profile section or ``[control.<backend>]`` in
    the main config can override.
    """
    options: dict[str, Any] = {}
    if profile is not None:
        if backend in _IR_BACKENDS:
            options = _deep_merge(options, dict(profile.raw.get("ir", {})))
        options = _deep_merge(options, dict(profile.raw.get(backend, {})))
    explicit = config.sections.get(backend)
    if explicit is None:
        explicit = config.options if config.backend == backend else {}
    return _deep_merge(options, dict(explicit))


def build_controller(config: ControlConfig, profile: Profile | None = None) -> MuteController:
    """Instantiate the configured backend with profile-resolved options."""
    options = resolve_options(config, profile, config.backend)
    if config.backend == "rs232_sharp":
        return SharpRs232Controller(options)
    if config.backend == "ir_lirc":
        return IrLircController(options)
    if config.backend == "ir_pigpio":
        return IrPigpioController(options)
    if config.backend == "cec":
        return CecController(options)
    if config.backend == "ir_blaster_net":
        return IrBlasterNetController(options)
    if config.backend == "network_ip":
        return NetworkIpController(options)
    if config.backend == "local_audio":
        return LocalAudioController(options)
    if config.backend == "relay_hdmi":
        return RelayHdmiController(options)
    raise ControlError(f"control backend '{config.backend}' is not implemented yet (see roadmap)")
