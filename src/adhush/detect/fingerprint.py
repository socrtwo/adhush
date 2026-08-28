"""Match live A/V against the learned ad fingerprint store; emits known-ad hits with duration.

Samples the shared decoded frame every ``sample_interval_s`` (skipping flat
frames, whose hashes are degenerate), keeps rolling ring buffers of recent
hashes and chroma blocks, and feeds the matcher. A confirmed hit — with audio
corroboration when configured and audio is present — becomes the active match
the engine uses to promote straight to AD for the learned duration. The
rolling buffers double as the learner's source material when a fusion-driven
ad segment ends.
"""

from __future__ import annotations

from typing import ClassVar

import numpy as np
import numpy.typing as npt

from adhush.config import FingerprintConfig
from adhush.detect.base import Detector
from adhush.events import AudioEvent, DetectorVote, FrameEvent
from adhush.fingerprint.audio_chroma import chroma_bits
from adhush.fingerprint.matcher import Match, Matcher
from adhush.fingerprint.video_phash import phash
from adhush.util.imageops import downscale, to_luma
from adhush.util.ringbuffer import RingBuffer

# How much history the rolling buffers hold; must cover the learner's window
# plus fusion's mute latency with margin.
_BUFFER_S = 40.0
_HASH_DOWNSCALE = 4


class FingerprintDetector(Detector):
    name: ClassVar[str] = "fingerprint"
    needs_video: ClassVar[bool] = True

    def __init__(self, config: FingerprintConfig, matcher: Matcher) -> None:
        self._cfg = config
        self._matcher = matcher
        rate = 1.0 / config.sample_interval_s
        self._video: RingBuffer[tuple[float, int]] = RingBuffer.for_duration(_BUFFER_S, rate)
        self._audio: RingBuffer[tuple[float, int]] = RingBuffer.for_duration(_BUFFER_S, rate)
        self._next_sample_ts: float | None = None
        self._audio_pending: list[npt.NDArray[np.float32]] = []
        self._audio_pending_n = 0
        self._audio_block_start: float | None = None
        self._active: Match | None = None

    def warmup(self) -> None:
        self._video.clear()
        self._audio.clear()
        self._next_sample_ts = None
        self._audio_pending = []
        self._audio_pending_n = 0
        self._audio_block_start = None
        self._active = None
        self._matcher.reset()

    # -- observation ---------------------------------------------------------

    def observe_frame(self, event: FrameEvent) -> None:
        if self._next_sample_ts is not None and event.ts < self._next_sample_ts:
            return
        self._next_sample_ts = event.ts + self._cfg.sample_interval_s

        small = downscale(to_luma(event.frame), _HASH_DOWNSCALE)
        if float(small.std()) < self._cfg.min_frame_std:
            return  # flat frame: degenerate hash, no evidence either way
        frame_hash = phash(small)
        self._video.push((event.ts, frame_hash))

        if self._active is not None:
            if event.ts < self._active.expected_end_ts:
                return  # already matched; ride out the window
            self._active = None
            self._matcher.reset()

        match = self._matcher.feed(event.ts, frame_hash)
        if match is None:
            return
        if self._cfg.audio_corroboration:
            blocks = self.audio_between(match.est_start_ts, event.ts)
            if blocks:
                score = self._matcher.corroborate(match.ad_id, match.est_start_ts, blocks)
                if score < self._cfg.audio_min_agreement:
                    self._matcher.reset()
                    return
        self._active = match

    def observe_audio(self, event: AudioEvent) -> None:
        block_start = self._audio_block_start
        if block_start is None:
            block_start = event.ts
        self._audio_pending.append(event.samples)
        self._audio_pending_n += len(event.samples)
        block = round(self._cfg.sample_interval_s * event.sample_rate)
        while self._audio_pending_n >= block:
            samples = np.concatenate(self._audio_pending)
            head, rest = samples[:block], samples[block:]
            self._audio.push((block_start, chroma_bits(head, event.sample_rate)))
            block_start += block / event.sample_rate
            self._audio_pending = [rest] if len(rest) else []
            self._audio_pending_n = len(rest)
        self._audio_block_start = block_start

    # -- engine surface ------------------------------------------------------

    def active_match(self, ts: float) -> Match | None:
        if self._active is not None and ts >= self._active.expected_end_ts:
            self._active = None
            self._matcher.reset()
        return self._active

    def abort_match(self) -> None:
        """Engine calls this on early unmute so the match cannot re-promote."""
        self._active = None
        self._matcher.reset()

    def video_between(self, t0: float, t1: float) -> list[tuple[float, int]]:
        return [(ts, h) for ts, h in self._video if t0 <= ts <= t1]

    def audio_between(self, t0: float, t1: float) -> list[tuple[float, int]]:
        return [(ts, b) for ts, b in self._audio if t0 <= ts <= t1]

    # -- voting --------------------------------------------------------------

    def vote(self, ts: float) -> DetectorVote:
        match = self.active_match(ts)
        if match is None:
            return self._vote(ts, 0.0, "no_fp")
        return self._vote(
            ts,
            1.0,
            f"fp_hit ad={match.ad_id} dur={match.duration_s:.0f}"
            f" end={match.expected_end_ts:.1f} ham={match.hamming}",
        )
