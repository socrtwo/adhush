"""A small XMLTV reader (ADR 0024): channels, programmes, and what is on now.

XMLTV is what Schedules Direct's grabbers, tv_grab_* and most EPG tools
write: ``<channel id>`` with display names and ``<programme start stop
channel>`` with a title. Times are ``YYYYMMDDHHMMSS ±HHMM``; the offset is
honoured and everything is kept as Unix epoch seconds.
"""

from __future__ import annotations

import xml.etree.ElementTree as ET
from dataclasses import dataclass
from datetime import UTC, datetime, timedelta, timezone
from pathlib import Path


@dataclass(frozen=True, slots=True)
class Programme:
    channel: str
    title: str
    start: float
    stop: float


def parse_time(raw: str) -> float:
    """``20260916200000 -0400`` → epoch seconds; a missing offset is UTC."""
    raw = raw.strip()
    stamp, _, offset = raw.partition(" ")
    stamp = stamp.ljust(14, "0")[:14]
    tz = UTC
    if offset and len(offset) == 5 and offset[0] in "+-":
        sign = 1 if offset[0] == "+" else -1
        tz = timezone(sign * timedelta(hours=int(offset[1:3]), minutes=int(offset[3:5])))
    month, day, hour, minute, second = (int(stamp[i : i + 2]) for i in (4, 6, 8, 10, 12))
    dt = datetime(int(stamp[:4]), month, day, hour, minute, second, tzinfo=tz)
    return dt.timestamp()


class Schedule:
    def __init__(self, programmes: list[Programme], names: dict[str, str]) -> None:
        self._by_channel: dict[str, list[Programme]] = {}
        for p in sorted(programmes, key=lambda p: p.start):
            self._by_channel.setdefault(p.channel, []).append(p)
        # display name (lower-cased) → channel id
        self._names = {name.lower(): cid for cid, name in names.items()}
        self._names.update({cid.lower(): cid for cid in self._by_channel})

    @classmethod
    def load(cls, path: Path) -> Schedule:
        root = ET.parse(path).getroot()
        names: dict[str, str] = {}
        for ch in root.iter("channel"):
            cid = ch.get("id", "")
            dn = ch.find("display-name")
            if cid and dn is not None and dn.text:
                names[cid] = dn.text.strip()
        programmes = []
        for pr in root.iter("programme"):
            start, stop, channel = pr.get("start"), pr.get("stop"), pr.get("channel", "")
            if not start or not channel:
                continue
            title_el = pr.find("title")
            title = (title_el.text or "").strip() if title_el is not None else ""
            t0 = parse_time(start)
            t1 = parse_time(stop) if stop else t0 + 1800.0
            programmes.append(Programme(channel, title, t0, t1))
        return cls(programmes, names)

    def resolve(self, channel: str) -> str | None:
        """A channel id or display name → the id, or None."""
        return self._names.get(channel.lower())

    def current(self, channel: str, wall: float) -> Programme | None:
        cid = self.resolve(channel)
        if cid is None:
            return None
        for p in self._by_channel.get(cid, []):
            if p.start <= wall < p.stop:
                return p
        return None

    def channels(self) -> list[str]:
        return sorted(self._by_channel)
