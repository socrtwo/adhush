"""EBU R128 short-term LUFS delta vs rolling program baseline; DRC-aware.

K-weighting is applied in the frequency domain per audio block (a numpy-only
approximation of the R128 pre-filter: RLB high-pass at 38 Hz plus a +4 dB
high shelf). Absolute values track LUFS closely enough for deltas, which is
all fusion consumes. The program baseline is a slow EMA that freezes while
loudness is elevated, so a long hot ad pod cannot drag the baseline up — and
a set with aggressive DRC simply yields small deltas rather than false ones.
"""

from __future__ import annotations

import math
from collections import deque
from typing import ClassVar

import numpy as np
import numpy.typing as npt

from adhush.config import LoudnessConfig
from adhush.detect.base import Detector
from adhush.events import AudioEvent, DetectorVote

_HP_HZ = 38.0  # RLB high-pass corner
_SHELF_HZ = 1500.0  # high-shelf corner
_SHELF_GAIN = 1.505  # 10**(3.99/10) - 1: +4 dB shelf in power terms
_SILENCE_GATE_LUFS = -55.0  # blocks below this never move baseline or vote
# Confidence reaches 1.0 at this multiple of the configured delta threshold.
_FULL_CONF_FACTOR = 1.4


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
        # (duration_s, weighted mean-square) blocks covering the short-term window
        self._window: deque[tuple[float, float]] = deque()
        self._window_dur = 0.0
        self._baseline_lufs: float | None = None
        self._observed_s = 0.0
        self._last_short_term = -70.0

    def warmup(self) -> None:
        self._window.clear()
        self._window_dur = 0.0
        self._baseline_lufs = None
        self._observed_s = 0.0
        self._last_short_term = -70.0

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
        self._window.append((event.duration, ms))
        self._window_dur += event.duration
        while self._window_dur > self._cfg.window_s and len(self._window) > 1:
            dur, _ = self._window.popleft()
            self._window_dur -= dur
        self._observed_s += event.duration

        total = sum(d * m for d, m in self._window)
        mean_ms = total / self._window_dur if self._window_dur > 0 else 0.0
        if mean_ms <= 0.0:
            self._last_short_term = -70.0
            return
        self._last_short_term = -0.691 + 10.0 * math.log10(mean_ms)

        if self._last_short_term <= _SILENCE_GATE_LUFS:
            return
        if self._baseline_lufs is None:
            self._baseline_lufs = self._last_short_term
            return
        # Freeze the baseline while loudness is elevated (suspected ad).
        if self._last_short_term - self._baseline_lufs > self._cfg.delta_lufs / 2:
            return
        alpha = min(1.0, event.duration / self._cfg.baseline_s)
        self._baseline_lufs += alpha * (self._last_short_term - self._baseline_lufs)

    def vote(self, ts: float) -> DetectorVote:
        if not self._warm:
            return self._vote(ts, 0.0, f"warming observed_s={self._observed_s:.1f}")
        assert self._baseline_lufs is not None
        if self._last_short_term <= _SILENCE_GATE_LUFS:
            return self._vote(ts, 0.0, f"gated st_lufs={self._last_short_term:.1f}")
        delta = self._last_short_term - self._baseline_lufs
        confidence = max(0.0, min(1.0, delta / (self._cfg.delta_lufs * _FULL_CONF_FACTOR)))
        return self._vote(
            ts,
            confidence,
            f"loudness delta_lufs={delta:.2f} st_lufs={self._last_short_term:.1f}"
            f" baseline_lufs={self._baseline_lufs:.1f}",
        )
