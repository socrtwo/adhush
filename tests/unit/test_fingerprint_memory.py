"""Store, matcher, and learner behavior over an in-memory SQLite store."""

import numpy as np
import pytest

from adhush.config import FingerprintConfig
from adhush.fingerprint import open_fingerprints
from adhush.fingerprint.matcher import Matcher
from adhush.fingerprint.store import FingerprintStore
from adhush.fingerprint.video_phash import phash

CFG = FingerprintConfig(
    store=":memory:", hamming_threshold=10, confirm_hits=3, snap_min_samples=3
)


def _hash(seed: int) -> int:
    frame = np.random.default_rng(seed).integers(0, 256, (48, 64), dtype=np.uint8)
    return phash(frame)


AD_HASHES = [(0.5 * i, _hash(i)) for i in range(6)]
AD_AUDIO = [(0.5 * i, 0b101010101010 ^ i) for i in range(6)]


class TestStore:
    def test_round_trip(self) -> None:
        store = FingerprintStore(":memory:")
        ad_id = store.add_ad(31.2, AD_HASHES, AD_AUDIO, now=100.0)
        record = store.get(ad_id)
        assert record is not None
        assert record.duration_s == pytest.approx(31.2)
        assert record.sample_count == 1
        assert len(store.video_hashes()) == 6
        assert store.audio_blocks(ad_id) == AD_AUDIO
        store.update_duration(ad_id, 30.0, 2, now=200.0)
        updated = store.get(ad_id)
        assert updated is not None and updated.sample_count == 2

    def test_prune_ttl_cascades(self) -> None:
        store = FingerprintStore(":memory:")
        old = store.add_ad(30.0, AD_HASHES, [], now=0.0)
        fresh = store.add_ad(15.0, [(0.0, _hash(50))], [], now=100 * 86400.0)
        assert store.prune(ttl_days=30.0, now=100 * 86400.0) == 1
        assert store.get(old) is None
        assert store.get(fresh) is not None
        assert all(ad_id == fresh for ad_id, _, _ in store.video_hashes())

    def test_persists_to_disk(self, tmp_path) -> None:
        path = tmp_path / "ads.sqlite"
        store = FingerprintStore(path)
        store.add_ad(30.0, AD_HASHES, [], now=0.0)
        store.close()
        assert FingerprintStore(path).count() == 1


def _store_with_ad(duration: float = 32.0) -> tuple[FingerprintStore, int]:
    store = FingerprintStore(":memory:")
    ad_id = store.add_ad(duration, AD_HASHES, AD_AUDIO, now=0.0)
    return store, ad_id


class TestMatcher:
    def test_empty_store_never_matches(self) -> None:
        matcher = Matcher(FingerprintStore(":memory:"), CFG)
        assert matcher.feed(1.0, _hash(1)) is None

    def test_confirms_after_consecutive_hits(self) -> None:
        store, ad_id = _store_with_ad()
        matcher = Matcher(store, CFG)
        assert matcher.feed(100.0, AD_HASHES[0][1]) is None
        assert matcher.feed(100.5, AD_HASHES[1][1]) is None
        match = matcher.feed(101.0, AD_HASHES[2][1])
        assert match is not None
        assert match.ad_id == ad_id
        assert match.est_start_ts == pytest.approx(100.0)
        # One observation: the slot snap overrides 32 s -> 30 s.
        assert match.duration_s == pytest.approx(30.0)
        assert match.expected_end_ts == pytest.approx(130.0)

    def test_far_hash_resets_streak(self) -> None:
        store, _ = _store_with_ad()
        matcher = Matcher(store, CFG)
        matcher.feed(100.0, AD_HASHES[0][1])
        matcher.feed(100.5, AD_HASHES[1][1])
        assert matcher.feed(101.0, _hash(999)) is None  # unrelated content
        assert matcher.feed(101.5, AD_HASHES[2][1]) is None  # streak restarted

    def test_learned_duration_wins_with_enough_samples(self) -> None:
        store, ad_id = _store_with_ad()
        store.update_duration(ad_id, 32.0, CFG.snap_min_samples)
        matcher = Matcher(store, CFG)
        matcher.refresh()
        assert matcher.effective_duration(ad_id) == pytest.approx(32.0)

    def test_audio_corroboration_scores_alignment(self) -> None:
        store, ad_id = _store_with_ad()
        matcher = Matcher(store, CFG)
        live_good = [(200.0 + off, bits) for off, bits in AD_AUDIO]
        assert matcher.corroborate(ad_id, 200.0, live_good) == pytest.approx(1.0)
        live_bad = [(200.0 + off, bits ^ 0xFFF) for off, bits in AD_AUDIO]
        assert matcher.corroborate(ad_id, 200.0, live_bad) == pytest.approx(0.0)


class TestLearner:
    def test_learn_then_match(self) -> None:
        _store, matcher, learner = open_fingerprints(CFG, ":memory:")
        samples = [(50.0 + off, h) for off, h in AD_HASHES]
        ad_id = learner.learn_segment(50.0, 30.5, samples, [])
        assert ad_id is not None
        matcher.refresh()
        assert matcher.feed(300.0, AD_HASHES[0][1]) is None
        assert matcher.feed(300.5, AD_HASHES[1][1]) is None
        assert matcher.feed(301.0, AD_HASHES[2][1]) is not None

    def test_out_of_bounds_duration_skipped(self) -> None:
        _store, _matcher, learner = open_fingerprints(CFG, ":memory:")
        samples = [(0.0 + off, h) for off, h in AD_HASHES]
        assert learner.learn_segment(0.0, 3.0, samples, []) is None  # too short
        assert learner.learn_segment(0.0, 500.0, samples, []) is None  # too long

    def test_duplicate_segment_updates_instead_of_inserting(self) -> None:
        store, _matcher, learner = open_fingerprints(CFG, ":memory:")
        samples = [(10.0 + off, h) for off, h in AD_HASHES]
        first = learner.learn_segment(10.0, 30.0, samples, [])
        assert first is not None
        again = [(400.0 + off, h) for off, h in AD_HASHES]
        second = learner.learn_segment(400.0, 34.0, again, [])
        assert second == first
        assert store.count() == 1
        record = store.get(first)
        assert record is not None
        assert record.sample_count == 2
        assert record.duration_s == pytest.approx(32.0)  # mean of 30 and 34

    def test_early_unmute_shortens_duration(self) -> None:
        store, _matcher, learner = open_fingerprints(CFG, ":memory:")
        samples = [(10.0 + off, h) for off, h in AD_HASHES]
        ad_id = learner.learn_segment(10.0, 60.0, samples, [])
        assert ad_id is not None
        learner.observe_duration(ad_id, 30.0)  # unmuted early on a later airing
        record = store.get(ad_id)
        assert record is not None
        assert record.duration_s == pytest.approx(45.0)
        assert record.sample_count == 2
