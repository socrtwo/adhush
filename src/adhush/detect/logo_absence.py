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

Three rules learned from the phone (ADR 0013, ADR 0014) apply when configured:
with ``require_sighting`` the bug must be *seen* once before its absence
counts, and "Not an ad" demands a fresh sighting; with ``search_px`` the ROI
is slid over a small window and the best correlation counts, so a camera's
screen box found a few pixels off does not read as "logo gone"; and with no
frame for ``stale_s`` (the camera dropped a partial screen) the detector is
inert rather than absent. Inert means ``voting`` is False: no vote at all.
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
        self._score = 0.0 if config.require_sighting else 1.0
        self._sighted = not config.require_sighting
        self._last_frame_ts: float | None = None
        self._last_offset = (0, 0)

    @property
    def calibrated(self) -> bool:
        return self._template is not None

    @property
    def sighted(self) -> bool:
        """The logo has been seen present since the last reset or correction."""
        return self._sighted

    @property
    def last_offset(self) -> tuple[int, int]:
        """Where the best match sat relative to the configured ROI, in frame pixels."""
        return self._last_offset

    def stale(self, ts: float) -> bool:
        """No frame for ``stale_s``: the camera is not showing a whole screen."""
        return self._last_frame_ts is not None and ts - self._last_frame_ts > self._cfg.stale_s

    @property
    def voting(self) -> bool:
        return self.calibrated and self._sighted and not self._stale_now

    def describe(self, ts: float | None = None) -> str:
        """One short line for a status display: what the camera can and cannot see."""
        if not self.calibrated:
            return "not calibrated"
        if ts is not None and self.stale(ts):
            return "whole TV not in view"
        if not self._sighted:
            return "looking for the bug"
        if self._absent_run > 0:
            return "bug gone"
        return "bug seen"

    def user_says_program(self, ts: float) -> None:
        """"Not an ad": the logo was not gone. Forget the absence; with
        ``require_sighting``, demand a fresh sighting before saying so again."""
        self._absent_run = 0
        if self._cfg.require_sighting:
            self._sighted = False
            self._score = 0.0
        else:
            self._score = self._cfg.present_threshold

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
        self._score = 0.0 if self._cfg.require_sighting else 1.0
        self._sighted = not self._cfg.require_sighting
        self._last_frame_ts = None
        self._stale_now = False
        self._last_offset = (0, 0)

    _stale_now = False

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

    def _best_similarity(self, event: FrameEvent) -> float:
        """Correlation at the configured ROI, or the best over the search window."""
        cfg = self._cfg
        roi = extract_roi(event.frame, cfg.roi.x, cfg.roi.y, cfg.roi.w, cfg.roi.h)
        best = self._similarity(roi)
        self._last_offset = (0, 0)
        if cfg.search_px <= 0:
            return best
        # The window is given in pixels of a 320-wide screen; scale to this frame.
        h, w = event.frame.shape[:2]
        rx = max(1, round(cfg.search_px * w / 320))
        ry = max(1, round(cfg.search_px * h / 180))
        step_x, step_y = max(1, rx // 6), max(1, ry // 6)
        # The same box arithmetic as extract_roi, so the slid crops match the template's size.
        x0, y0 = int(cfg.roi.x * w), int(cfg.roi.y * h)
        bw = min(w, int((cfg.roi.x + cfg.roi.w) * w)) - x0
        bh = min(h, int((cfg.roi.y + cfg.roi.h) * h)) - y0
        for dy in range(-ry, ry + 1, step_y):
            for dx in range(-rx, rx + 1, step_x):
                if dx == 0 and dy == 0:
                    continue
                xa, ya = x0 + dx, y0 + dy
                if xa < 0 or ya < 0 or xa + bw > w or ya + bh > h:
                    continue
                score = self._similarity(event.frame[ya : ya + bh, xa : xa + bw])
                if score > best:
                    best, self._last_offset = score, (dx, dy)
        return best

    def observe_frame(self, event: FrameEvent) -> None:
        if not self.calibrated:
            return
        self._last_frame_ts = event.ts
        self._stale_now = False
        raw = self._best_similarity(event)
        self._score += _SCORE_ALPHA * (raw - self._score)
        if self._score >= self._cfg.present_threshold:
            self._absent_run = 0
            if raw >= self._cfg.present_threshold:
                self._sighted = True
        elif self._sighted:
            self._absent_run += 1

    def vote(self, ts: float) -> DetectorVote:
        if not self.calibrated:
            return self._vote(ts, 0.0, "uncalibrated")
        self._stale_now = self.stale(ts)
        if self._stale_now:
            return self._vote(ts, 0.0, "no_screen")
        if not self._sighted:
            return self._vote(ts, 0.0, "logo_not_yet_seen")
        if self._absent_run == 0:
            return self._vote(ts, 0.0, f"logo_present score={self._score:.2f}")
        confidence = min(1.0, self._absent_run / self._cfg.absence_frames)
        return self._vote(
            ts,
            confidence,
            f"logo_absent frames={self._absent_run} score={self._score:.2f}",
        )
