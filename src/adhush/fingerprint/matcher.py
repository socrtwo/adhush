"""Hamming search over the fingerprint index with a configurable distance threshold.

The index is held in memory as packed uint64 arrays and scanned with
vectorized XOR + popcount — at on-device scale (hundreds of ads, a dozen
hashes each) this beats a BK-tree while staying trivially correct; the
interface leaves room to swap the scan for a smarter index if stores grow.

A match is confirmed only after ``confirm_hits`` consecutive samples land on
the same ad (one hot frame is never enough), and the caller can demand audio
corroboration on top. The returned duration applies the slot-snap prior until
an ad has been observed ``snap_min_samples`` times, after which the learned
duration wins (docs/detection-strategies.md).
"""

from __future__ import annotations

from dataclasses import dataclass

import numpy as np

from adhush.config import FingerprintConfig
from adhush.fingerprint.audio_chroma import agreement
from adhush.fingerprint.store import AdRecord, FingerprintStore
from adhush.util.timing import snap_to_slot


@dataclass(frozen=True, slots=True)
class Match:
    ad_id: int
    est_start_ts: float  # estimated media time the ad began
    duration_s: float  # effective (possibly slot-snapped) duration
    confirmed_ts: float
    hamming: int  # distance of the confirming sample

    @property
    def expected_end_ts(self) -> float:
        return self.est_start_ts + self.duration_s


class Matcher:
    def __init__(self, store: FingerprintStore, config: FingerprintConfig) -> None:
        self._store = store
        self._cfg = config
        self._ad_ids: np.ndarray = np.empty(0, dtype=np.int64)
        self._offsets: np.ndarray = np.empty(0, dtype=np.float64)
        self._hashes: np.ndarray = np.empty(0, dtype=np.uint64)
        self._durations: dict[int, AdRecord] = {}
        self._candidate: int | None = None
        self._streak = 0
        self._est_start = 0.0
        self.refresh()

    def refresh(self) -> None:
        """Reload the in-memory index after the learner writes to the store."""
        rows = self._store.video_hashes()
        self._ad_ids = np.array([r[0] for r in rows], dtype=np.int64)
        self._offsets = np.array([r[1] for r in rows], dtype=np.float64)
        self._hashes = np.array([r[2] for r in rows], dtype=np.uint64)
        self._durations = {ad.ad_id: ad for ad in self._store.ads()}

    def reset(self) -> None:
        self._candidate = None
        self._streak = 0

    def effective_duration(self, ad_id: int) -> float:
        record = self._durations[ad_id]
        if record.sample_count >= self._cfg.snap_min_samples:
            return record.duration_s
        return snap_to_slot(record.duration_s, self._cfg.slot_snap_s)

    def feed(self, ts: float, frame_hash: int) -> Match | None:
        """Consume one sampled frame hash; returns a Match when confirmed."""
        if len(self._hashes) == 0:
            return None
        distances = np.bitwise_count(self._hashes ^ np.uint64(frame_hash))
        best = int(np.argmin(distances))
        best_distance = int(distances[best])
        if best_distance > self._cfg.hamming_threshold:
            self.reset()
            return None

        ad_id = int(self._ad_ids[best])
        est_start = ts - float(self._offsets[best])
        if ad_id == self._candidate:
            self._streak += 1
        else:
            self._candidate = ad_id
            self._streak = 1
            self._est_start = est_start
        if self._streak < self._cfg.confirm_hits:
            return None
        return Match(
            ad_id=ad_id,
            est_start_ts=self._est_start,
            duration_s=self.effective_duration(ad_id),
            confirmed_ts=ts,
            hamming=best_distance,
        )

    def corroborate(
        self, ad_id: int, est_start_ts: float, blocks: list[tuple[float, int]]
    ) -> float:
        """Bit agreement between live chroma blocks and the ad's stored blocks.

        Live blocks are aligned to stored offsets by media time relative to
        the estimated ad start; unmatched offsets are simply skipped.
        """
        stored = self._store.audio_blocks(ad_id)
        if not stored or not blocks:
            return 0.0
        half_block = self._cfg.sample_interval_s / 2
        stored_bits: list[int] = []
        live_bits: list[int] = []
        for ts, bits in blocks:
            offset = ts - est_start_ts
            nearest = min(stored, key=lambda row: abs(row[0] - offset))
            if abs(nearest[0] - offset) <= half_block:
                stored_bits.append(nearest[1])
                live_bits.append(bits)
        return agreement(stored_bits, live_bits)
