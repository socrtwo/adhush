"""SQLite-backed fingerprint store: ad_id, hashes, observed duration, hit counts, TTL.

One row per learned ad plus its first-seconds video hashes and audio chroma
blocks, keyed by offset from the estimated ad start. ``prune`` implements the
TTL: ads not re-observed within ``ttl_days`` are dropped so the index stays
small on-device. ``:memory:`` works for tests and replay runs.
"""

from __future__ import annotations

import sqlite3
import time
from dataclasses import dataclass
from pathlib import Path

_SCHEMA = """
CREATE TABLE IF NOT EXISTS ads (
    ad_id INTEGER PRIMARY KEY AUTOINCREMENT,
    duration_s REAL NOT NULL,
    sample_count INTEGER NOT NULL DEFAULT 1,
    created_ts REAL NOT NULL,
    updated_ts REAL NOT NULL
);
CREATE TABLE IF NOT EXISTS video_hashes (
    ad_id INTEGER NOT NULL REFERENCES ads(ad_id) ON DELETE CASCADE,
    offset_s REAL NOT NULL,
    hash INTEGER NOT NULL
);
CREATE TABLE IF NOT EXISTS audio_blocks (
    ad_id INTEGER NOT NULL REFERENCES ads(ad_id) ON DELETE CASCADE,
    offset_s REAL NOT NULL,
    bits INTEGER NOT NULL
);
CREATE INDEX IF NOT EXISTS idx_video_ad ON video_hashes(ad_id);
CREATE INDEX IF NOT EXISTS idx_audio_ad ON audio_blocks(ad_id);
"""


@dataclass(frozen=True, slots=True)
class AdRecord:
    ad_id: int
    duration_s: float
    sample_count: int


class FingerprintStore:
    def __init__(self, path: str | Path) -> None:
        if str(path) != ":memory:":
            Path(path).parent.mkdir(parents=True, exist_ok=True)
        self._db = sqlite3.connect(str(path))
        self._db.execute("PRAGMA foreign_keys = ON")
        self._db.executescript(_SCHEMA)
        self._db.commit()

    def add_ad(
        self,
        duration_s: float,
        video: list[tuple[float, int]],
        audio: list[tuple[float, int]],
        now: float | None = None,
    ) -> int:
        ts = time.time() if now is None else now
        cur = self._db.execute(
            "INSERT INTO ads (duration_s, sample_count, created_ts, updated_ts)"
            " VALUES (?, 1, ?, ?)",
            (duration_s, ts, ts),
        )
        ad_id = int(cur.lastrowid or 0)
        self._db.executemany(
            "INSERT INTO video_hashes (ad_id, offset_s, hash) VALUES (?, ?, ?)",
            [(ad_id, off, h) for off, h in video],
        )
        self._db.executemany(
            "INSERT INTO audio_blocks (ad_id, offset_s, bits) VALUES (?, ?, ?)",
            [(ad_id, off, b) for off, b in audio],
        )
        self._db.commit()
        return ad_id

    def get(self, ad_id: int) -> AdRecord | None:
        row = self._db.execute(
            "SELECT ad_id, duration_s, sample_count FROM ads WHERE ad_id = ?", (ad_id,)
        ).fetchone()
        return AdRecord(*row) if row else None

    def ads(self) -> list[AdRecord]:
        rows = self._db.execute(
            "SELECT ad_id, duration_s, sample_count FROM ads ORDER BY ad_id"
        ).fetchall()
        return [AdRecord(*row) for row in rows]

    def video_hashes(self) -> list[tuple[int, float, int]]:
        """All (ad_id, offset_s, hash) rows; the matcher's in-memory index."""
        return list(
            self._db.execute("SELECT ad_id, offset_s, hash FROM video_hashes ORDER BY ad_id")
        )

    def audio_blocks(self, ad_id: int) -> list[tuple[float, int]]:
        return list(
            self._db.execute(
                "SELECT offset_s, bits FROM audio_blocks WHERE ad_id = ? ORDER BY offset_s",
                (ad_id,),
            )
        )

    def update_duration(
        self, ad_id: int, duration_s: float, sample_count: int, now: float | None = None
    ) -> None:
        ts = time.time() if now is None else now
        self._db.execute(
            "UPDATE ads SET duration_s = ?, sample_count = ?, updated_ts = ? WHERE ad_id = ?",
            (duration_s, sample_count, ts, ad_id),
        )
        self._db.commit()

    def prune(self, ttl_days: float, now: float | None = None) -> int:
        """Drop ads not re-observed within the TTL; returns rows removed."""
        ts = time.time() if now is None else now
        cur = self._db.execute(
            "DELETE FROM ads WHERE updated_ts < ?", (ts - ttl_days * 86400.0,)
        )
        self._db.commit()
        return cur.rowcount

    def count(self) -> int:
        return int(self._db.execute("SELECT COUNT(*) FROM ads").fetchone()[0])

    def close(self) -> None:
        self._db.close()
