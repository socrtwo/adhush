"""Chromaprint/landmark-style audio fingerprints; robust to volume and codec changes.

Each fixed-length block of mono audio folds its FFT power spectrum onto the
12 semitone chroma classes and binarizes each class against the median,
giving a 12-bit signature per block. Level-invariant by construction (the
median threshold), and coarse enough to survive broadcast processing.
Corroboration compares block sequences by fraction of agreeing bits.
"""

from __future__ import annotations

import numpy as np
import numpy.typing as npt

CHROMA_BITS = 12
_A4_HZ = 440.0
_FMIN = 60.0
_FMAX = 3800.0


def chroma_bits(samples: npt.NDArray[np.float32], rate: int) -> int:
    """12-bit chroma signature of one audio block."""
    if len(samples) == 0:
        return 0
    spectrum = np.abs(np.fft.rfft(samples.astype(np.float64))) ** 2
    freqs = np.fft.rfftfreq(len(samples), d=1.0 / rate)
    keep = (freqs >= _FMIN) & (freqs <= _FMAX)
    spectrum, freqs = spectrum[keep], freqs[keep]
    if spectrum.size == 0 or float(spectrum.sum()) <= 0.0:
        return 0
    semitone = np.round(12.0 * np.log2(freqs / _A4_HZ)).astype(np.int64) % 12
    energy = np.zeros(CHROMA_BITS)
    np.add.at(energy, semitone, spectrum)
    median = float(np.median(energy))
    bits = 0
    for value in energy:
        bits = (bits << 1) | (1 if value > median else 0)
    return bits


def agreement(a: list[int], b: list[int]) -> float:
    """Fraction of agreeing bits across two aligned block sequences, in [0, 1]."""
    n = min(len(a), len(b))
    if n == 0:
        return 0.0
    matching = sum(CHROMA_BITS - (x ^ y).bit_count() for x, y in zip(a[:n], b[:n]))
    return matching / (n * CHROMA_BITS)
