"""Pipeline driver: capture -> detectors -> fusion -> state -> controller.

Owns the wiring and cadence of the loop in both modes — deterministic offline
replay (events merged by media timestamp; what CI runs) and live capture (one
decode thread, one audio thread, decisions on the consumer loop, per
docs/architecture.md) — plus onset evaluation of its own transition log, which
is how replay results are scored against labeled ground truth. See
docs/adr/0004-engine-module-owns-pipeline-wiring.md.
"""

from __future__ import annotations

import heapq
import logging
import queue
import threading
from collections.abc import Iterator
from dataclasses import dataclass

from adhush.capture.base import CaptureSource
from adhush.control.base import ControlError, MuteController
from adhush.detect.base import Detector
from adhush.detect.fingerprint import FingerprintDetector
from adhush.detect.fusion import Fusion
from adhush.detect.logo_absence import LogoAbsenceDetector
from adhush.events import AdSegment, AudioEvent, FrameEvent
from adhush.fingerprint.learner import Learner
from adhush.fingerprint.matcher import Match, Matcher
from adhush.state import Action, AdState, AdStateMachine

log = logging.getLogger(__name__)

# How often fused decisions are made, in media time. Detector observation
# happens on every event; this only caps decision/actuation frequency.
_DECISION_INTERVAL_S = 0.1


@dataclass(frozen=True, slots=True)
class Transition:
    ts: float
    action: Action
    confidence: float
    reasons: tuple[str, ...]


class Pipeline:
    def __init__(
        self,
        detectors: list[Detector],
        fusion: Fusion,
        machine: AdStateMachine,
        controller: MuteController,
        *,
        learner: Learner | None = None,
        matcher: Matcher | None = None,
    ) -> None:
        self._detectors = detectors
        self._fusion = fusion
        self._machine = machine
        self._controller = controller
        self._learner = learner
        self._matcher = matcher
        self._fp = next(
            (d for d in detectors if isinstance(d, FingerprintDetector)), None
        )
        self._logos = [d for d in detectors if isinstance(d, LogoAbsenceDetector)]
        self._next_decision_ts: float | None = None
        self._ad_start_est: float | None = None
        self._mute_match: Match | None = None
        self.transitions: list[Transition] = []

    def warmup(self) -> None:
        for detector in self._detectors:
            detector.warmup()
        self._fusion.reset()
        self._next_decision_ts = None
        self._ad_start_est = None
        self._mute_match = None
        self.transitions = []

    def process(self, event: FrameEvent | AudioEvent) -> None:
        if isinstance(event, FrameEvent):
            for detector in self._detectors:
                if detector.needs_video:
                    detector.observe_frame(event)
        else:
            for detector in self._detectors:
                if detector.needs_audio:
                    detector.observe_audio(event)

        if self._next_decision_ts is None:
            self._next_decision_ts = event.ts
        if event.ts < self._next_decision_ts:
            return
        self._next_decision_ts = event.ts + _DECISION_INTERVAL_S
        self._decide(event.ts)

    def _decide(self, ts: float) -> None:
        votes = [d.vote(ts) for d in self._detectors]
        decision = self._fusion.combine(votes, ts)

        match = self._fp.active_match(ts) if self._fp is not None else None
        fp_hold = match is not None and ts < match.expected_end_ts
        promote = fp_hold and self._machine.state in (AdState.PROGRAM, AdState.SUSPECT_AD)
        program_evidence = any(d.program_present for d in self._logos)

        action = self._machine.update(
            decision, promote=promote, fp_hold=fp_hold, program_evidence=program_evidence
        )
        if action is None:
            return

        reasons = decision.reasons
        if action is Action.MUTE:
            if promote and match is not None:
                self._mute_match = match
                self._ad_start_est = match.est_start_ts
                reasons = (
                    f"fingerprint:promote ad={match.ad_id} dur={match.duration_s:.0f}",
                    *reasons,
                )
            else:
                self._mute_match = None
                self._ad_start_est = ts - self._machine.mute_dwell_s
        self.transitions.append(
            Transition(
                ts=ts, action=action, confidence=decision.confidence, reasons=reasons
            )
        )
        log.info("%s at ts=%.2f conf=%.2f", action.value, ts, decision.confidence)
        try:
            if action is Action.MUTE:
                self._controller.mute()
            else:
                self._controller.unmute()
        except ControlError:
            # A failed unmute is the dangerous direction; the next decision
            # cycle retries because the state machine has already left AD.
            log.exception("controller failed on %s", action.value)
        if action is Action.UNMUTE:
            self._finish_ad(ts)

    def _finish_ad(self, ts: float) -> None:
        """Feed the just-ended ad segment back into the fingerprint memory."""
        start = self._ad_start_est
        match, self._mute_match, self._ad_start_est = self._mute_match, None, None
        if self._learner is None or self._fp is None or start is None:
            return
        duration = ts - start
        if match is not None:
            # Known ad: fold this airing's duration in. An early unmute
            # (program evidence inside the window) shortens the estimate.
            self._fp.abort_match()
            self._learner.observe_duration(match.ad_id, duration)
        else:
            learned = self._learner.learn_segment(
                start,
                duration,
                self._fp.video_between(start, ts),
                self._fp.audio_between(start, ts),
            )
            if learned is not None and self._matcher is not None:
                self._matcher.refresh()


def _merged_offline(source: CaptureSource) -> Iterator[FrameEvent | AudioEvent]:
    return heapq.merge(source.frames(), source.audio_blocks(), key=lambda e: e.ts)


def run_offline(source: CaptureSource, pipeline: Pipeline) -> list[Transition]:
    """Drive the pipeline from a deterministic (file_replay) source."""
    pipeline.warmup()
    for event in _merged_offline(source):
        pipeline.process(event)
    return pipeline.transitions


def run_live(
    source: CaptureSource,
    pipeline: Pipeline,
    stop: threading.Event,
    *,
    queue_size: int = 64,
) -> None:
    """Drive the pipeline from a live source until ``stop`` is set."""
    pipeline.warmup()
    events: queue.Queue[FrameEvent | AudioEvent | None] = queue.Queue(maxsize=queue_size)

    def _pump(stream: Iterator[FrameEvent | AudioEvent]) -> None:
        try:
            for event in stream:
                if stop.is_set():
                    return
                try:
                    events.put(event, timeout=1.0)
                except queue.Full:
                    log.warning("event queue full; dropping (detectors falling behind)")
        finally:
            events.put(None)

    caps = source.caps()
    threads = []
    live_streams = int(caps.video) + int(caps.audio)
    if caps.video:
        threads.append(threading.Thread(target=_pump, args=(source.frames(),), daemon=True))
    if caps.audio:
        threads.append(threading.Thread(target=_pump, args=(source.audio_blocks(),), daemon=True))
    for thread in threads:
        thread.start()

    ended = 0
    while not stop.is_set() and ended < live_streams:
        try:
            event = events.get(timeout=0.5)
        except queue.Empty:
            continue
        if event is None:
            ended += 1
            continue
        pipeline.process(event)


@dataclass(frozen=True, slots=True)
class OnsetScore:
    precision: float
    recall: float
    matched: int
    predicted: int
    labeled: int


def _score(predicted: list[float], labeled: list[float], tolerance_s: float) -> OnsetScore:
    remaining = sorted(labeled)
    matched = 0
    for ts in sorted(predicted):
        for i, truth in enumerate(remaining):
            if abs(ts - truth) <= tolerance_s:
                matched += 1
                remaining.pop(i)
                break
    return OnsetScore(
        precision=matched / len(predicted) if predicted else 1.0,
        recall=matched / len(labeled) if labeled else 1.0,
        matched=matched,
        predicted=len(predicted),
        labeled=len(labeled),
    )


def evaluate_onsets(
    transitions: list[Transition],
    segments: list[AdSegment],
    *,
    mute_tolerance_s: float,
    unmute_tolerance_s: float,
) -> tuple[OnsetScore, OnsetScore]:
    """Score mute-onset and unmute-onset separately against labeled segments.

    They are reported separately because they fail differently: a late mute is
    an annoyance, a late unmute eats program audio and is the worse defect —
    callers should hold unmute to the tighter tolerance.
    """
    mutes = [t.ts for t in transitions if t.action is Action.MUTE]
    unmutes = [t.ts for t in transitions if t.action is Action.UNMUTE]
    ad_starts = [s.start_ts for s in segments]
    ad_ends = [s.start_ts + s.duration_s for s in segments]
    return (
        _score(mutes, ad_starts, mute_tolerance_s),
        _score(unmutes, ad_ends, unmute_tolerance_s),
    )
