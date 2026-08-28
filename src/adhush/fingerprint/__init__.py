"""Fingerprint subsystem exports."""

from __future__ import annotations

from adhush.config import FingerprintConfig
from adhush.fingerprint.learner import Learner
from adhush.fingerprint.matcher import Match, Matcher
from adhush.fingerprint.store import FingerprintStore

__all__ = ["FingerprintStore", "Learner", "Match", "Matcher", "open_fingerprints"]


def open_fingerprints(
    config: FingerprintConfig, store_path: str | None = None
) -> tuple[FingerprintStore, Matcher, Learner]:
    """Open the store and build the matcher/learner pair over it."""
    store = FingerprintStore(store_path if store_path is not None else config.store)
    return store, Matcher(store, config), Learner(store, config)
