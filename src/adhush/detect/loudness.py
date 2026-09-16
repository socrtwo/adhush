"""EBU R128 short-term LUFS delta vs rolling program baseline; DRC-aware.

K-weighting is applied in the frequency domain per audio block (a numpy-only
approximation of the R128 pre-filter: RLB high-pass at 38 Hz plus a +4 dB
high shelf). Absolute values track LUFS closely enough for deltas, which is
all fusion consumes. The program baseline is a slow EMA that freezes while
loudness is elevated, so a long hot ad pod cannot drag the baseline up — and
a set with aggressive DRC simply yields small deltas rather than false ones.

Two guards learned from a phone in a living room (2026-09-09): the baseline is
not taken until a whole window of un-gated audio has been heard, because the
first short-term values — a window still half full of the microphone's start-up
silence — read several dB low and froze the baseline there for good; and an
elevation that outlasts any ad pod (``max_elevated_s``) unfreezes the baseline,
so a wrong one recovers instead of voting "ad" forever.

Duck compensation (ADR 0016, after admuffs): a room microphone hears the
set get quieter the moment the engine ducks it, and would report that quiet
as "program resumed" — the mute paradox. When told of a duck, the detector
freezes its vote for one window, measures how much the room actually dropped,
and adds that back to every later reading so the ducked ad is judged on the
original scale. If the ducked set is buried under the room (the level sits
near the silence gate, or what the microphone hears is spectrally flat noise —
fans, not a broadcast), no offset can recover it: the detector goes inert
until the volume is back, and says so in its reason.

Crest factor (ADR 0021, after beepscore's analysis): commercials are
mastered with heavy compression, so their peak-to-RMS ratio sits several dB
under the programme's even when their loudness does not. The detector keeps
the crest factor over the same short-term window against the same slow
baseline, and a drop adds up to half a vote — never a whole one, so a music
bed inside the programme cannot mute by itself, but a spot mixed no hotter
than the show still shows.
"""

from __future__ import annotations

import math
from collections import deque
from typing import ClassVar

import numpy as np
import numpy.typing as npt

from adhush.config import LoudnessConfig
from adhush.detect.base import Detector
from adhush.detect.silence import spectral_flatness
from adhush.events import AudioEvent, DetectorVote

_HP_HZ = 38.0  # RLB high-pass corner
_SHELF_HZ = 1500.0  # high-shelf corner
_SHELF_GAIN = 1.505  # 10**(3.99/10) - 1: +4 dB shelf in power terms
_SILENCE_GATE_LUFS = -55.0  # blocks below this never move baseline or vote
# Confidence reaches 1.0 at this multiple of the configured delta threshold.
_FULL_CONF_FACTOR = 1.4
# Duck compensation: wait one window plus this before measuring the drop.
_DUCK_SETTLE_MARGIN_S = 1.0
_MAX_DUCK_OFFSET_DB = 30.0
# A ducked set is "buried" when its level is within this of the silence gate…
_DUCK_HEADROOM_DB = 6.0
# …or the audio heard during the settle window is flat noise (the silence
# detector's own test for "room, not broadcast").
_BURIED_FLATNESS = 0.2
# A full crest-factor drop is worth this much of a vote (ADR 0021).
_CREST_SHARE = 0.5


def _k_weights(n_samples: int, rate: int) -> npt.NDArray[np.float64]:
    freqs = np.fft.rfftfreq(n_samples, d=1.0 / rate)
    f2 = np.square(freqs)
    highpass = np.square(f2) / (np.square(f2) + _HP_HZ**4)
    shelf = 1.0 + _SHELF_GAIN * f2 / (f2 + _SHELF_HZ**2)
    return np.asarray(highpass * shelf, dtype=np.float64)


class LoudnessDetector(Detector):
    name: ClassVar[str] = "loudness"
    needs_audio: ClassVar[bool] = True

    def __init__(self, config: LoudnessConfig) -> None:
        self._cfg = config
        self._weights: npt.NDArray[np.float64] | None = None
        self._weights_key: tuple[int, int] | None = None
        # (duration_s, weighted mean-square, crest_db) blocks covering the short-term window
        self._window: deque[tuple[float, float, float]] = deque()
        self._window_dur = 0.0
        self._baseline_lufs: float | None = None
        self._baseline_crest: float | None = None
        self._last_crest = 0.0
        self._observed_s = 0.0
        self._ungated_s = 0.0  # consecutive seconds with the short-term value above the gate
        self._elevated_s = 0.0  # consecutive seconds spent above baseline + delta/2
        self._last_short_term = -70.0
        self._reset_duck()

    def _reset_duck(self) -> None:
        self._ducked = False
        self._offset_db = 0.0  # added to every reading while ducked (ADR 0016)
        self._pre_duck_lufs: float | None = None
        self._settle_flatness: list[float] = []
        self._settle_until: float | None = None
        self._frozen: tuple[float, str] | None = None
        self._buried = False
        self._skip_baseline_until = -math.inf

    def warmup(self) -> None:
        self._window.clear()
        self._window_dur = 0.0
        self._baseline_lufs = None
        self._baseline_crest = None
        self._last_crest = 0.0
        self._observed_s = 0.0
        self._ungated_s = 0.0
        self._elevated_s = 0.0
        self._last_short_term = -70.0
        self._reset_duck()

    @property
    def voting(self) -> bool:
        return not self._buried

    @property
    def duck_offset_db(self) -> float:
        return self._offset_db

    def audio_ducked(self, ts: float, ducked: bool) -> None:
        if not ducked:
            self._reset_duck()
            # The window still holds ducked audio: keep it out of the baseline.
            self._skip_baseline_until = ts + self._cfg.window_s
            return
        if self._ducked:
            return
        self._ducked = True
        if not self._warm or self._last_short_term <= _SILENCE_GATE_LUFS:
            # Nothing to measure against: the set was silent, or we are too new.
            self._buried = True
            return
        self._pre_duck_lufs = self._last_short_term
        vote = self.vote(ts)
        self._frozen = (vote.confidence, vote.reason)
        self._settle_until = ts + self._cfg.window_s + _DUCK_SETTLE_MARGIN_S

    def _settle(self, raw: float) -> None:
        """One window after the duck: measure the drop, or give up."""
        assert self._pre_duck_lufs is not None
        self._settle_until = None
        self._frozen = None
        flat = self._settle_flatness
        self._settle_flatness = []
        noise = bool(flat) and sum(flat) / len(flat) >= _BURIED_FLATNESS
        if raw <= _SILENCE_GATE_LUFS + _DUCK_HEADROOM_DB or noise:
            self._buried = True
            return
        self._offset_db = max(0.0, min(_MAX_DUCK_OFFSET_DB, self._pre_duck_lufs - raw))

    @property
    def baseline_lufs(self) -> float | None:
        return self._baseline_lufs

    @property
    def crest_db(self) -> float:
        """Peak-to-RMS ratio over the short-term window, in dB."""
        return self._last_crest

    @property
    def baseline_crest_db(self) -> float | None:
        return self._baseline_crest

    @staticmethod
    def block_crest_db(samples: npt.NDArray[np.float32]) -> float:
        x = samples.astype(np.float64)
        rms = float(np.sqrt(np.mean(np.square(x)))) if len(x) else 0.0
        if rms <= 0.0:
            return 0.0
        return 20.0 * math.log10(float(np.max(np.abs(x))) / rms)

    @property
    def _warm(self) -> bool:
        # Baseline needs several windows of program before deltas mean anything.
        return self._baseline_lufs is not None and self._observed_s >= 4 * self._cfg.window_s

    def _weighted_ms(self, samples: npt.NDArray[np.float32], rate: int) -> float:
        key = (len(samples), rate)
        if self._weights_key != key:
            self._weights = _k_weights(*key)
            self._weights_key = key
        assert self._weights is not None
        spectrum = np.abs(np.fft.rfft(samples.astype(np.float64))) ** 2
        n = len(samples)
        # Parseval: sum(x²) == (|X0|² + 2·sum(|Xk|²) + |X_nyq|²) / n
        doubled = np.full(spectrum.shape, 2.0)
        doubled[0] = 1.0
        if n % 2 == 0:
            doubled[-1] = 1.0
        return float(np.sum(spectrum * doubled * self._weights) / (n * n))

    def observe_audio(self, event: AudioEvent) -> None:
        ms = self._weighted_ms(event.samples, event.sample_rate)
        self._window.append((event.duration, ms, self.block_crest_db(event.samples)))
        self._window_dur += event.duration
        while self._window_dur > self._cfg.window_s and len(self._window) > 1:
            dur, _, _ = self._window.popleft()
            self._window_dur -= dur
        self._observed_s += event.duration

        total = sum(d * m for d, m, _ in self._window)
        mean_ms = total / self._window_dur if self._window_dur > 0 else 0.0
        if self._window_dur > 0:
            self._last_crest = sum(d * c for d, _, c in self._window) / self._window_dur
        raw = -70.0 if mean_ms <= 0.0 else -0.691 + 10.0 * math.log10(mean_ms)
        now = event.ts + event.duration
        if self._settle_until is not None:
            self._settle_flatness.append(spectral_flatness(event.samples))
            if now >= self._settle_until:
                self._settle(raw)
        self._last_short_term = raw + self._offset_db
        if mean_ms <= 0.0:
            return

        if self._last_short_term <= _SILENCE_GATE_LUFS:
            self._ungated_s = 0.0
            return
        self._ungated_s += event.duration
        if self._settle_until is not None or now < self._skip_baseline_until:
            return  # a window straddling a volume change must not move the baseline
        if self._baseline_lufs is None:
            # Only once the whole window is un-gated program: a window still
            # filling with start-up silence reads low and would freeze it there.
            if self._ungated_s >= self._cfg.window_s:
                self._baseline_lufs = self._last_short_term
                self._baseline_crest = self._last_crest
            return
        # Freeze the baseline while loudness is elevated (suspected ad) — unless
        # it has been elevated longer than any ad pod, which means the baseline
        # itself is wrong and must be allowed to follow.
        if self._last_short_term - self._baseline_lufs > self._cfg.delta_lufs / 2:
            self._elevated_s += event.duration
            if self._elevated_s < self._cfg.max_elevated_s:
                return
        else:
            self._elevated_s = 0.0
        alpha = min(1.0, event.duration / self._cfg.baseline_s)
        self._baseline_lufs += alpha * (self._last_short_term - self._baseline_lufs)
        if self._baseline_crest is not None:
            self._baseline_crest += alpha * (self._last_crest - self._baseline_crest)

    def vote(self, ts: float) -> DetectorVote:
        if self._frozen is not None:
            confidence, reason = self._frozen
            return self._vote(ts, confidence, f"duck_settling {reason}")
        if self._buried:
            return self._vote(ts, 0.0, f"ducked_buried st_lufs={self._last_short_term:.1f}")
        if not self._warm:
            return self._vote(ts, 0.0, f"warming observed_s={self._observed_s:.1f}")
        assert self._baseline_lufs is not None
        if self._last_short_term <= _SILENCE_GATE_LUFS:
            return self._vote(ts, 0.0, f"gated st_lufs={self._last_short_term:.1f}")
        delta = self._last_short_term - self._baseline_lufs
        confidence = max(0.0, min(1.0, delta / (self._cfg.delta_lufs * _FULL_CONF_FACTOR)))
        crest = ""
        if self._cfg.crest_drop_db > 0.0 and self._baseline_crest is not None:
            drop = self._baseline_crest - self._last_crest
            crest_conf = max(0.0, min(1.0, drop / (self._cfg.crest_drop_db * _FULL_CONF_FACTOR)))
            confidence = min(1.0, confidence + _CREST_SHARE * crest_conf)
            crest = f" crest_db={self._last_crest:.1f} crest_drop_db={drop:.1f}"
        duck = f" duck_offset_db={self._offset_db:.1f}" if self._ducked else ""
        return self._vote(
            ts,
            confidence,
            f"loudness delta_lufs={delta:.2f} st_lufs={self._last_short_term:.1f}"
            f" baseline_lufs={self._baseline_lufs:.1f}{crest}{duck}",
        )
