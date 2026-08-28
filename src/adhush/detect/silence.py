"""Audio gaps at pod boundaries; distinguishes true silence from quiet dialogue.

True boundary silence is both very low level (dBFS floor) and spectrally flat;
quiet dialogue keeps spectral structure. Like black_frame, a qualifying run
votes 1.0 and decays briefly after it ends so fusion's dwell can catch it.
"""

from __future__ import annotations

import math
from typing import ClassVar

import numpy as np

from adhush.config import SilenceConfig
from adhush.detect.base import Detector
from adhush.events import AudioEvent, DetectorVote

_DECAY_S = 2.5
# Spectral flatness (geometric/arithmetic mean of the power spectrum) above
# which a quiet block reads as noise-floor rather than voiced content.
_FLATNESS_MIN = 0.2


def block_dbfs(samples: np.ndarray) -> float:
    """RMS level of a float32 [-1, 1] block in dBFS."""
    rms = float(np.sqrt(np.mean(np.square(samples, dtype=np.float64))))
    if rms <= 0.0:
        return -120.0
    return max(-120.0, 20.0 * math.log10(rms))


def spectral_flatness(samples: np.ndarray) -> float:
    """Flatness in [0, 1]; ~1 for white noise / digital silence, low for tones."""
    spectrum = np.abs(np.fft.rfft(samples.astype(np.float64))) ** 2
    spectrum = spectrum[1:]  # drop DC
    if spectrum.size == 0 or float(spectrum.max()) == 0.0:
        return 1.0
    spectrum = spectrum + 1e-12
    geometric = float(np.exp(np.mean(np.log(spectrum))))
    arithmetic = float(np.mean(spectrum))
    return geometric / arithmetic


class SilenceDetector(Detector):
    name: ClassVar[str] = "silence"
    needs_audio: ClassVar[bool] = True

    def __init__(self, config: SilenceConfig) -> None:
        self._cfg = config
        self._run_ms = 0.0
        self._last_dbfs = 0.0
        self._run_ended_ts: float | None = None
        self._ended_run_ms = 0.0

    def warmup(self) -> None:
        self._run_ms = 0.0
        self._last_dbfs = 0.0
        self._run_ended_ts = None
        self._ended_run_ms = 0.0

    def observe_audio(self, event: AudioEvent) -> None:
        self._last_dbfs = block_dbfs(event.samples)
        quiet = self._last_dbfs <= self._cfg.dbfs_threshold
        flat = self._last_dbfs <= -80.0 or spectral_flatness(event.samples) >= _FLATNESS_MIN
        if quiet and flat:
            self._run_ms += event.duration * 1000.0
            self._run_ended_ts = None
        else:
            if self._run_ms >= self._cfg.min_run_ms:
                self._run_ended_ts = event.ts
                self._ended_run_ms = self._run_ms
            self._run_ms = 0.0

    def vote(self, ts: float) -> DetectorVote:
        if self._run_ms >= self._cfg.min_run_ms:
            return self._vote(
                ts,
                1.0,
                f"silence_run ms={self._run_ms:.0f} dbfs={self._last_dbfs:.1f}",
            )
        if self._run_ended_ts is not None:
            age = ts - self._run_ended_ts
            if 0.0 <= age < _DECAY_S:
                confidence = 1.0 - age / _DECAY_S
                return self._vote(
                    ts,
                    confidence,
                    f"silence_ended ms={self._ended_run_ms:.0f} age_s={age:.2f}",
                )
            self._run_ended_ts = None
        return self._vote(ts, 0.0, f"no_silence dbfs={self._last_dbfs:.1f}")
