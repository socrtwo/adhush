"""Downscale, letterbox crop, ROI extraction, color-space helpers.

All helpers are numpy-only and cheap enough for the Pi 4 budget: detectors
downscale before doing anything else, and share the one decoded frame.
"""

from __future__ import annotations

import numpy as np
import numpy.typing as npt

# BT.601 luma weights for BGR channel order (what raw capture hands us).
_LUMA_BGR = np.array([0.114, 0.587, 0.299], dtype=np.float32)


def to_luma(frame: npt.NDArray[np.uint8]) -> npt.NDArray[np.uint8]:
    """Collapse a BGR frame to single-channel luma; pass luma through."""
    if frame.ndim == 2:
        return frame
    if frame.ndim == 3 and frame.shape[2] == 3:
        return (frame.astype(np.float32) @ _LUMA_BGR).astype(np.uint8)
    raise ValueError(f"unsupported frame shape {frame.shape}")


def downscale(frame: npt.NDArray[np.uint8], factor: int) -> npt.NDArray[np.uint8]:
    """Area-average downscale by an integer factor (crops any remainder).

    Deliberately integer-only: it keeps the hot path allocation-light and
    deterministic, which matters more here than resampling quality.
    """
    if factor < 1:
        raise ValueError("factor must be >= 1")
    if factor == 1:
        return frame
    h = (frame.shape[0] // factor) * factor
    w = (frame.shape[1] // factor) * factor
    cropped = frame[:h, :w]
    if cropped.ndim == 2:
        blocks = cropped.reshape(h // factor, factor, w // factor, factor)
    else:
        blocks = cropped.reshape(h // factor, factor, w // factor, factor, cropped.shape[2])
    return np.asarray(blocks.mean(axis=(1, 3)), dtype=np.uint8)


def extract_roi(
    frame: npt.NDArray[np.uint8], x: float, y: float, w: float, h: float
) -> npt.NDArray[np.uint8]:
    """Cut a normalized-coordinate ROI (all of x, y, w, h in [0, 1])."""
    for name, v in (("x", x), ("y", y), ("w", w), ("h", h)):
        if not 0.0 <= v <= 1.0:
            raise ValueError(f"roi {name}={v} outside [0, 1]")
    if x + w > 1.0 or y + h > 1.0:
        raise ValueError(f"roi extends past the frame: x+w={x + w}, y+h={y + h}")
    fh, fw = frame.shape[0], frame.shape[1]
    x0, y0 = int(x * fw), int(y * fh)
    x1, y1 = min(fw, int((x + w) * fw)), min(fh, int((y + h) * fh))
    if x1 <= x0 or y1 <= y0:
        raise ValueError("roi collapses to zero pixels at this resolution")
    return frame[y0:y1, x0:x1]


def mean_luma(frame: npt.NDArray[np.uint8]) -> float:
    """Mean luma of a frame, converting from BGR if needed."""
    return float(to_luma(frame).mean())
