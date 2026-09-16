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
from collections.abc import Callable, Iterator
from dataclasses import dataclass

from adhush.capture.base import CaptureSource
from adhush.control.base import ControlError, MuteController
from adhush.detect.base import Detector
from adhush.detect.clock import ClockDetector
from adhush.detect.crowd import CrowdDetector
from adhush.detect.fingerprint import FingerprintDetector
from adhush.detect.fusion import Fusion
from adhush.detect.jingle import JingleDetector
from adhush.detect.logo_absence import LogoAbsenceDetector
from adhush.detect.schedule import ScheduleDetector
from adhush.events import AdSegment, AudioEvent, CueEvent, FrameEvent, MuteDecision
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


# Pipeline event listener: (kind, payload) with kind in {"transition",
# "decision", "status"}; payloads are the engine's own dataclasses/dicts and
# the IPC layer owns serializing them.
Listener = Callable[[str, object], None]

OVERRIDE_MODES = ("auto", "mute", "unmute")


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
        hears_room: bool = False,
        wall_clock: Callable[[], float] | None = None,
    ) -> None:
        self._detectors = detectors
        self._fusion = fusion
        self._machine = machine
        self._controller = controller
        # Audio comes from a microphone in the room, so a duck changes what the
        # detectors hear; a line tap hears the broadcast whatever the set does.
        self._hears_room = hears_room
        self._learner = learner
        self._matcher = matcher
        self._fp = next(
            (d for d in detectors if isinstance(d, FingerprintDetector)), None
        )
        self._logos = [d for d in detectors if isinstance(d, LogoAbsenceDetector)]
        # The break clock needs wall time; a replay has none, so it stays inert there.
        self._wall_clock = wall_clock
        self._clocks = [d for d in detectors if isinstance(d, ClockDetector)]
        self._jingles = [d for d in detectors if isinstance(d, JingleDetector)]
        # ADR 0024: an ad-free listing vetoes mutes; the crowd hears about every real break.
        self._schedules = [d for d in detectors if isinstance(d, ScheduleDetector)]
        self._crowds = [d for d in detectors if isinstance(d, CrowdDetector)]
        self._ad_start_wall: float | None = None
        # A timed manual duck ends at this media time (ADR 0017).
        self._timed_until: float | None = None
        self._next_decision_ts: float | None = None
        self._ad_start_est: float | None = None
        self._mute_match: Match | None = None
        self.transitions: list[Transition] = []
        # IPC surface state; the lock covers everything the API threads touch.
        self._lock = threading.RLock()
        self._listeners: list[Listener] = []
        self._override = "auto"
        self._trace = False
        self._confirm_current = False
        self._reject_current = False
        self._last_ts = 0.0
        # Teach mode: "Is an ad" outside AD ducks now and holds until "Show's back".
        self._user_hold = False
        # After "Not an ad": nothing may mute until this media time.
        self._quiet_until = -1.0

    def warmup(self) -> None:
        for detector in self._detectors:
            detector.warmup()
        self._fusion.reset()
        self._next_decision_ts = None
        self._ad_start_est = None
        self._mute_match = None
        self.transitions = []

    def process(self, event: FrameEvent | AudioEvent | CueEvent) -> None:
        if isinstance(event, FrameEvent):
            for detector in self._detectors:
                if detector.needs_video or detector.wants_video:
                    detector.observe_frame(event)
        elif isinstance(event, CueEvent):
            for detector in self._detectors:
                detector.observe_cue(event)
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
        with self._lock:
            self._last_ts = ts
            if self._wall_clock is not None:
                # Wall time reaches every detector that keeps one (the break
                # clock, a schedule, the crowd); a replay has none.
                wall = self._wall_clock()
                for detector in self._detectors:
                    detector.tick(wall)
            if self._timed_until is not None and ts >= self._timed_until:
                self._end_timed()
                return
            # An inert detector (the camera with no whole screen in view, a logo
            # never yet sighted) casts no vote and leaves the normalizer alone.
            votes = [d.vote(ts) for d in self._detectors if d.voting]
            quiet = ts < self._quiet_until
            if quiet:
                # The quiet period after "Not an ad": evidence is shown, nothing acts on it.
                self._fusion.reset()
                decision = MuteDecision(
                    ts=ts, mute=False, confidence=0.0, reasons=("user:not_ad_quiet",)
                )
            else:
                decision = self._fusion.combine(votes, ts)
            if self._trace:
                self._emit("decision", decision)

            match = None if quiet else (
                self._fp.active_match(ts) if self._fp is not None else None
            )
            fp_hold = match is not None and ts < match.expected_end_ts
            promote = fp_hold and self._machine.state in (
                AdState.PROGRAM, AdState.SUSPECT_AD,
            )
            # A user hold behaves like a fingerprint hold with no program
            # evidence: only "Show's back" or the ceiling ends it. Positive
            # programme evidence comes from any detector that has it: the logo
            # visibly back, the closing sting, a rating box, a title card, an
            # in-network cue, other devices' breaks ending (Detector.program_present).
            program_evidence = not self._user_hold and any(d.program_present for d in self._detectors)
            if any(s.ad_free_now for s in self._schedules) and not self._user_hold:
                # An ad-free channel (ADR 0024): nothing may mute, whatever the detectors think.
                decision = MuteDecision(ts=ts, mute=False, confidence=0.0, reasons=("schedule:ad_free",))
                promote = False

            action = self._machine.update(
                decision,
                promote=promote,
                fp_hold=fp_hold or self._user_hold,
                program_evidence=program_evidence,
            )
            if action is None:
                return

            reasons = decision.reasons
            if action is Action.MUTE:
                self._ad_start_wall = self._wall_clock() if self._wall_clock else None
                if self._ad_start_wall is not None and self._timed_until is None:
                    for crowd in self._crowds:
                        crowd.report("start", self._ad_start_wall)
                self._machine.ceiling_s = (
                    self._clocks[0].ceiling_s(self._machine.hard_max_s)
                    if self._clocks else self._machine.hard_max_s
                )
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
            self._record(
                Transition(
                    ts=ts, action=action, confidence=decision.confidence, reasons=reasons
                )
            )
            if action is Action.UNMUTE:
                self._finish_ad(ts)

    def _record(self, transition: Transition) -> None:
        """Log a transition, notify listeners, and drive the controller.

        With a manual override active the machine keeps deciding (its record
        stays truthful) but the controller stays pinned to the override.
        """
        self.transitions.append(transition)
        log.info(
            "%s at ts=%.2f conf=%.2f",
            transition.action.value,
            transition.ts,
            transition.confidence,
        )
        self._emit("transition", transition)
        if self._override != "auto":
            return
        try:
            self._drive(transition.action is Action.MUTE)
        except ControlError:
            # A failed unmute is the dangerous direction; the next decision
            # cycle retries because the state machine has already left AD.
            log.exception("controller failed on %s", transition.action.value)

    def _drive(self, mute: bool) -> None:
        """Duck or restore the set, then tell the detectors what the room will
        hear next (ADR 0016). A failed command leaves the detectors untouched:
        the set has not changed."""
        if mute:
            self._controller.mute()
        else:
            self._controller.unmute()
        if self._hears_room:
            for detector in self._detectors:
                detector.audio_ducked(self._last_ts, mute)

    def _finish_ad(self, ts: float) -> None:
        """Feed the just-ended ad segment back into the fingerprint memory."""
        start = self._ad_start_est
        match, self._mute_match, self._ad_start_est = self._mute_match, None, None
        confirm, self._confirm_current = self._confirm_current, False
        reject, self._reject_current = self._reject_current, False
        self._user_hold = False
        wall_start, self._ad_start_wall = self._ad_start_wall, None
        # The break clock and the jingle detector learn every real break (a timed duck sets reject).
        if not reject and wall_start is not None and self._wall_clock is not None:
            for clock in self._clocks:
                clock.learn(wall_start, self._wall_clock())
            for crowd in self._crowds:
                crowd.report("end", self._wall_clock())
        if not reject and start is not None:
            for jingle in self._jingles:
                jingle.learn_break(start, ts)
        if self._learner is None or self._fp is None or start is None or reject:
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
                force=confirm,
            )
            if learned is not None and self._matcher is not None:
                self._matcher.refresh()

    # -- IPC surface (called from API server threads) ------------------------

    def add_listener(self, listener: Listener) -> None:
        with self._lock:
            self._listeners.append(listener)

    def remove_listener(self, listener: Listener) -> None:
        with self._lock:
            if listener in self._listeners:
                self._listeners.remove(listener)

    def _emit(self, kind: str, payload: object) -> None:
        for listener in list(self._listeners):
            try:
                listener(kind, payload)
            except Exception:  # a broken client must never stop the loop
                log.exception("pipeline listener failed")

    def status(self) -> dict[str, object]:
        with self._lock:
            last = self.transitions[-1] if self.transitions else None
            return {
                "state": self._machine.state.value,
                "muted": self._machine.muted,
                "override": self._override,
                "teaching": self._user_hold and self._timed_until is None,
                "timed_s": max(0.0, self._timed_until - self._last_ts)
                if self._timed_until is not None
                else 0.0,
                "clock": self._clocks[0].describe() if self._clocks else None,
                "jingles": self._jingles[0].describe() if self._jingles else None,
                "break_left_s": self._break_left(),
                "quiet_s": max(0.0, self._quiet_until - self._last_ts),
                "camera": self._logos[0].describe(self._last_ts) if self._logos else None,
                "schedule": self._schedules[0].describe() if self._schedules else None,
                "crowd": self._crowds[0].describe() if self._crowds and self._crowds[0].enabled else None,
                "witnesses": [d.name for d in self._detectors if d.program_present],
                "trace": self._trace,
                "detectors": [d.name for d in self._detectors],
                "transitions": len(self.transitions),
                "last_transition": None
                if last is None
                else {
                    "ts": last.ts,
                    "action": last.action.value,
                    "confidence": last.confidence,
                    "reasons": list(last.reasons),
                },
                "media_ts": self._last_ts,
            }

    def set_trace(self, enabled: bool) -> None:
        with self._lock:
            self._trace = enabled

    def press_key(self, key: str) -> None:
        """Press a remote-control key on the set through the control backend."""
        with self._lock:
            self._controller.send_key(key)

    def set_override(self, mode: str) -> None:
        """Pin the controller to mute/unmute, or return it to the machine."""
        if mode not in OVERRIDE_MODES:
            raise ValueError(f"override mode must be one of {OVERRIDE_MODES}")
        with self._lock:
            self._override = mode
            try:
                if mode == "mute":
                    self._drive(True)
                elif mode == "unmute":
                    self._drive(False)
                else:  # back to auto: reconcile
                    self._drive(self._machine.muted)
            except ControlError:
                log.exception("controller failed applying override %s", mode)
            self._emit("status", self.status())

    def confirm_ad(self) -> bool:
        """"✓ Is an ad". Already muted: confirm it, so it is learned even if its
        duration falls outside the usual sanity bounds. Not muted: teach mode —
        mute now and hold, whatever the detectors say, until "Show's back" or
        the ceiling; then learn everything heard in between (ADR 0009)."""
        with self._lock:
            self._confirm_current = True
            if self._machine.state is AdState.AD:
                return True
            decision = MuteDecision(
                ts=self._last_ts, mute=True, confidence=1.0, reasons=("user:confirm",)
            )
            action = self._machine.update(decision, promote=True)
            if action is None:
                self._confirm_current = False
                return False
            self._user_hold = True
            self._mute_match = None
            self._ad_start_est = self._last_ts
            self._record(
                Transition(
                    ts=self._last_ts, action=action, confidence=1.0, reasons=("user:confirm",)
                )
            )
            return True

    def duck_for(self, seconds: float) -> bool:
        """A timed manual duck (ADR 0017): mute now and restore after
        ``seconds`` whatever the detectors say in between; nothing is learned.
        Pressed while already muted, it keeps the mute for that long instead."""
        with self._lock:
            if self._override != "auto":
                return False
            if self._machine.state is AdState.AD:
                self._timed_until = self._last_ts + seconds
                self._user_hold = True
                self._emit("status", self.status())
                return True
            action = self._machine.user_mute(self._last_ts)
            if action is None:
                return False
            self._timed_until = self._last_ts + seconds
            self._user_hold = True
            self._mute_match = None
            self._ad_start_est = self._last_ts
            self._record(
                Transition(
                    ts=self._last_ts,
                    action=action,
                    confidence=1.0,
                    reasons=("user:timed", f"seconds={seconds:.0f}"),
                )
            )
            return True

    def _break_left(self) -> float | None:
        """While muted by the detectors: the clock's guess at what is left of the break."""
        if not self._machine.muted or self._timed_until is not None or not self._clocks:
            return None
        entered = self._machine.ad_entered_ts
        if entered is None:
            return None
        return self._clocks[0].remaining_s(self._last_ts - entered)

    def extend_duck(self, seconds: float) -> bool:
        """"+30 s": lengthen a running mute (timed or automatic) by ``seconds``;
        not muted, it starts a timed mute of that length."""
        with self._lock:
            if self._machine.state is not AdState.AD:
                return self.duck_for(seconds)
            base = self._timed_until if self._timed_until and self._timed_until > self._last_ts else self._last_ts
            self._timed_until = base + seconds
            self._user_hold = True
            self._emit("status", self.status())
            return True

    def _end_timed(self) -> bool:
        """The timed duck ran out (or "Show's back" ended it): restore, learn nothing."""
        self._timed_until = None
        self._reject_current = True  # skips learning in _finish_ad
        action = self._machine.cancel_ad(self._last_ts)
        if action is None:
            self._reject_current = False
            return False
        self._record(
            Transition(
                ts=self._last_ts, action=action, confidence=0.0, reasons=("user:timed_end",)
            )
        )
        self._finish_ad(self._last_ts)
        self._fusion.reset()
        return True

    def show_back(self) -> bool:
        """"▶ Show's back". In teach mode: restore and learn the bracketed
        break. On an automatic mute that overran: just restore, learning and
        forgetting nothing. Either way the detectors are told it is program."""
        with self._lock:
            if self._timed_until is not None:
                return self._end_timed()
            teaching = self._user_hold
            if not teaching:
                self._reject_current = True  # stand down: no learning
            action = self._machine.cancel_ad(self._last_ts)
            if action is None:
                self._reject_current = False
                return False
            self._record(
                Transition(
                    ts=self._last_ts,
                    action=action,
                    confidence=0.0,
                    reasons=("user:show_back" if teaching else "user:stand_down",),
                )
            )
            self._finish_ad(self._last_ts)
            for detector in self._detectors:
                detector.user_says_program(self._last_ts)
            if self._fp is not None:
                self._fp.abort_match()
            self._fusion.reset()
            return True

    def reject_ad(self) -> bool:
        """"✗ Not an ad": unmute now, skip learning, forget the fingerprint
        that caused a false match, tell every detector it was wrong, and open
        the quiet period in which nothing may mute again."""
        with self._lock:
            match = self._mute_match
            self._reject_current = True
            self._timed_until = None
            for detector in self._detectors:
                detector.user_says_program(self._last_ts)
            self._quiet_until = self._last_ts + self._machine.not_ad_quiet_s
            self._fusion.reset()
            action = self._machine.cancel_ad(self._last_ts)
            if action is not None:
                self._record(
                    Transition(
                        ts=self._last_ts,
                        action=action,
                        confidence=0.0,
                        reasons=("user:reject_ad",),
                    )
                )
                self._finish_ad(self._last_ts)
            else:
                self._reject_current = False
            if self._fp is not None:
                self._fp.abort_match()
            if match is not None:
                if self._learner is not None:
                    self._learner.forget(match.ad_id)
                if self._matcher is not None:
                    self._matcher.refresh()
            return action is not None


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
    events: queue.Queue[FrameEvent | AudioEvent | CueEvent | None] = queue.Queue(maxsize=queue_size)

    def _pump(stream: Iterator[FrameEvent | AudioEvent | CueEvent]) -> None:
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
    live_streams = int(caps.video) + int(caps.audio) + int(caps.cues)
    if caps.video:
        threads.append(threading.Thread(target=_pump, args=(source.frames(),), daemon=True))
    if caps.audio:
        threads.append(threading.Thread(target=_pump, args=(source.audio_blocks(),), daemon=True))
    if caps.cues:
        threads.append(threading.Thread(target=_pump, args=(source.cues(),), daemon=True))
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
