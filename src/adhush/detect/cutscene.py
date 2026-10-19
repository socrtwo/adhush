"""Intro/outro template matching — comskip's "cutscenes" (ADR 0023).

A channel shows the same frames at the same moments around every break:
"We'll be right back" over the show's logo, the title card when it returns,
a sponsor billboard. Comskip keeps up to eight frames the user captured
and correlates every frame against them. AdHush does the same with a small
store of templates on disk (``data/cutscenes/<name>.npz``: a 32×18 luma
thumbnail plus its perceptual hash and a kind), written by
``adhush cutscene add``. The jingle's visual twin: an "in" template seen
live votes 1.0 for ``hold_s`` and may duck alone (LOGO weight); an "out"
template is positive programme evidence for ``close_hold_s``.

Matching is the fingerprint's own phash within ``max_hamming`` bits *and*
the thumbnails' normalised correlation above ``min_correlation``, so a
solid-colour frame cannot match a solid-colour template by hash alone.
Inert with no templates; inert while nothing matches.
"""

from __future__ import annotations

from pathlib import Path
from typing import ClassVar

import numpy as np
import numpy.typing as npt

from adhush.config import CutsceneConfig
from adhush.detect.base import Detector
from adhush.events import DetectorVote, FrameEvent
from adhush.fingerprint.video_phash import hamming, phash
from adhush.util.imageops import to_luma

THUMB_W, THUMB_H = 32, 18


def thumbnail(frame: npt.NDArray[np.uint8]) -> npt.NDArray[np.float32]:
    """A 32×18 area-averaged luma thumbnail as float32."""
    luma = to_luma(frame)
    h, w = luma.shape
    ys = (np.arange(THUMB_H + 1) * h // THUMB_H)
    xs = (np.arange(THUMB_W + 1) * w // THUMB_W)
    out = np.empty((THUMB_H, THUMB_W), dtype=np.float32)
    for j in range(THUMB_H):
        rows = luma[ys[j] : max(ys[j] + 1, ys[j + 1])]
        for i in range(THUMB_W):
            out[j, i] = float(rows[:, xs[i] : max(xs[i] + 1, xs[i + 1])].mean())
    return out


def correlation(a: npt.NDArray[np.float32], b: npt.NDArray[np.float32]) -> float:
    """Normalised cross-correlation of two thumbnails; 1.0 for the same picture."""
    da = a - a.mean()
    db = b - b.mean()
    na = float(np.sqrt((da * da).sum()))
    nb = float(np.sqrt((db * db).sum()))
    if na < 1e-6 or nb < 1e-6:
        return 1.0 if na < 1e-6 and nb < 1e-6 else 0.0
    return float((da * db).sum() / (na * nb))


class CutsceneTemplate:
    def __init__(self, name: str, kind: str, thumb: npt.NDArray[np.float32], hash_: int) -> None:
        self.name = name
        self.kind = kind  # "in" (a break starts) or "out" (the programme returns)
        self.thumb = thumb
        self.hash = hash_

    @classmethod
    def from_frame(cls, name: str, kind: str, frame: npt.NDArray[np.uint8]) -> CutsceneTemplate:
        return cls(name, kind, thumbnail(frame), phash(frame))

    def save(self, directory: Path) -> Path:
        directory.mkdir(parents=True, exist_ok=True)
        path = directory / f"{self.name}.npz"
        np.savez(path, kind=np.array(self.kind), thumb=self.thumb, hash=np.array(self.hash, dtype=np.uint64))
        return path

    @classmethod
    def load(cls, path: Path) -> CutsceneTemplate:
        with np.load(path) as data:
            return cls(path.stem, str(data["kind"]), data["thumb"].astype(np.float32), int(data["hash"]))


def load_templates(directory: Path) -> list[CutsceneTemplate]:
    if not directory.is_dir():
        return []
    return [CutsceneTemplate.load(p) for p in sorted(directory.glob("*.npz"))]


class CutsceneDetector(Detector):
    name: ClassVar[str] = "cutscene"
    needs_video: ClassVar[bool] = True

    def __init__(self, config: CutsceneConfig, templates: list[CutsceneTemplate] | None = None) -> None:
        self._cfg = config
        self._templates = templates if templates is not None else load_templates(Path(config.directory))
        self.warmup()

    def warmup(self) -> None:
        self._next_sample_ts = -np.inf
        self._in_until = -np.inf
        self._out_until = -np.inf
        self._last: tuple[str, float] | None = None
        self._hits = 0

    @property
    def templates(self) -> list[CutsceneTemplate]:
        return list(self._templates)

    def match(self, frame: npt.NDArray[np.uint8]) -> tuple[CutsceneTemplate, float] | None:
        if not self._templates:
            return None
        h = phash(frame)
        candidates = [t for t in self._templates if hamming(h, t.hash) <= self._cfg.max_hamming]
        if not candidates:
            return None
        thumb = thumbnail(frame)
        best: tuple[CutsceneTemplate, float] | None = None
        for t in candidates:
            c = correlation(thumb, t.thumb)
            if c >= self._cfg.min_correlation and (best is None or c > best[1]):
                best = (t, c)
        return best

    def observe_frame(self, event: FrameEvent) -> None:
        if not self._templates or event.ts < self._next_sample_ts:
            return
        self._next_sample_ts = event.ts + self._cfg.sample_interval_s
        hit = self.match(event.frame)
        if hit is None:
            return
        template, c = hit
        self._last = (template.name, c)
        self._hits += 1
        if template.kind == "out":
            self._out_until = event.ts + self._cfg.close_hold_s
            self._in_until = -np.inf
        else:
            self._in_until = event.ts + self._cfg.hold_s

    def user_says_program(self, ts: float) -> None:
        self._in_until = -np.inf

    @property
    def program_present(self) -> bool:
        return self._next_sample_ts - self._cfg.sample_interval_s < self._out_until

    @property
    def voting(self) -> bool:
        now = self._next_sample_ts - self._cfg.sample_interval_s
        return now < self._in_until or now < self._out_until

    def vote(self, ts: float) -> DetectorVote:
        if ts < self._in_until and self._last is not None:
            return self._vote(ts, 1.0, f"cutscene_in name={self._last[0]} corr={self._last[1]:.2f} left_s={self._in_until - ts:.1f}")
        if ts < self._out_until and self._last is not None:
            return self._vote(ts, 0.0, f"cutscene_out name={self._last[0]} corr={self._last[1]:.2f}")
        return self._vote(ts, 0.0, f"cutscene_quiet templates={len(self._templates)}")

    def describe(self) -> str:
        if not self._templates:
            return "no templates (adhush cutscene add)"
        return f"{len(self._templates)} template(s), {self._hits} hit(s)"
