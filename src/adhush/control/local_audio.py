"""Mute the host's own audio path (screen/app mode) instead of the TV.

The desktop/laptop platforms play the stream through the host's speakers, so
muting the default sink is discrete, instant, and verifiable:

- Linux/ChromeOS-Crostini: ``pactl set-sink-mute @DEFAULT_SINK@ 1|0`` with
  ``pactl get-sink-mute`` readback (``amixer`` as a fallback via options).
- macOS: ``osascript`` output-muted set/get.
- Windows: ``nircmd mutesysvolume 1|0`` (no readback).

The command runner is injectable so each platform's command line is testable
anywhere.
"""

from __future__ import annotations

import subprocess
import sys
from collections.abc import Callable, Sequence
from typing import Any

from adhush.control.base import ControlError, MuteController

# Runs one command; returns (exit_code, stdout).
Runner = Callable[[Sequence[str]], tuple[int, str]]


def _run(args: Sequence[str]) -> tuple[int, str]:
    try:
        proc = subprocess.run(
            list(args), capture_output=True, text=True, timeout=5.0, check=False
        )
        return proc.returncode, proc.stdout
    except FileNotFoundError as exc:
        raise ControlError(f"{args[0]} not found") from exc
    except subprocess.TimeoutExpired as exc:
        raise ControlError(f"{args[0]} timed out") from exc


def mute_commands(platform: str, tool: str) -> dict[str, list[str]]:
    """Command lines per action for a platform; 'state' may be absent."""
    if platform == "linux":
        if tool == "amixer":
            return {
                "mute": ["amixer", "-q", "set", "Master", "mute"],
                "unmute": ["amixer", "-q", "set", "Master", "unmute"],
                "state": ["amixer", "get", "Master"],
            }
        return {
            "mute": ["pactl", "set-sink-mute", "@DEFAULT_SINK@", "1"],
            "unmute": ["pactl", "set-sink-mute", "@DEFAULT_SINK@", "0"],
            "state": ["pactl", "get-sink-mute", "@DEFAULT_SINK@"],
        }
    if platform == "darwin":
        return {
            "mute": ["osascript", "-e", "set volume with output muted"],
            "unmute": ["osascript", "-e", "set volume without output muted"],
            "state": ["osascript", "-e", "output muted of (get volume settings)"],
        }
    if platform == "win32":
        return {
            "mute": ["nircmd", "mutesysvolume", "1"],
            "unmute": ["nircmd", "mutesysvolume", "0"],
        }
    raise ControlError(f"local_audio is not supported on platform {platform}")


def parse_mute_state(platform: str, tool: str, output: str) -> bool | None:
    if platform == "linux":
        if tool == "amixer":
            return "[off]" in output if "[" in output else None
        if "yes" in output:
            return True
        if "no" in output:
            return False
        return None
    if platform == "darwin":
        text = output.strip().lower()
        if text == "true":
            return True
        if text == "false":
            return False
    return None


class LocalAudioController(MuteController):
    def __init__(
        self,
        options: dict[str, Any],
        runner: Runner | None = None,
        platform: str | None = None,
    ) -> None:
        self._platform = platform if platform is not None else sys.platform
        self._tool = str(options.get("tool", "pactl"))
        self._commands = mute_commands(self._platform, self._tool)
        self._run = runner if runner is not None else _run

    def _do(self, action: str) -> tuple[int, str]:
        code, output = self._run(self._commands[action])
        if code != 0:
            raise ControlError(f"local_audio {action} exited {code}")
        return code, output

    def mute(self) -> None:
        self._do("mute")

    def unmute(self) -> None:
        self._do("unmute")

    def state(self) -> bool | None:
        command = self._commands.get("state")
        if command is None:
            return None
        try:
            _, output = self._do("state")
        except ControlError:
            return None
        return parse_mute_state(self._platform, self._tool, output)

    def supports_discrete(self) -> bool:
        return True
