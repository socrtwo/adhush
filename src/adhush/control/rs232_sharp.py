"""Sharp AQUOS RS-232C serial control: discrete MUTE on/off/toggle, verified state readback.

Protocol (AQUOS RS-232C command table, LE830U generation): each command is a
4-character mnemonic plus a 4-character parameter, space-padded, terminated by
CR. The set answers ``OK`` or ``ERR``, and answers value queries (parameter
``?``) with the current value. ``MUTE`` parameters: 0 toggle, 1 on, 2 off.

The serial link itself is injectable so the framing is testable without
hardware; the default transport uses pyserial, imported lazily so the core
install stays dependency-light.

**Ducking.** With ``duck_level`` set, ``mute()`` turns the set *down* to that
level (``VOLM``, 0-60) and ``unmute()`` puts the volume back where it was, so
a microphone keeps hearing the set through the break — the listener's way of
knowing when the show is back (ADR 0014). The pre-duck volume is written to
``duck_state_file`` before ducking so a crash never leaves the set quiet.
"""

from __future__ import annotations

import logging
from pathlib import Path
from typing import Any, Protocol

from adhush.control.base import ControlError, MuteController

log = logging.getLogger(__name__)

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
        duck = options.get("duck_level")
        self._duck_level: int | None = None if duck is None else int(duck)
        if self._duck_level is not None and not 0 <= self._duck_level <= 60:
            raise ControlError("rs232_sharp duck_level must be 0-60")
        self._normal_volume = int(options.get("normal_volume", 20))
        self._duck_state_file = str(options.get("duck_state_file", ""))
        self._pre_duck: int | None = None
        self._ducked = False

    def _exchange(self, command: str, parameter: str) -> str:
        if self._link is None:
            raise ControlError("controller is closed")
        self._link.write(frame_command(command, parameter))
        reply = self._link.read_line().strip().decode("ascii", errors="replace")
        if reply == "ERR" or not reply:
            raise ControlError(f"device rejected {command} {parameter}: {reply or 'timeout'}")
        return reply

    # -- ducking ---------------------------------------------------------------

    def query_volume(self) -> int | None:
        """The set's present volume (``VOLM?``), or None when it does not answer."""
        try:
            reply = self._exchange("VOLM", _QUERY)
        except ControlError:
            return None
        digits = "".join(ch for ch in reply if ch.isdigit())
        return int(digits) if digits else None

    def _set_volume(self, level: int) -> None:
        self._exchange("VOLM", str(max(0, min(60, level))))

    def _remember(self, volume: int | None) -> None:
        self._pre_duck = volume
        if not self._duck_state_file:
            return
        path = Path(self._duck_state_file)
        try:
            if volume is None:
                path.unlink(missing_ok=True)
            else:
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(str(volume))
        except OSError:
            log.warning("could not write %s", path)

    def recover_on_start(self) -> bool:
        """A previous run died ducked: put the volume back. True when it did."""
        if not self._duck_state_file:
            return False
        try:
            saved = int(Path(self._duck_state_file).read_text().strip())
        except (OSError, ValueError):
            return False
        self._set_volume(saved)
        self._remember(None)
        return True

    @property
    def ducked(self) -> bool:
        return self._ducked

    def mute(self) -> None:
        if self._duck_level is None:
            self._exchange("MUTE", _MUTE_ON)
            return
        if self._ducked:
            return
        now = self.query_volume()
        if now is not None and now != self._duck_level:
            self._remember(now)
        elif self._pre_duck is None:
            self._remember(self._normal_volume)
        self._set_volume(self._duck_level)
        self._ducked = True

    def unmute(self) -> None:
        if self._duck_level is None:
            self._exchange("MUTE", _MUTE_OFF)
            return
        back = self._pre_duck if self._pre_duck is not None else self._normal_volume
        self._set_volume(back)
        self._ducked = False
        self._remember(None)

    def state(self) -> bool | None:
        if self._duck_level is not None:
            now = self.query_volume()
            return self._ducked if now is None else now <= self._duck_level
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
        if self._link is not None and self._ducked:
            try:
                self.unmute()
            except ControlError:
                log.warning("could not restore the volume on close")
        if self._link is not None:
            self._link.close()
            self._link = None
