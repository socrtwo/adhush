"""Passthrough-box audio interception for HDMI splitter/extractor topologies.

The Phase 5 box sits inline: source → HDMI splitter → TV, with the second
splitter output feeding the capture dongle and an audio extractor feeding the
TV/amp through a relay this controller drives on a GPIO pin. Muting opens the
relay — physically cutting the audio path — so it is discrete, instant, works
on any TV ever made, and cannot desynchronize; ``state()`` reports the
commanded pin state, which *is* the physical path state for a relay.

Safety posture: wire the relay so its resting (de-energized) contact passes
audio, and this controller energizes it only to mute. Then a crash, power
loss, or ``close()`` always fails **unmuted** — a stuck mute is the defect
AdHush must never ship (docs/architecture.md).

The pin driver is injectable; the default uses the pigpio daemon, imported
lazily like every other Pi-only dependency. See
docs/hardware-passthrough-box.md for wiring and parts.
"""

from __future__ import annotations

from typing import Any, Protocol

from adhush.control.base import ControlError, MuteController


class GpioPin(Protocol):
    """Minimal output-pin contract; True = drive high."""

    def write(self, level: bool) -> None: ...

    def close(self) -> None: ...


class _PigpioPin:
    def __init__(self, gpio: int) -> None:
        try:
            import pigpio  # type: ignore[import-not-found]
        except ImportError as exc:
            raise ControlError("relay_hdmi requires the pigpio package") from exc
        self._pi = pigpio.pi()
        if not self._pi.connected:
            raise ControlError("pigpiod not running (sudo systemctl start pigpiod)")
        self._gpio = gpio
        self._pi.set_mode(gpio, pigpio.OUTPUT)

    def write(self, level: bool) -> None:
        self._pi.write(self._gpio, 1 if level else 0)

    def close(self) -> None:
        self._pi.stop()


class RelayHdmiController(MuteController):
    def __init__(self, options: dict[str, Any], pin: GpioPin | None = None) -> None:
        self._gpio = int(options.get("gpio", 23))
        # active_high=True: driving the pin high energizes the relay = mute.
        self._active_high = bool(options.get("active_high", True))
        self._pin = pin if pin is not None else _PigpioPin(self._gpio)
        self._muted = False
        self._pin.write(self._level(False))  # start passing audio

    def _level(self, muted: bool) -> bool:
        return muted if self._active_high else not muted

    def mute(self) -> None:
        self._pin.write(self._level(True))
        self._muted = True

    def unmute(self) -> None:
        self._pin.write(self._level(False))
        self._muted = False

    def state(self) -> bool | None:
        return self._muted

    def supports_discrete(self) -> bool:
        return True

    def close(self) -> None:
        # Fail unmuted: release the relay before letting go of the pin.
        try:
            self._pin.write(self._level(False))
            self._muted = False
        finally:
            self._pin.close()
