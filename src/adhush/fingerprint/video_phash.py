"""Perceptual hashes (dHash/pHash/wavelet) over downscaled, letterbox-cropped frames.

The default (and currently only) hash is pHash: resize luma to 32x32, 2D
DCT-II, keep the lowest 8x8 coefficients minus DC, threshold each against the
median. 63 bits packed into a Python int; similarity is Hamming distance.
Numpy-only — the DCT is a pair of 32x32 matrix multiplies, cheap enough to run
twice a second on the Pi budget.

Flat frames (black cards, plain slates) collapse to near-degenerate hashes
that cross-match everything; callers must gate hashing on ``frame_std``.
"""

from __future__ import annotations

import numpy as np
import numpy.typing as npt

from adhush.util.imageops import to_luma

_SIZE = 32
_KEEP = 8

_n = np.arange(_SIZE)
_DCT = np.cos(np.pi * np.outer(_n, _n + 0.5) / _SIZE)  # DCT-II basis, unnormalized

HASH_BITS = _KEEP * _KEEP - 1


def frame_std(frame: npt.NDArray[np.uint8]) -> float:
    """Luma standard deviation; the flatness gate for hashing."""
    return float(to_luma(frame).std())


def _resize32(luma: npt.NDArray[np.uint8]) -> npt.NDArray[np.float64]:
    h, w = luma.shape
    rows = ((np.arange(_SIZE) + 0.5) * h / _SIZE).astype(np.intp)
    cols = ((np.arange(_SIZE) + 0.5) * w / _SIZE).astype(np.intp)
    return luma[np.ix_(rows, cols)].astype(np.float64)


def phash(frame: npt.NDArray[np.uint8]) -> int:
    """63-bit perceptual hash of a luma or BGR frame."""
    small = _resize32(to_luma(frame))
    coeffs = _DCT @ small @ _DCT.T
    block = coeffs[:_KEEP, :_KEEP].flatten()[1:]  # drop DC
    median = float(np.median(block))
    bits = 0
    for value in block:
        bits = (bits << 1) | (1 if value > median else 0)
    return bits


def hamming(a: int, b: int) -> int:
    return (a ^ b).bit_count()
