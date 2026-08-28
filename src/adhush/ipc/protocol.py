"""Shared wire schema used by every platform front end.

Versioned JSON messages. Server-to-client *events* carry ``{"v", "type",
"data"}``; client-to-server *commands* carry ``{"v", "type", ...fields}``.
Event types: ``status``, ``transition``, ``decision`` (only while trace is
enabled). Command types: ``get_status``, ``override`` (mode: auto|mute|
unmute), ``confirm_ad``, ``reject_ad``, ``set_trace`` (enabled: bool).

Everything here is pure data marshalling — no I/O — so front ends on any
platform can validate against it directly.
"""

from __future__ import annotations

import json
from dataclasses import asdict, dataclass, is_dataclass
from typing import Any

VERSION = 1

EVENT_TYPES = ("status", "transition", "decision")
COMMAND_TYPES = ("get_status", "override", "confirm_ad", "reject_ad", "set_trace")
OVERRIDE_MODES = ("auto", "mute", "unmute")


class ProtocolError(ValueError):
    """Raised for malformed or unsupported wire messages."""


@dataclass(frozen=True, slots=True)
class Command:
    type: str
    mode: str = ""  # override
    enabled: bool = False  # set_trace


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
    return Command(
        type=str(command_type), mode=mode, enabled=bool(message.get("enabled", False))
    )
