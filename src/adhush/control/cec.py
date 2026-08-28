"""HDMI-CEC user control codes (libcec) where the sink honors them.

Sends User Control Pressed (0x44) with the MUTE operand (0x43) followed by
User Control Released (0x45) through ``cec-client`` in single-command mode.
CEC exposes only a mute *toggle* at the user-control level, so this backend
never claims discrete mute — pair it with ``control.verify_with_audio`` so
fusion can detect desynchronization. Support varies wildly by set; ``adhush
probe`` reports whether the adapter and client are present at all.

The command runner is injectable so framing is testable without an adapter.
"""

from __future__ import annotations

import subprocess
from collections.abc import Callable, Sequence
from typing import Any

from adhush.control.base import ControlError, MuteController

# One irrevocable CEC fact: frame header is (source << 4) | destination.
_UC_PRESSED = 0x44
_UC_RELEASED = 0x45
_UC_MUTE = 0x43

# Runs cec-client with the given tx lines on stdin; returns the exit code.
CecRunner = Callable[[Sequence[str]], int]


def _run_cec_client(lines: Sequence[str]) -> int:
    try:
        proc = subprocess.run(
            ["cec-client", "-s", "-d", "1"],
            input="\n".join(lines) + "\n",
            text=True,
            capture_output=True,
            timeout=10.0,
            check=False,
        )
        return proc.returncode
    except FileNotFoundError as exc:
        raise ControlError("cec-client not found; install cec-utils") from exc
    except subprocess.TimeoutExpired as exc:
        raise ControlError("cec-client timed out; is the CEC adapter present?") from exc


def frame_user_control(source: int, destination: int, operand: int | None) -> str:
    """One cec-client ``tx`` line for a user-control opcode."""
    for name, value in (("source", source), ("destination", destination)):
        if not 0 <= value <= 15:
            raise ValueError(f"cec {name} address {value} outside 0..15")
    header = (source << 4) | destination
    opcode = _UC_RELEASED if operand is None else _UC_PRESSED
    parts = [f"{header:02x}", f"{opcode:02x}"]
    if operand is not None:
        parts.append(f"{operand:02x}")
    return "tx " + ":".join(parts)


class CecController(MuteController):
    def __init__(self, options: dict[str, Any], runner: CecRunner | None = None) -> None:
        self._source = int(options.get("source_address", 1))
        self._destination = int(options.get("destination_address", 0))  # 0 = TV
        self._run = runner if runner is not None else _run_cec_client

    def _toggle(self) -> None:
        lines = [
            frame_user_control(self._source, self._destination, _UC_MUTE),
            frame_user_control(self._source, self._destination, None),
        ]
        code = self._run(lines)
        if code != 0:
            raise ControlError(f"cec-client exited {code}")

    def mute(self) -> None:
        self._toggle()

    def unmute(self) -> None:
        self._toggle()

    def supports_discrete(self) -> bool:
        return False
