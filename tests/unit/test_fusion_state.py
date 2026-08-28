from adhush.config import FusionConfig
from adhush.detect.fusion import Fusion
from adhush.events import DetectorVote, MuteDecision
from adhush.state import Action, AdState, AdStateMachine

CFG = FusionConfig()  # example defaults: 0.72 / 0.45, 900 ms / 400 ms, 240 s
PHASE1 = ["black_frame", "silence", "loudness"]


def _vote(name: str, conf: float, ts: float = 0.0) -> DetectorVote:
    return DetectorVote(detector=name, ts=ts, confidence=conf, reason="test")


class TestFusion:
    def test_lone_detector_cannot_reach_mute_threshold(self) -> None:
        fusion = Fusion(CFG, {}, PHASE1)
        votes = [_vote("black_frame", 1.0), _vote("silence", 0.0), _vote("loudness", 0.0)]
        decision = fusion.combine(votes, 0.0)
        assert decision.confidence <= 0.5 < CFG.mute_confidence
        assert not decision.mute

    def test_lone_detector_cannot_mute_in_audio_only_mode(self) -> None:
        fusion = Fusion(CFG, {}, ["silence", "loudness"])
        decision = fusion.combine([_vote("silence", 1.0), _vote("loudness", 0.0)], 0.0)
        assert decision.confidence <= 0.5
        assert not decision.mute

    def test_two_corroborating_detectors_mute(self) -> None:
        fusion = Fusion(CFG, {}, PHASE1)
        votes = [_vote("black_frame", 1.0), _vote("silence", 1.0), _vote("loudness", 0.0)]
        assert fusion.combine(votes, 0.0).mute

    def test_lone_sustained_detector_holds_existing_mute(self) -> None:
        fusion = Fusion(CFG, {}, PHASE1)
        boundary = [_vote("black_frame", 1.0), _vote("silence", 1.0), _vote("loudness", 1.0)]
        assert fusion.combine(boundary, 0.0).mute
        mid_ad = [_vote("black_frame", 0.0), _vote("silence", 0.0), _vote("loudness", 1.0)]
        decision = fusion.combine(mid_ad, 5.0)
        assert decision.mute  # 0.5 stays above unmute_confidence
        assert decision.confidence > CFG.unmute_confidence

    def test_schmitt_releases_below_unmute_threshold(self) -> None:
        fusion = Fusion(CFG, {}, PHASE1)
        fusion.combine([_vote(n, 1.0) for n in PHASE1], 0.0)
        released = fusion.combine([_vote(n, 0.0) for n in PHASE1], 1.0)
        assert not released.mute

    def test_reasons_ranked_by_confidence(self) -> None:
        fusion = Fusion(CFG, {}, PHASE1)
        votes = [_vote("loudness", 0.4), _vote("black_frame", 0.9), _vote("silence", 0.0)]
        decision = fusion.combine(votes, 0.0)
        assert decision.reasons[0].startswith("black_frame:")
        assert all(not r.startswith("silence:") for r in decision.reasons)


def _decision(mute: bool, conf: float, ts: float) -> MuteDecision:
    return MuteDecision(ts=ts, mute=mute, confidence=conf)


class TestStateMachine:
    def test_full_cycle_program_suspect_ad_recovery(self) -> None:
        machine = AdStateMachine(CFG)
        assert machine.update(_decision(True, 0.9, 0.0)) is None
        assert machine.state is AdState.SUSPECT_AD
        assert machine.update(_decision(True, 0.9, 0.5)) is None  # dwell not served
        assert machine.update(_decision(True, 0.9, 1.0)) is Action.MUTE
        assert machine.state is AdState.AD
        assert machine.update(_decision(False, 0.2, 10.0)) is None  # unmute dwell starts
        assert machine.update(_decision(False, 0.2, 10.5)) is Action.UNMUTE
        assert machine.state is AdState.RECOVERY
        assert machine.update(_decision(False, 0.1, 13.0)) is None
        assert machine.state is AdState.PROGRAM

    def test_fadeout_with_sagging_confidence_never_mutes(self) -> None:
        # Schmitt hysteresis keeps mute=True while confidence decays from a
        # lone program fade-out; AD entry must demand full confidence at
        # dwell completion.
        machine = AdStateMachine(CFG)
        machine.update(_decision(True, 0.9, 0.0))
        assert machine.update(_decision(True, 0.6, 1.0)) is None
        assert machine.update(_decision(True, 0.5, 1.5)) is None
        assert machine.state is AdState.SUSPECT_AD
        machine.update(_decision(False, 0.2, 2.0))
        assert machine.state is AdState.PROGRAM

    def test_max_mute_ceiling_forces_unmute(self) -> None:
        machine = AdStateMachine(CFG)
        machine.update(_decision(True, 0.9, 0.0))
        assert machine.update(_decision(True, 0.9, 1.0)) is Action.MUTE
        # Fusion still insists it's an ad; the ceiling must win anyway.
        assert machine.update(_decision(True, 0.9, 100.0)) is None
        assert machine.update(_decision(True, 0.9, 1.0 + CFG.max_mute_s)) is Action.UNMUTE
        assert machine.state is AdState.RECOVERY

    def test_recovery_ignores_mute_requests(self) -> None:
        machine = AdStateMachine(CFG)
        machine.update(_decision(True, 0.9, 0.0))
        machine.update(_decision(True, 0.9, 1.0))
        machine.update(_decision(False, 0.2, 5.0))
        assert machine.update(_decision(False, 0.2, 5.4)) is Action.UNMUTE
        assert machine.update(_decision(True, 0.9, 5.5)) is None
        assert machine.state is AdState.RECOVERY

    def test_suspicion_clears_without_mute(self) -> None:
        machine = AdStateMachine(CFG)
        machine.update(_decision(True, 0.8, 0.0))
        assert machine.state is AdState.SUSPECT_AD
        machine.update(_decision(False, 0.1, 0.3))
        assert machine.state is AdState.PROGRAM
