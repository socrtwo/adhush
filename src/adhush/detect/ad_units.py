"""Ad-unit length quantisation (ADR 0023, after comskip and MythTV).

Commercials are sold in units: 15, 30, 45, 60, 90, 120 seconds. Inside a
break, the separators between spots — a black run, a silence, a scene
flash — therefore fall at multiples of fifteen seconds from each other,
give or take a second. Programme material has no such rhythm. Comskip and
MythTV score recorded blocks by this; here it runs live and cheaply: the
detector watches the shared frame's mean luma and the audio block's level
for separators of its own (it may not read other detectors), keeps their
timestamps, and votes while the recent separators fit the grid.

Two consecutive fitted gaps are a full vote; one is a half. The vote holds
for ``hold_s`` after the last separator, long enough to bridge to the next
unit. Default weight: it never mutes alone, but it *holds* a mute while the
transient boundary signals have decayed and loudness is only halfway sure,
which is exactly the sag that ends a mute early in the middle of a pod. It
is inert without a fit, and never votes for the programme.
"""

from __future__ import annotations

from collections import deque
from typing import ClassVar

from adhush.config import AdUnitsConfig
from adhush.detect.base import Detector
from adhush.detect.silence import block_dbfs
from adhush.events import AudioEvent, DetectorVote, FrameEvent
from adhush.util.imageops import downscale, mean_luma, to_luma

_UNITS_S = (15.0, 30.0, 45.0, 60.0, 90.0, 120.0)
_DOWNSCALE = 16


def unit_fit(gap_s: float, tolerance_s: float) -> float | None:
    """The ad unit ``gap_s`` fits, or None. Multiples of 15 s up to 120 s."""
    for unit in _UNITS_S:
        if abs(gap_s - unit) <= tolerance_s:
            return unit
    return None


class AdUnitsDetector(Detector):
    name: ClassVar[str] = "ad_units"
    needs_audio: ClassVar[bool] = True
    wants_video: ClassVar[bool] = True

    def __init__(self, config: AdUnitsConfig) -> None:
        self._cfg = config
        self.warmup()

    def warmup(self) -> None:
        self._separators: deque[float] = deque(maxlen=16)
        self._black_run = 0
        self._black_start = 0.0
        self._quiet_since: float | None = None
        self._quiet_open = False
        self._fits: list[float] = []
        self._last_sep: float | None = None
        self._ducked = False

    # -- separators of its own -------------------------------------------------

    def _separator(self, ts: float) -> None:
        # Two cues for the same boundary (black frame + silence) are one separator.
        if self._separators and ts - self._separators[-1] < self._cfg.merge_s:
            return
        self._separators.append(ts)
        self._last_sep = ts
        self._refit()

    def observe_frame(self, event: FrameEvent) -> None:
        luma = mean_luma(downscale(to_luma(event.frame), _DOWNSCALE))
        if luma <= self._cfg.black_luma:
            if self._black_run == 0:
                self._black_start = event.ts
            self._black_run += 1
        else:
            if self._black_run >= self._cfg.min_black_frames:
                self._separator(self._black_start)
            self._black_run = 0

    def observe_audio(self, event: AudioEvent) -> None:
        if self._ducked:
            return  # a ducked set's quiet is the duck, not a separator (ADR 0016)
        quiet = block_dbfs(event.samples) <= self._cfg.silence_dbfs
        if quiet:
            if self._quiet_since is None:
                self._quiet_since = event.ts
            elif not self._quiet_open and event.ts + event.duration - self._quiet_since >= self._cfg.min_silence_s:
                self._quiet_open = True
                self._separator(self._quiet_since)
        else:
            self._quiet_since = None
            self._quiet_open = False

    def audio_ducked(self, ts: float, ducked: bool) -> None:
        self._ducked = ducked
        self._quiet_since = None
        self._quiet_open = False

    def user_says_program(self, ts: float) -> None:
        self._fits = []
        self._separators.clear()

    # -- the grid ---------------------------------------------------------------

    def _refit(self) -> None:
        seps = list(self._separators)
        fits: list[float] = []
        # Count consecutive fitted gaps ending at the latest separator.
        for a, b in zip(reversed(seps[:-1]), reversed(seps), strict=False):
            unit = unit_fit(b - a, self._cfg.tolerance_s)
            if unit is None:
                break
            fits.insert(0, unit)
        self._fits = fits

    @property
    def units(self) -> list[float]:
        """The consecutive ad units that led up to the last separator."""
        return list(self._fits)

    def _active(self, ts: float) -> bool:
        return bool(self._fits) and self._last_sep is not None and ts - self._last_sep <= self._cfg.hold_s

    @property
    def voting(self) -> bool:
        return self._last_sep is not None and bool(self._fits)

    def vote(self, ts: float) -> DetectorVote:
        if not self._active(ts):
            if self._fits and self._last_sep is not None:
                self._fits = []  # the hold ran out with no next separator: the rhythm broke
            return self._vote(ts, 0.0, f"no_units separators={len(self._separators)}")
        n = len(self._fits)
        confidence = 1.0 if n >= 2 else 0.5
        units = ",".join(f"{u:.0f}" for u in self._fits[-4:])
        assert self._last_sep is not None
        return self._vote(ts, confidence, f"unit_fit units={units} n={n} age_s={ts - self._last_sep:.1f}")

    def describe(self) -> str:
        return f"{len(self._fits)} fitted unit(s), {len(self._separators)} separators kept"
