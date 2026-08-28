"""Learns new ads from confirmed segments; snaps durations to 15/30/45/60s slots.

``learn_segment`` stores the first-seconds hashes of a fusion-confirmed ad,
unless those hashes already match a stored ad — then the sighting updates
that ad's duration instead of duplicating it. ``observe_duration`` folds each
observed airing into a running mean and bumps the sample count; once enough
airings agree, the matcher stops applying the slot-snap prior and the learned
duration wins outright. An early unmute therefore shortens the stored
duration for that ad, exactly as docs/detection-strategies.md prescribes.
"""

from __future__ import annotations

import logging

from adhush.config import FingerprintConfig
from adhush.fingerprint.store import FingerprintStore
from adhush.fingerprint.video_phash import hamming

log = logging.getLogger(__name__)


class Learner:
    def __init__(self, store: FingerprintStore, config: FingerprintConfig) -> None:
        self._store = store
        self._cfg = config

    def learn_segment(
        self,
        start_ts: float,
        duration_s: float,
        video_samples: list[tuple[float, int]],  # (media_ts, phash)
        audio_blocks: list[tuple[float, int]],  # (media_ts, chroma bits)
    ) -> int | None:
        """Store a confirmed ad segment; returns the ad_id, or None if skipped."""
        if not self._cfg.min_learn_s <= duration_s <= self._cfg.max_learn_s:
            log.debug("segment duration %.1fs outside learn bounds; skipped", duration_s)
            return None
        window_end = start_ts + self._cfg.window_s
        video = [
            (ts - start_ts, h) for ts, h in video_samples if start_ts <= ts <= window_end
        ]
        audio = [
            (ts - start_ts, b) for ts, b in audio_blocks if start_ts <= ts <= window_end
        ]
        if len(video) < self._cfg.confirm_hits:
            log.debug("only %d hashable frames in learn window; skipped", len(video))
            return None

        known = self._find_existing(video)
        if known is not None:
            self.observe_duration(known, duration_s)
            return known
        ad_id = self._store.add_ad(duration_s, video, audio)
        log.info("learned ad %d duration=%.1fs hashes=%d", ad_id, duration_s, len(video))
        return ad_id

    def observe_duration(self, ad_id: int, duration_s: float) -> None:
        """Fold one observed airing into the ad's duration estimate."""
        record = self._store.get(ad_id)
        if record is None:
            return
        n = record.sample_count
        updated = (record.duration_s * n + duration_s) / (n + 1)
        self._store.update_duration(ad_id, updated, n + 1)
        log.info(
            "ad %d duration %.1fs -> %.1fs (n=%d)", ad_id, record.duration_s, updated, n + 1
        )

    def _find_existing(self, video: list[tuple[float, int]]) -> int | None:
        """Ad already in the store whose hashes these are, if any."""
        hits: dict[int, int] = {}
        index = self._store.video_hashes()
        if not index:
            return None
        for _, new_hash in video:
            best_ad: int | None = None
            best_distance = self._cfg.hamming_threshold + 1
            for ad_id, _, stored_hash in index:
                distance = hamming(new_hash, stored_hash)
                if distance < best_distance:
                    best_ad, best_distance = ad_id, distance
            if best_ad is not None:
                hits[best_ad] = hits.get(best_ad, 0) + 1
        for ad_id, count in hits.items():
            if count >= self._cfg.confirm_hits:
                return ad_id
        return None
