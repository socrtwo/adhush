"""LIRC / gpio-ir transmit on Raspberry Pi GPIO; carrier, duty, repeat handling.

Sends through the ``irsend`` client, so carrier frequency and duty cycle come
from the LIRC remote definition (populated per profile via ``adhush ir-test``).
Discrete mute is available only when the remote defines distinct on/off keys;
with a toggle-only key the controller declares non-discrete and the engine's
audio verification (control.verify_with_audio) has to compensate.

The command runner is injectable so behavior is testable without LIRC.
"""

from __future__ import annotations

import subprocess
from collections.abc import Callable, Sequence
from typing import Any

from adhush.control.base import ControlError, MuteController

# Returns the process exit code for one irsend invocation.
CommandRunner = Callable[[Sequence[str]], int]


def _run_irsend(args: Sequence[str]) -> int:
    try:
        return subprocess.run(list(args), check=False, timeout=5.0).returncode
    except FileNotFoundError as exc:
        raise ControlError("irsend not found; install lirc") from exc
    except subprocess.TimeoutExpired as exc:
        raise ControlError("irsend timed out; is lircd running?") from exc


class IrLircController(MuteController):
    def __init__(self, options: dict[str, Any], runner: CommandRunner | None = None) -> None:
        self._remote = str(options.get("remote", ""))
        if not self._remote:
            raise ControlError("control.ir_lirc.remote is required")
        self._key_on = str(options.get("mute_on", ""))
        self._key_off = str(options.get("mute_off", ""))
        self._key_toggle = str(options.get("mute_toggle", ""))
        if not ((self._key_on and self._key_off) or self._key_toggle):
            raise ControlError("ir_lirc needs mute_on+mute_off keys, or mute_toggle")
        self._repeat = max(0, int(options.get("repeat", 0)))
        self._run = runner if runner is not None else _run_irsend

    def _send(self, key: str) -> None:
        args = ["irsend", "SEND_ONCE", self._remote, key]
        if self._repeat:
            args[1:1] = [f"--count={self._repeat + 1}"]
        code = self._run(args)
        if code != 0:
            raise ControlError(f"irsend exited {code} for {self._remote}/{key}")

    def mute(self) -> None:
        self._send(self._key_on if self._key_on else self._key_toggle)

    def unmute(self) -> None:
        self._send(self._key_off if self._key_off else self._key_toggle)

    def supports_discrete(self) -> bool:
        return bool(self._key_on and self._key_off)
