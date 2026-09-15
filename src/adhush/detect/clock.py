"""Break clock: a learned prior on the minute of the hour (ADR 0017).

Cable news runs to a format clock — breaks land at roughly the same minutes
every hour — but the clock is neither published nor exact, so nothing here is
hard-coded. Every break the other detectors or the user confirm is scored
against the minutes it covered; every minute the pipeline watches is counted
as seen. The vote is the fraction of watched hours in which this minute was a
break, scaled so ``full_fraction`` reads as certainty. It is a default-weight
vote: alone it can never mute, it tips the balance. Inert until a minute has
been watched in ``min_hours`` distinct hours, so learning never dilutes fusion.
"""

from __future__ import annotations

from pathlib import Path
from typing import ClassVar

from adhush.config import ClockConfig
from adhush.detect.base import Detector
from adhush.events import DetectorVote

_MAX_COUNT = 100_000


class ClockDetector(Detector):
    name: ClassVar[str] = "clock"

    def __init__(self, config: ClockConfig) -> None:
        self._cfg = config
        self._breaks = [0] * 60
        self._seen = [0] * 60
        self._minute = -1
        self._last_key: int | None = None
        self._hour_of_last_save: int | None = None
        self._load()

    # -- persistence (same TSV as the phone: m<TAB>minute<TAB>breaks<TAB>hours_seen) --

    def _path(self) -> Path | None:
        return Path(self._cfg.file) if self._cfg.file else None

    def _load(self) -> None:
        path = self._path()
        if path is None or not path.is_file():
            return
        for line in path.read_text().splitlines():
            parts = line.split("\t")
            if parts[0] == "m" and len(parts) >= 4:
                m = int(parts[1])
                if 0 <= m < 60:
                    self._breaks[m], self._seen[m] = int(parts[2]), int(parts[3])

    def _save(self) -> None:
        path = self._path()
        if path is None:
            return
        path.parent.mkdir(parents=True, exist_ok=True)
        tmp = path.with_suffix(path.suffix + ".tmp")
        lines = ["# adhush clock v1\tminute\tbreaks\thours_seen"]
        lines += [f"m\t{m}\t{self._breaks[m]}\t{self._seen[m]}" for m in range(60)]
        tmp.write_text("\n".join(lines) + "\n")
        tmp.replace(path)

    # -- learning ------------------------------------------------------------

    def tick(self, wall: float) -> None:
        """Wall-clock seconds; every decision. Each (hour, minute) is seen once."""
        key = int(wall // 60)
        self._minute = key % 60
        if key == self._last_key:
            return
        self._last_key = key
        self._seen[self._minute] = min(self._seen[self._minute] + 1, _MAX_COUNT)
        hour = key // 60
        if hour != self._hour_of_last_save:
            self._hour_of_last_save = hour
            self._save()

    def learn(self, start_wall: float, end_wall: float) -> None:
        """A confirmed break; only plausible lengths teach."""
        duration = end_wall - start_wall
        if duration < self._cfg.min_break_s or duration > self._cfg.max_break_s:
            return
        first, last = int(start_wall // 60), int((end_wall - 1.0) // 60)
        for key in range(first, last + 1):
            m = key % 60
            self._breaks[m] = min(self._breaks[m] + 1, _MAX_COUNT)
        self._save()

    # -- voting --------------------------------------------------------------

    @property
    def voting(self) -> bool:
        return self._minute >= 0 and self._seen[self._minute] >= self._cfg.min_hours

    def _fraction(self, m: int) -> float:
        return 0.0 if self._seen[m] <= 0 else min(1.0, self._breaks[m] / self._seen[m])

    def vote(self, ts: float) -> DetectorVote:
        if not self.voting:
            seen = self._seen[self._minute] if self._minute >= 0 else 0
            return self._vote(ts, 0.0, f"clock_learning minute={self._minute} seen={seen}")
        frac = self._fraction(self._minute)
        return self._vote(
            ts,
            frac / self._cfg.full_fraction,
            f"clock minute={self._minute} frac={frac:.2f} seen={self._seen[self._minute]}",
        )

    def describe(self) -> str:
        if self._minute < 0:
            return "no time yet"
        seen = self._seen[self._minute]
        if seen < self._cfg.min_hours:
            return f":{self._minute:02d} learning ({seen} of {self._cfg.min_hours} hours)"
        return f":{self._minute:02d} a break in {self._breaks[self._minute]} of {seen} hours"

    def break_minutes(self) -> list[int]:
        """Minutes of the hour that are usually a break."""
        return [
            m for m in range(60)
            if self._seen[m] >= self._cfg.min_hours and self._fraction(m) >= self._cfg.full_fraction
        ]
