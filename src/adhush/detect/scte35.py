"""SCTE-35 cues from a transport stream (ADR 0024).

Cable and broadcast feeds carry in-band splice points: an out-of-network
cue when a local ad break begins (often with its length) and an in-network
cue when it ends. Where the stream still carries them — an HDHomeRun on a
clear-QAM or ATSC channel, a DVB dongle — they are the authority every
other detector only estimates. The capture backend hands them over as
``CueEvent``s; this detector votes 1.0 from the out cue until the in cue,
the announced duration or ``max_break_s``, whichever is first, and counts
the in cue as programme evidence for ``in_hold_s``. It may act alone, like
a fingerprint match. Inert until the first cue.
"""

from __future__ import annotations

from typing import ClassVar

from adhush.config import Scte35Config
from adhush.detect.base import Detector
from adhush.events import CueEvent, DetectorVote


class Scte35Detector(Detector):
    name: ClassVar[str] = "scte35"

    def __init__(self, config: Scte35Config) -> None:
        self._cfg = config
        self.warmup()

    def warmup(self) -> None:
        self._out_ts: float | None = None
        self._out_until = -1e18
        self._in_until = -1e18
        self._last_ts = 0.0
        self._detail = ""
        self._cues = 0

    def observe_cue(self, event: CueEvent) -> None:
        self._cues += 1
        self._last_ts = event.ts
        self._detail = event.detail
        if event.kind == "ad_start":
            self._out_ts = event.ts
            span = event.duration_s + 5.0 if event.duration_s else self._cfg.max_break_s
            self._out_until = event.ts + min(span, self._cfg.max_break_s)
            self._in_until = -1e18
        elif event.kind == "ad_end":
            self._out_ts = None
            self._out_until = -1e18
            self._in_until = event.ts + self._cfg.in_hold_s

    def user_says_program(self, ts: float) -> None:
        self._out_ts = None
        self._out_until = -1e18

    def _seen(self, ts: float) -> None:
        self._last_ts = max(self._last_ts, ts)

    @property
    def program_present(self) -> bool:
        return self._last_ts < self._in_until

    @property
    def voting(self) -> bool:
        return self._last_ts < self._out_until or self._last_ts < self._in_until

    def vote(self, ts: float) -> DetectorVote:
        self._seen(ts)
        if ts < self._out_until and self._out_ts is not None:
            return self._vote(ts, 1.0, f"scte35_out age_s={ts - self._out_ts:.1f} until_s={self._out_until - ts:.0f} {self._detail}")
        if ts < self._in_until:
            return self._vote(ts, 0.0, f"scte35_in {self._detail}")
        return self._vote(ts, 0.0, f"scte35_idle cues={self._cues}")

    def describe(self) -> str:
        if self._cues == 0:
            return "no cues yet"
        return f"{self._cues} cue(s); last {self._detail}" + (" — in a break" if self._out_ts is not None else "")
