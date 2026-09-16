"""The crowd detector (ADR 0024): other AdHush devices on the same channel.

Every device that opts in reports the wall-clock time of each confirmed
break's start and end, under a hash of the channel's name; it fetches the
reports for its hash prefix and keeps the ones for its own full hash. When
at least ``min_reports`` *other* devices have reported a start within
``window_s`` and no end since, that is a break — the same evidence a
fingerprint gives, so it may act alone. An end from others is programme
evidence for a few seconds. Network I/O runs on its own thread; the
detector only reads what the thread last fetched. Inert without a server,
with no reports, and during a replay (no wall clock).
"""

from __future__ import annotations

import hashlib
import json
import logging
import secrets
import threading
import urllib.error
import urllib.request
from typing import ClassVar

from adhush.config import CrowdConfig
from adhush.detect.base import Detector
from adhush.events import DetectorVote

log = logging.getLogger(__name__)
_END_HOLD_S = 8.0


def channel_hash(channel: str, salt: str) -> str:
    return hashlib.sha256(f"{salt}:{channel.strip().lower()}".encode()).hexdigest()


class CrowdDetector(Detector):
    name: ClassVar[str] = "crowd"

    def __init__(self, config: CrowdConfig, device_id: str | None = None) -> None:
        self._cfg = config
        self._device = device_id or secrets.token_hex(8)
        self._hash = channel_hash(config.channel, config.salt) if config.channel else ""
        self._lock = threading.Lock()
        self._reports: list[dict[str, object]] = []
        self._wall: float | None = None
        self._last_poll = -1e18
        self._last_ts = 0.0
        self._polls = 0
        self._errors = 0

    @property
    def enabled(self) -> bool:
        return bool(self._cfg.url and self._hash)

    # -- network, off the decision thread ---------------------------------------

    def _fetch(self, since: float) -> None:
        url = f"{self._cfg.url.rstrip('/')}/recent?prefix={self._hash[:4]}&since={since:.0f}"
        try:
            with urllib.request.urlopen(url, timeout=4) as resp:
                body = json.loads(resp.read())
            mine = [r for r in body.get("reports", []) if r.get("hash") == self._hash and r.get("device") != self._device]
            with self._lock:
                self._reports = mine
                self._polls += 1
        except (urllib.error.URLError, OSError, ValueError, TypeError) as e:
            self._errors += 1
            if self._errors <= 3:
                log.warning("crowd: %s", e)

    def _post(self, kind: str, wall: float) -> None:
        data = json.dumps({"hash": self._hash, "kind": kind, "ts": wall, "device": self._device}).encode()
        req = urllib.request.Request(f"{self._cfg.url.rstrip('/')}/report", data=data, headers={"Content-Type": "application/json"})
        try:
            urllib.request.urlopen(req, timeout=4).close()
        except (urllib.error.URLError, OSError, ValueError) as e:
            self._errors += 1
            if self._errors <= 3:
                log.warning("crowd report: %s", e)

    def report(self, kind: str, wall: float) -> None:
        """This device's own confirmed break start/end; sent when reporting is on."""
        if not self.enabled or not self._cfg.report:
            return
        threading.Thread(target=self._post, args=(kind, wall), daemon=True).start()

    def tick(self, wall: float) -> None:
        self._wall = wall
        if not self.enabled or wall - self._last_poll < self._cfg.poll_s:
            return
        self._last_poll = wall
        threading.Thread(target=self._fetch, args=(wall - self._cfg.window_s - _END_HOLD_S,), daemon=True).start()

    # -- the vote ----------------------------------------------------------------

    def _state(self) -> tuple[int, float | None]:
        """(devices in a break now, wall time of the latest end from others)."""
        wall = self._wall
        if wall is None:
            return 0, None
        with self._lock:
            reports = list(self._reports)
        latest: dict[str, tuple[float, str]] = {}
        for r in reports:
            dev = str(r.get("device", ""))
            raw_ts = r.get("ts", 0)
            ts = float(raw_ts) if isinstance(raw_ts, int | float | str) else 0.0
            if dev not in latest or ts > latest[dev][0]:
                latest[dev] = (ts, str(r.get("kind", "")))
        in_break = sum(1 for ts, kind in latest.values() if kind == "start" and wall - ts <= self._cfg.window_s)
        ends = [ts for ts, kind in latest.values() if kind == "end" and wall - ts <= _END_HOLD_S]
        return in_break, max(ends) if ends else None

    @property
    def program_present(self) -> bool:
        return self._state()[1] is not None

    @property
    def voting(self) -> bool:
        n, end = self._state()
        return n >= self._cfg.min_reports or end is not None

    def vote(self, ts: float) -> DetectorVote:
        self._last_ts = ts
        n, end = self._state()
        if n >= self._cfg.min_reports:
            return self._vote(ts, 1.0, f"crowd_break devices={n}")
        if end is not None:
            return self._vote(ts, 0.0, "crowd_end")
        return self._vote(ts, 0.0, f"crowd_quiet devices={n}")

    def describe(self) -> str:
        if not self.enabled:
            return "off (no server or channel)"
        n, _ = self._state()
        return f"{n} device(s) in a break; {self._polls} poll(s), {self._errors} error(s)"
