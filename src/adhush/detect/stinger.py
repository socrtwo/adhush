"""Segment stingers (ADR 0027): the whoosh, hit or swell a channel drops on
the cut into a segment or a break, followed by a change of level.

A news channel opens every segment with a short sound effect; a network
hits the cut to black with one. Unlike a jingle it has no tune to learn, so
this detector recognises the *shape* instead: a short burst (up to
``max_burst_s``) that is noisy rather than tonal (spectral flatness at least
``min_flatness``) and ``burst_above_db`` louder than the two seconds before
it, after which the level has moved by ``level_step_db`` from where it was
(or the burst itself was ``loud_burst_db`` above the bed). A loud noisy
programme (applause, traffic) fails the burst length; a plain level step
without a burst is loudness's business, not this one's.

The vote is 1.0 at the moment the stinger is confirmed and decays to
nothing over ``hold_s``: at default weight it tips a balance the other
detectors are already leaning towards, never ducks alone. Inert otherwise,
so it stays out of the fusion normaliser. Level judgements go quiet for
``duck_guard_s`` after our own volume change (ADR 0016: the microphone hears
the set get quieter).
"""

from __future__ import annotations

from collections import deque
from statistics import median
from typing import ClassVar

import numpy as np

from adhush.config import StingerConfig
from adhush.detect.base import Detector
from adhush.detect.silence import block_dbfs, spectral_flatness
from adhush.events import AudioEvent, DetectorVote


class StingerDetector(Detector):
    name: ClassVar[str] = "stinger"
    needs_audio: ClassVar[bool] = True

    def __init__(self, config: StingerConfig) -> None:
        self._cfg = config
        self.warmup()

    def warmup(self) -> None:
        self._pending: list[np.ndarray] = []
        self._pending_n = 0
        self._block_start: float | None = None
        self._levels: deque[float] = deque()  # pre-burst bed, one entry per block
        self._burst_start: float | None = None
        self._burst_pre = 0.0
        self._burst_peak = -120.0
        self._burst_blocks = 0
        self._post: list[float] = []
        self._hit_ts: float | None = None
        self._hit_burst_db = 0.0
        self._hit_step_db = 0.0
        self._guard_until = -1.0
        self._last_ts = 0.0
        self._count = 0

    @property
    def _pre_blocks(self) -> int:
        return max(2, int(self._cfg.pre_s / self._cfg.block_s))

    @property
    def _post_blocks(self) -> int:
        return max(1, int(self._cfg.post_s / self._cfg.block_s))

    @property
    def _max_burst_blocks(self) -> int:
        return max(1, int(self._cfg.max_burst_s / self._cfg.block_s))

    def audio_ducked(self, ts: float, ducked: bool) -> None:
        self._guard_until = ts + self._cfg.duck_guard_s
        self._reset_burst()
        self._levels.clear()  # the room's scale just changed; the bed must be re-heard

    def user_says_program(self, ts: float) -> None:
        self._hit_ts = None

    def _reset_burst(self) -> None:
        self._burst_start = None
        self._burst_blocks = 0
        self._post = []

    def observe_audio(self, event: AudioEvent) -> None:
        start = self._block_start if self._block_start is not None else event.ts
        self._pending.append(event.samples)
        self._pending_n += len(event.samples)
        need = max(1, round(self._cfg.block_s * event.sample_rate))
        while self._pending_n >= need:
            all_samples = np.concatenate(self._pending)
            head, rest = all_samples[:need], all_samples[need:]
            self._pending = [rest] if len(rest) else []
            self._pending_n = len(rest)
            self._on_block(start, block_dbfs(head), spectral_flatness(head))
            start += need / event.sample_rate
        self._block_start = start

    def _on_block(self, ts: float, dbfs: float, flatness: float) -> None:
        self._last_ts = ts
        if ts < self._guard_until:
            return
        if self._post and self._burst_start is not None:
            # After the burst: hear the new bed, then judge.
            self._post.append(dbfs)
            if len(self._post) >= self._post_blocks:
                step = median(self._post) - self._burst_pre
                above = self._burst_peak - self._burst_pre
                if abs(step) >= self._cfg.level_step_db or above >= self._cfg.loud_burst_db:
                    self._hit_ts, self._hit_burst_db, self._hit_step_db = ts, above, step
                    self._count += 1
                for level in self._post:
                    self._push_level(level)
                self._reset_burst()
            return
        if self._burst_start is not None:
            if flatness >= self._cfg.min_flatness and dbfs >= self._burst_pre + self._cfg.burst_above_db:
                self._burst_blocks += 1
                self._burst_peak = max(self._burst_peak, dbfs)
                if self._burst_blocks > self._max_burst_blocks:
                    self._reset_burst()  # too long for a stinger: loud, noisy programme
                    self._push_level(dbfs)
                return
            self._post = [dbfs]  # the burst just ended; the first post block
            return
        if len(self._levels) >= self._pre_blocks:
            pre = median(self._levels)
            if flatness >= self._cfg.min_flatness and dbfs >= pre + self._cfg.burst_above_db:
                self._burst_start, self._burst_pre, self._burst_peak, self._burst_blocks = ts, pre, dbfs, 1
                return
        self._push_level(dbfs)

    def _push_level(self, dbfs: float) -> None:
        self._levels.append(dbfs)
        while len(self._levels) > self._pre_blocks:
            self._levels.popleft()

    @property
    def voting(self) -> bool:
        return self._hit_ts is not None and self._last_ts - self._hit_ts < self._cfg.hold_s

    def vote(self, ts: float) -> DetectorVote:
        hit = self._hit_ts
        if hit is not None and ts - hit < self._cfg.hold_s:
            age = max(0.0, ts - hit)
            return self._vote(
                ts,
                max(0.0, 1.0 - age / self._cfg.hold_s),
                f"stinger burst_db=+{self._hit_burst_db:.1f} step_db={self._hit_step_db:+.1f} age_s={age:.1f}",
            )
        return self._vote(ts, 0.0, f"stinger_quiet heard={self._count}")

    def describe(self) -> str:
        return f"{self._count} stinger(s) heard" if self._count else "listening for a stinger"
