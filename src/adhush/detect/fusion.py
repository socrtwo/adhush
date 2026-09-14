"""Weighted evidence fusion across detectors; per-profile weights; produces MuteDecision.

Combined confidence is the weighted vote mass over a normalizer:

    min(1, sum(weight_i * confidence_i) / max(present_mass / 2, 0.30))

``present_mass`` is the weight of the detectors that voted this tick. A
detector that cannot see (the camera with no whole screen in view) stays
out of the vote list and so out of the normalizer: it neither adds evidence
nor dilutes it. With every detector voting the normalizer is the constant
``enabled_mass / 2``, so the score is monotone in every vote — a fading
boundary signal can only add evidence, never dilute a sustained one. Its floor terms encode the posture
from docs/detection-strategies.md: no single detector may trigger a mute
alone. A lone default-weight (0.15) detector at full confidence normalizes to
at most 0.5, below any sane mute threshold, while two corroborating detectors
saturate to 1.0. That same lone 0.5 is enough to *hold* an existing mute (it
exceeds the unmute threshold), which is what lets sustained loudness carry a
long pod after the transient boundary signals (black frame, silence) decay.

Hysteresis here is a Schmitt trigger: the muted side is entered at
``mute_confidence`` and left below ``unmute_confidence``. Dwell timing and the
mute/unmute asymmetry live in the state machine.
"""

from __future__ import annotations

from collections.abc import Iterable

from adhush.config import FusionConfig
from adhush.events import DetectorVote, MuteDecision

_DEFAULT_WEIGHT = 0.15
# Absolute normalization floor: two default-weight detectors' worth of
# agreement, so even a two-detector (audio-only) configuration needs
# corroboration to mute.
_MIN_MASS = 2 * _DEFAULT_WEIGHT
# Votes below this confidence are noise; keep them out of the reason list.
_REASON_EPS = 0.05


class Fusion:
    def __init__(
        self, config: FusionConfig, weights: dict[str, float], enabled: Iterable[str]
    ) -> None:
        self._cfg = config
        self._weights = dict(weights)
        enabled_mass = sum(self.weight_for(name) for name in enabled)
        self._norm = max(enabled_mass / 2, _MIN_MASS)
        self._muted_side = False

    def weight_for(self, detector: str) -> float:
        return self._weights.get(detector, _DEFAULT_WEIGHT)

    def combine(self, votes: list[DetectorVote], ts: float) -> MuteDecision:
        """Fuse the votes of every detector that is *voting* this tick."""
        mass = sum(self.weight_for(v.detector) * v.confidence for v in votes)
        present = sum(self.weight_for(v.detector) for v in votes)
        norm = max(present / 2, _MIN_MASS) if present > 0.0 else self._norm
        confidence = min(1.0, mass / norm)

        if self._muted_side:
            if confidence <= self._cfg.unmute_confidence:
                self._muted_side = False
        elif confidence >= self._cfg.mute_confidence:
            self._muted_side = True

        reasons = tuple(
            f"{v.detector}:{v.reason}"
            for v in sorted(votes, key=lambda v: v.confidence, reverse=True)
            if v.confidence > _REASON_EPS
        )
        return MuteDecision(ts=ts, mute=self._muted_side, confidence=confidence, reasons=reasons)

    def reset(self) -> None:
        self._muted_side = False
