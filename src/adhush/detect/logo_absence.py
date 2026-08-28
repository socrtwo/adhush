"""Network bug/logo presence in a configurable ROI; absence over N frames votes AD.

Calibration (``adhush calibrate``) averages the ROI's edge-magnitude maps over
a stretch of program known to show the logo and saves the result as a
template. At runtime each frame's ROI edge map is compared to the template by
cosine similarity restricted to the template's strong-edge mask — content
behind the translucent bug changes constantly, but the bug's edges persist.
A similarity below ``present_threshold`` counts the frame as logo-absent;
``absence_frames`` consecutive absent frames ramp the vote to 1.0. The logo
returning drops the vote to 0 immediately (presence is proof of program).

Uncalibrated, the detector is inert: it votes 0.0 with reason ``uncalibrated``
so fusion sees no evidence either way.
"""

from __future__ import annotations

from pathlib import Path
from typing import ClassVar

import numpy as np
import numpy.typing as npt

from adhush.config import LogoAbsenceConfig
from adhush.detect.base import Detector
from adhush.events import DetectorVote, FrameEvent
from adhush.util.imageops import extract_roi, to_luma

# EMA smoothing of the per-frame similarity score.
_SCORE_ALPHA = 0.3


def edge_map(roi_luma: npt.NDArray[np.uint8]) -> npt.NDArray[np.float64]:
    gy, gx = np.gradient(roi_luma.astype(np.float64))
    return np.asarray(np.hypot(gx, gy), dtype=np.float64)


def build_template(rois: list[npt.NDArray[np.uint8]]) -> npt.NDArray[np.float64]:
    """Mean edge map over calibration ROIs (logo visible throughout)."""
    if not rois:
        raise ValueError("calibration needs at least one frame")
    return np.asarray(np.mean([edge_map(to_luma(r)) for r in rois], axis=0), dtype=np.float64)


def save_template(path: Path, template: npt.NDArray[np.float64]) -> None:
    path.parent.mkdir(parents=True, exist_ok=True)
    np.savez_compressed(path, template=template)


def load_template(path: Path) -> npt.NDArray[np.float64] | None:
    if not path.is_file():
        return None
    with np.load(path) as archive:
        return np.asarray(archive["template"], dtype=np.float64)


def _resize_nearest(
    arr: npt.NDArray[np.float64], shape: tuple[int, int]
) -> npt.NDArray[np.float64]:
    h, w = arr.shape
    rows = ((np.arange(shape[0]) + 0.5) * h / shape[0]).astype(np.intp)
    cols = ((np.arange(shape[1]) + 0.5) * w / shape[1]).astype(np.intp)
    return arr[np.ix_(rows, cols)]


class LogoAbsenceDetector(Detector):
    name: ClassVar[str] = "logo_absence"
    needs_video: ClassVar[bool] = True

    def __init__(
        self,
        config: LogoAbsenceConfig,
        template: npt.NDArray[np.float64] | None = None,
    ) -> None:
        self._cfg = config
        if template is None:
            template = load_template(Path(config.template))
        self._template = template
        self._centered_template: npt.NDArray[np.float64] | None = None
        if template is not None:
            self._centered_template = template - float(template.mean())
        self._absent_run = 0
        self._score = 1.0

    @property
    def calibrated(self) -> bool:
        return self._template is not None

    @property
    def program_present(self) -> bool:
        """Positive program proof: the logo is visibly on screen right now.

        The engine uses this as the early-unmute signal inside a
        fingerprint-matched window — presence is evidence, absence is not.
        """
        return self.calibrated and self._absent_run == 0 and (
            self._score >= self._cfg.present_threshold
        )

    def warmup(self) -> None:
        self._absent_run = 0
        self._score = 1.0

    def _similarity(self, roi: npt.NDArray[np.uint8]) -> float:
        """Pearson correlation between the ROI's edge map and the template.

        Centered on purpose: edge magnitudes are non-negative, so a plain
        cosine floors well above zero on unrelated content; correlation
        stays near zero there and at 1.0 when the bug's edges are present.
        The template's variance is dominated by the bug's own edges (content
        edges average out during calibration), so no explicit mask is needed.
        """
        assert self._centered_template is not None
        edges = edge_map(to_luma(roi))
        if edges.shape != self._centered_template.shape:
            edges = _resize_nearest(edges, self._centered_template.shape)
        a = edges - float(edges.mean())
        b = self._centered_template
        denom = float(np.linalg.norm(a) * np.linalg.norm(b))
        if denom == 0.0:
            return 0.0
        return float(np.dot(a.ravel(), b.ravel()) / denom)

    def observe_frame(self, event: FrameEvent) -> None:
        if not self.calibrated:
            return
        roi = extract_roi(
            event.frame, self._cfg.roi.x, self._cfg.roi.y, self._cfg.roi.w, self._cfg.roi.h
        )
        raw = self._similarity(roi)
        self._score += _SCORE_ALPHA * (raw - self._score)
        if self._score < self._cfg.present_threshold:
            self._absent_run += 1
        else:
            self._absent_run = 0

    def vote(self, ts: float) -> DetectorVote:
        if not self.calibrated:
            return self._vote(ts, 0.0, "uncalibrated")
        if self._absent_run == 0:
            return self._vote(ts, 0.0, f"logo_present score={self._score:.2f}")
        confidence = min(1.0, self._absent_run / self._cfg.absence_frames)
        return self._vote(
            ts,
            confidence,
            f"logo_absent frames={self._absent_run} score={self._score:.2f}",
        )
