"""State machine: PROGRAM, SUSPECT_AD, AD, RECOVERY. Hysteresis and dwell timers.

Consumes fusion's MuteDecision stream and decides when the set actually mutes.
The asymmetry is deliberate and lives here: entering AD requires the mute side
to dwell ``mute_dwell_ms``; leaving requires only ``unmute_dwell_ms``; and
``max_mute_s`` is a hard ceiling — the machine force-unmutes past it no matter
what fusion says, because a stuck mute is a defect while a missed mute is only
an annoyance.
"""

from __future__ import annotations

from enum import Enum

from adhush.config import FusionConfig
from adhush.events import MuteDecision
from adhush.util.timing import DwellTimer

# After a force-unmute or normal recovery, suspicion must rebuild from scratch;
# this pause absorbs controller latency and stops flapping at pod edges.
_RECOVERY_S = 2.0


class AdState(Enum):
    PROGRAM = "program"
    SUSPECT_AD = "suspect_ad"
    AD = "ad"
    RECOVERY = "recovery"


class Action(Enum):
    MUTE = "mute"
    UNMUTE = "unmute"


class AdStateMachine:
    def __init__(self, config: FusionConfig) -> None:
        self._cfg = config
        self.state = AdState.PROGRAM
        self._mute_dwell = DwellTimer(config.mute_dwell_ms / 1000.0)
        self._unmute_dwell = DwellTimer(config.unmute_dwell_ms / 1000.0)
        self._ad_entered_ts: float | None = None
        self._recovery_until: float | None = None

    def update(self, decision: MuteDecision) -> Action | None:
        """Advance on one fusion decision; returns a controller action or None."""
        now = decision.ts

        if self.state is AdState.PROGRAM:
            if decision.mute:
                self.state = AdState.SUSPECT_AD
                self._mute_dwell.reset()
                self._mute_dwell.update(True, now)
            return None

        if self.state is AdState.SUSPECT_AD:
            if not decision.mute:
                self.state = AdState.PROGRAM
                self._mute_dwell.reset()
                return None
            # Entering AD needs the dwell served AND full confidence right
            # now, not just lingering hysteresis: a lone program fade-out
            # (black + quiet) decays below mute_confidence before its dwell
            # completes and must not mute the set.
            if (
                self._mute_dwell.update(True, now)
                and decision.confidence >= self._cfg.mute_confidence
            ):
                self.state = AdState.AD
                self._ad_entered_ts = now
                self._unmute_dwell.reset()
                return Action.MUTE
            return None

        if self.state is AdState.AD:
            assert self._ad_entered_ts is not None
            if now - self._ad_entered_ts >= self._cfg.max_mute_s:
                return self._leave_ad(now)
            if self._unmute_dwell.update(not decision.mute, now):
                return self._leave_ad(now)
            return None

        # RECOVERY: stay unmuted and deaf to mute requests until the pause ends.
        assert self._recovery_until is not None
        if now >= self._recovery_until:
            self.state = AdState.PROGRAM
        return None

    def _leave_ad(self, now: float) -> Action:
        self.state = AdState.RECOVERY
        self._recovery_until = now + _RECOVERY_S
        self._ad_entered_ts = None
        self._mute_dwell.reset()
        self._unmute_dwell.reset()
        return Action.UNMUTE

    @property
    def muted(self) -> bool:
        return self.state is AdState.AD

    @property
    def ad_entered_ts(self) -> float | None:
        return self._ad_entered_ts
