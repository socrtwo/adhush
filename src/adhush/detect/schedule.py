"""EPG programme boundaries from an XMLTV schedule (ADR 0024).

Two uses, both mild by design. For ``start_grace_s`` after a scheduled
programme start the detector votes 0 with weight: a break in the first
minute and a half of a show is unlikely, so the mute votes are diluted
then, never vetoed — a schedule is a minute out as often as not and a
break that runs across the top of the hour must still be muted once the
evidence is there. On a channel listed as ``ad_free`` (PBS, BBC) the
detector reports ``ad_free_now`` and the engine never mutes at all.

It never says the programme is *present*: an EPG cannot see a break end.
Inert without a file, outside the grace window, and during a replay (no
wall clock). The current programme's title is in ``describe``.
"""

from __future__ import annotations

import logging
import xml.etree.ElementTree as ET
from pathlib import Path
from typing import ClassVar

from adhush.config import ScheduleConfig
from adhush.detect.base import Detector
from adhush.events import DetectorVote
from adhush.util.xmltv import Programme, Schedule

log = logging.getLogger(__name__)


class ScheduleDetector(Detector):
    name: ClassVar[str] = "schedule"

    def __init__(self, config: ScheduleConfig) -> None:
        self._cfg = config
        self._schedule: Schedule | None = None
        self._loaded_at = -1e18
        self._mtime = 0.0
        self._wall: float | None = None
        self._current: Programme | None = None

    def warmup(self) -> None:
        self._wall = None
        self._current = None

    def _load(self, wall: float) -> None:
        path = Path(self._cfg.file)
        try:
            mtime = path.stat().st_mtime
        except OSError:
            return
        if self._schedule is not None and mtime == self._mtime and wall - self._loaded_at < self._cfg.reload_s:
            return
        try:
            self._schedule = Schedule.load(path)
            self._mtime = mtime
            self._loaded_at = wall
            log.info("schedule: %s, %d channel(s)", path, len(self._schedule.channels()))
        except (OSError, ET.ParseError, ValueError) as e:  # a bad file must not stop the loop
            log.warning("schedule: cannot read %s: %s", path, e)

    def tick(self, wall: float) -> None:
        if not self._cfg.file:
            return
        self._wall = wall
        self._load(wall)
        if self._schedule is not None and self._cfg.channel:
            self._current = self._schedule.current(self._cfg.channel, wall)

    @property
    def current(self) -> Programme | None:
        return self._current

    @property
    def ad_free_now(self) -> bool:
        if not self._cfg.channel:
            return False
        wanted = {c.lower() for c in self._cfg.ad_free}
        if self._cfg.channel.lower() in wanted:
            return True
        cur = self._current
        return cur is not None and cur.channel.lower() in wanted

    def _grace_left(self) -> float:
        if self._wall is None or self._current is None:
            return 0.0
        return max(0.0, self._current.start + self._cfg.start_grace_s - self._wall)

    @property
    def voting(self) -> bool:
        return self._grace_left() > 0.0

    def vote(self, ts: float) -> DetectorVote:
        left = self._grace_left()
        if left > 0.0 and self._current is not None:
            return self._vote(ts, 0.0, f"programme_started title={self._current.title!r} grace_left_s={left:.0f}")
        return self._vote(ts, 0.0, "schedule_idle")

    def describe(self) -> str:
        if not self._cfg.file:
            return "no schedule file"
        if self._schedule is None:
            return "schedule not loaded"
        cur = self._current
        if cur is None or self._wall is None:
            return f"nothing listed for {self._cfg.channel!r} now"
        left = (cur.stop - self._wall) / 60.0
        return f"{cur.title} ({left:.0f} min left)" + (" — ad-free channel" if self.ad_free_now else "")
