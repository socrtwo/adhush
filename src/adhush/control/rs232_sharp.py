"""Sharp AQUOS RS-232C serial control: discrete MUTE on/off/toggle, verified state readback.

Protocol (AQUOS RS-232C command table, LE830U generation): each command is a
4-character mnemonic plus a 4-character parameter, space-padded, terminated by
CR. The set answers ``OK`` or ``ERR``, and answers value queries (parameter
``?``) with the current value. ``MUTE`` parameters: 0 toggle, 1 on, 2 off.

The serial link itself is injectable so the framing is testable without
hardware; the default transport uses pyserial, imported lazily so the core
install stays dependency-light.
"""

from __future__ import annotations

from typing import Any, Protocol

from adhush.control.base import ControlError, MuteController

_TERMINATOR = b"\r"
_MUTE_ON = "1"
_MUTE_OFF = "2"
_QUERY = "?"


class SerialLink(Protocol):
    def write(self, data: bytes) -> None: ...

    def read_line(self) -> bytes:
        """Read up to and including the next CR."""
        ...

    def close(self) -> None: ...


class _PyserialLink:
    def __init__(self, port: str, baud: int, timeout_s: float) -> None:
        try:
            import serial  # type: ignore[import-untyped]
        except ImportError as exc:
            raise ControlError(
                "rs232_sharp requires pyserial (pip install adhush[pi])"
            ) from exc
        try:
            self._serial = serial.Serial(port=port, baudrate=baud, timeout=timeout_s)
        except serial.SerialException as exc:
            raise ControlError(f"cannot open serial port {port}: {exc}") from exc

    def write(self, data: bytes) -> None:
        self._serial.write(data)

    def read_line(self) -> bytes:
        return bytes(self._serial.read_until(_TERMINATOR))

    def close(self) -> None:
        self._serial.close()


def frame_command(command: str, parameter: str) -> bytes:
    """Frame one AQUOS command: 4-char mnemonic + 4-char parameter + CR."""
    if not 1 <= len(command) <= 4 or not command.isascii():
        raise ValueError(f"bad command mnemonic: {command!r}")
    if len(parameter) > 4 or not parameter.isascii():
        raise ValueError(f"bad parameter: {parameter!r}")
    return f"{command:<4}{parameter:<4}".encode("ascii") + _TERMINATOR


class SharpRs232Controller(MuteController):
    def __init__(self, options: dict[str, Any], link: SerialLink | None = None) -> None:
        self._link: SerialLink | None
        if link is not None:
            self._link = link
        else:
            self._link = _PyserialLink(
                port=str(options.get("port", "/dev/ttyUSB0")),
                baud=int(options.get("baud", 9600)),
                timeout_s=float(options.get("timeout_s", 1.0)),
            )

    def _exchange(self, command: str, parameter: str) -> str:
        if self._link is None:
            raise ControlError("controller is closed")
        self._link.write(frame_command(command, parameter))
        reply = self._link.read_line().strip().decode("ascii", errors="replace")
        if reply == "ERR" or not reply:
            raise ControlError(f"device rejected {command} {parameter}: {reply or 'timeout'}")
        return reply

    def mute(self) -> None:
        self._exchange("MUTE", _MUTE_ON)

    def unmute(self) -> None:
        self._exchange("MUTE", _MUTE_OFF)

    def state(self) -> bool | None:
        try:
            reply = self._exchange("MUTE", _QUERY)
        except ControlError:
            return None
        if reply == _MUTE_ON:
            return True
        if reply == _MUTE_OFF:
            return False
        return None

    def supports_discrete(self) -> bool:
        return True

    def close(self) -> None:
        if self._link is not None:
            self._link.close()
            self._link = None
