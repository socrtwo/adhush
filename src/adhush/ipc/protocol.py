"""Shared wire schema used by every platform front end.

Versioned JSON messages. Server-to-client *events* carry ``{"v", "type",
"data"}``; client-to-server *commands* carry ``{"v", "type", ...fields}``.
Event types: ``status``, ``transition``, ``decision`` (only while trace is
enabled). Command types: ``get_status``, ``override`` (mode: auto|mute|
unmute), ``confirm_ad``, ``show_back``, ``reject_ad``, ``set_trace`` (enabled: bool), and
``shutdown`` (stop the core; the transport unmutes first — see engine).
Adding a command type keeps VERSION at 1: old clients never send it, and
old servers reject it with a clean protocol error.

Everything here is pure data marshalling — no I/O — so front ends on any
platform can validate against it directly.
"""

from __future__ import annotations

import json
from dataclasses import asdict, dataclass, is_dataclass
from typing import Any

from adhush.control.remote_keys import KEY_NAMES

VERSION = 1

EVENT_TYPES = ("status", "transition", "decision")
COMMAND_TYPES = (
    "get_status", "override", "confirm_ad", "show_back", "reject_ad", "set_trace", "shutdown",
    "duck_for", "remote",
)
MAX_TIMED_S = 600
OVERRIDE_MODES = ("auto", "mute", "unmute")


class ProtocolError(ValueError):
    """Raised for malformed or unsupported wire messages."""


@dataclass(frozen=True, slots=True)
class Command:
    type: str
    mode: str = ""  # override
    enabled: bool = False  # set_trace
    seconds: int = 0  # duck_for
    key: str = ""  # remote


def encode_event(event_type: str, data: Any) -> str:
    """Serialize one server event to a JSON line."""
    if event_type not in EVENT_TYPES:
        raise ProtocolError(f"unknown event type: {event_type}")
    if is_dataclass(data) and not isinstance(data, type):
        payload: Any = asdict(data)
    else:
        payload = data
    return json.dumps(
        {"v": VERSION, "type": event_type, "data": payload}, default=_jsonify
    )


def _jsonify(value: Any) -> Any:
    if hasattr(value, "value"):  # enums
        return value.value
    return str(value)


def parse_command(raw: str | bytes) -> Command:
    """Parse and validate one client command."""
    try:
        message = json.loads(raw)
    except (json.JSONDecodeError, UnicodeDecodeError) as exc:
        raise ProtocolError(f"invalid JSON: {exc}") from exc
    if not isinstance(message, dict):
        raise ProtocolError("command must be a JSON object")
    if message.get("v", VERSION) != VERSION:
        raise ProtocolError(f"unsupported protocol version: {message.get('v')}")
    command_type = message.get("type")
    if command_type not in COMMAND_TYPES:
        raise ProtocolError(f"unknown command type: {command_type}")
    mode = str(message.get("mode", ""))
    if command_type == "override" and mode not in OVERRIDE_MODES:
        raise ProtocolError(f"override mode must be one of {OVERRIDE_MODES}")
    seconds = 0
    if command_type == "duck_for":
        try:
            seconds = int(message.get("seconds", 0))
        except (TypeError, ValueError) as exc:
            raise ProtocolError("duck_for seconds must be a number") from exc
        if not 1 <= seconds <= MAX_TIMED_S:
            raise ProtocolError(f"duck_for seconds must be 1..{MAX_TIMED_S}")
    key = str(message.get("key", ""))
    if command_type == "remote" and key not in KEY_NAMES:
        raise ProtocolError(f"remote key must be one of {KEY_NAMES}")
    return Command(
        type=str(command_type),
        mode=mode,
        enabled=bool(message.get("enabled", False)),
        seconds=seconds,
        key=key,
    )
