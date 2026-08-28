"""Direct pigpio waveform IR TX as a LIRC-free fallback; raw NEC/RC-5/Sharp timing.

Encodes named protocols (nec, nec_ext, samsung, sharp, sirc, rc5) or raw
pulse/space arrays into mark/space microsecond pairs and transmits them as a
carrier-modulated pigpio waveform on a GPIO pin. Codes come from the device
profile: either ``{protocol, address, command}`` or ``{protocol = "raw",
timing = [...], carrier_hz = ...}`` (docs/device-support.md). Discrete mute
is claimed only when distinct on/off codes exist.

The encoders are pure functions and the transmitter is injectable, so timing
is testable without hardware; pigpio itself is imported lazily.
"""

from __future__ import annotations

from collections.abc import Callable, Sequence
from typing import Any

from adhush.control.base import ControlError, MuteController

# A pulse train: (mark_us, space_us) pairs, in order.
Pulses = list[tuple[int, int]]
# Sends one pulse train: (gpio, carrier_hz, duty, pulses).
Transmitter = Callable[[int, int, float, Pulses], None]

_NEC_HDR = (9000, 4500)
_NEC_BIT_MARK = 562
_NEC_SPACE_0 = 562
_NEC_SPACE_1 = 1687
_SAMSUNG_HDR = (4500, 4500)
_SHARP_MARK = 320
_SHARP_SPACE_0 = 680
_SHARP_SPACE_1 = 1680
_SHARP_FRAME_GAP_US = 40000
_SIRC_HDR = (2400, 600)
_SIRC_MARK_0 = 600
_SIRC_MARK_1 = 1200
_SIRC_SPACE = 600
_RC5_HALF = 889

DEFAULT_CARRIER_HZ = {"sirc": 40000, "rc5": 36000}


def _lsb_bits(value: int, count: int) -> list[int]:
    return [(value >> i) & 1 for i in range(count)]


def _pulses_nec_style(header: tuple[int, int], bits: Sequence[int]) -> Pulses:
    pulses: Pulses = [header]
    for bit in bits:
        pulses.append((_NEC_BIT_MARK, _NEC_SPACE_1 if bit else _NEC_SPACE_0))
    pulses.append((_NEC_BIT_MARK, 0))  # trailer mark
    return pulses


def encode_nec(address: int, command: int) -> Pulses:
    bits = (
        _lsb_bits(address, 8)
        + _lsb_bits(~address & 0xFF, 8)
        + _lsb_bits(command, 8)
        + _lsb_bits(~command & 0xFF, 8)
    )
    return _pulses_nec_style(_NEC_HDR, bits)


def encode_nec_ext(address: int, command: int) -> Pulses:
    """Extended NEC: 16-bit address replaces the address/~address pair."""
    bits = _lsb_bits(address, 16) + _lsb_bits(command, 8) + _lsb_bits(~command & 0xFF, 8)
    return _pulses_nec_style(_NEC_HDR, bits)


def encode_samsung(address: int, command: int) -> Pulses:
    """Samsung TV variant: NEC bit timing, 4.5/4.5 ms header, address twice."""
    bits = (
        _lsb_bits(address, 8)
        + _lsb_bits(address, 8)
        + _lsb_bits(command, 8)
        + _lsb_bits(~command & 0xFF, 8)
    )
    return _pulses_nec_style(_SAMSUNG_HDR, bits)


def encode_sharp(address: int, command: int) -> Pulses:
    """Sharp 15-bit: each frame sent normal then with cmd/exp/chk inverted."""

    def frame(cmd: int, expansion: int, check: int) -> Pulses:
        bits = _lsb_bits(address, 5) + _lsb_bits(cmd, 8) + [expansion, check]
        pulses: Pulses = []
        for bit in bits:
            pulses.append((_SHARP_MARK, _SHARP_SPACE_1 if bit else _SHARP_SPACE_0))
        pulses.append((_SHARP_MARK, _SHARP_FRAME_GAP_US))
        return pulses

    first = frame(command, 1, 0)
    second = frame(~command & 0xFF, 0, 1)
    second[-1] = (_SHARP_MARK, 0)
    return first + second


def encode_sirc(address: int, command: int, bits: int = 12) -> Pulses:
    """Sony SIRC: 7-bit command then 5/8/13-bit address, 40 kHz carrier."""
    if bits not in (12, 15, 20):
        raise ValueError(f"sirc bits must be 12, 15, or 20, not {bits}")
    payload = _lsb_bits(command, 7) + _lsb_bits(address, bits - 7)
    pulses: Pulses = [_SIRC_HDR]
    for bit in payload:
        pulses.append((_SIRC_MARK_1 if bit else _SIRC_MARK_0, _SIRC_SPACE))
    return pulses


def encode_rc5(address: int, command: int, toggle: int = 0) -> Pulses:
    """Philips RC-5 bi-phase: start bits, toggle, 5-bit address, 6-bit command."""
    bits = [1, 1, toggle & 1]
    bits += [(address >> i) & 1 for i in range(4, -1, -1)]
    bits += [(command >> i) & 1 for i in range(5, -1, -1)]
    # Bi-phase: 1 = space then mark, 0 = mark then space, 889 us halves.
    halves: list[int] = []  # 1 = carrier on, 0 = off
    for bit in bits:
        halves += [0, 1] if bit else [1, 0]
    while halves and halves[0] == 0:  # transmission begins at the first mark
        halves.pop(0)
    pulses: Pulses = []
    mark_us = 0
    space_us = 0
    for level in halves:
        if level:
            if space_us:
                pulses.append((mark_us, space_us))
                mark_us = space_us = 0
            mark_us += _RC5_HALF
        else:
            space_us += _RC5_HALF
    pulses.append((mark_us, space_us))
    return pulses


def encode_raw(timing: Sequence[int]) -> Pulses:
    """Raw pulse/space playback: alternating mark/space microseconds."""
    if not timing or any(int(t) <= 0 for t in timing):
        raise ValueError("raw timing must be positive microseconds")
    padded = list(timing) + ([0] if len(timing) % 2 else [])
    return [(int(padded[i]), int(padded[i + 1])) for i in range(0, len(padded), 2)]


_ENCODERS = {
    "nec": encode_nec,
    "nec_ext": encode_nec_ext,
    "samsung": encode_samsung,
    "sharp": encode_sharp,
    "rc5": encode_rc5,
}


def encode_code(code: dict[str, Any], default_protocol: str) -> tuple[Pulses, int]:
    """Encode one profile code entry; returns (pulses, carrier_hz)."""
    protocol = str(code.get("protocol", default_protocol)).lower()
    carrier = int(code.get("carrier_hz", DEFAULT_CARRIER_HZ.get(protocol, 38000)))
    if protocol == "raw":
        return encode_raw(list(code["timing"])), carrier
    if protocol == "sirc":
        return (
            encode_sirc(
                int(code["address"]), int(code["command"]), int(code.get("bits", 12))
            ),
            carrier,
        )
    encoder = _ENCODERS.get(protocol)
    if encoder is None:
        raise ControlError(
            f"unsupported IR protocol '{protocol}'; use raw timing for this remote"
        )
    return encoder(int(code["address"]), int(code["command"])), carrier


def _pigpio_transmit(gpio: int, carrier_hz: int, duty: float, pulses: Pulses) -> None:
    try:
        import pigpio  # type: ignore[import-not-found]
    except ImportError as exc:
        raise ControlError("ir_pigpio requires the pigpio package") from exc
    pi = pigpio.pi()
    if not pi.connected:
        raise ControlError("pigpiod not running (sudo systemctl start pigpiod)")
    try:
        pi.set_mode(gpio, pigpio.OUTPUT)
        period_us = 1_000_000 / carrier_hz
        on_us = period_us * duty
        wave: list[Any] = []
        for mark_us, space_us in pulses:
            cycles = max(1, round(mark_us / period_us))
            for _ in range(cycles):
                wave.append(pigpio.pulse(1 << gpio, 0, round(on_us)))
                wave.append(pigpio.pulse(0, 1 << gpio, round(period_us - on_us)))
            if space_us:
                wave.append(pigpio.pulse(0, 1 << gpio, space_us))
        pi.wave_clear()
        pi.wave_add_generic(wave)
        wave_id = pi.wave_create()
        try:
            pi.wave_send_once(wave_id)
            while pi.wave_tx_busy():
                pass
        finally:
            pi.wave_delete(wave_id)
    finally:
        pi.stop()


class IrPigpioController(MuteController):
    def __init__(
        self, options: dict[str, Any], transmitter: Transmitter | None = None
    ) -> None:
        self._gpio = int(options.get("gpio", 18))
        self._duty = float(options.get("duty", 0.33))
        self._protocol = str(options.get("protocol", "nec"))
        self._repeat = max(1, int(options.get("repeat", 1)))
        codes = options.get("codes", {})
        self._code_on = codes.get("mute_on")
        self._code_off = codes.get("mute_off")
        self._code_toggle = codes.get("mute_toggle")
        if not ((self._code_on and self._code_off) or self._code_toggle):
            raise ControlError("ir_pigpio needs codes.mute_on+mute_off, or codes.mute_toggle")
        self._transmit = transmitter if transmitter is not None else _pigpio_transmit

    def _send(self, code: dict[str, Any]) -> None:
        pulses, carrier = encode_code(dict(code), self._protocol)
        for _ in range(self._repeat):
            self._transmit(self._gpio, carrier, self._duty, pulses)

    def mute(self) -> None:
        self._send(self._code_on if self._code_on else self._code_toggle)

    def unmute(self) -> None:
        self._send(self._code_off if self._code_off else self._code_toggle)

    def supports_discrete(self) -> bool:
        return bool(self._code_on and self._code_off)
