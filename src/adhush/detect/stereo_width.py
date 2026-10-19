"""Audio channel-count / stereo-width switch (ADR 0023, after comskip and
the Japanese "Auto-Cut" VCRs).

Mitsubishi's 1990s recorders skipped commercials by one cue alone: the
programme was in mono (or bilingual) and the spots came in stereo, and the
flip was the boundary. Comskip still penalises a change in channel count.
On a captured HDMI feed the equivalent is the stereo *width* of the mix:
the side/mid RMS ratio the capture backend measures before it downmixes
(``AudioEvent.width``). A news studio sits near mono; a produced spot is
wide; a film is wider still.

The detector keeps a slow baseline of width (frozen while switched, like
loudness) and classes the current window against it: a jump of
``min_delta`` either way that holds for ``confirm_s`` is a *switch*, and the
detector votes 1.0 while the width stays on the other side. Inert without
width (a mono source, a microphone, a fixture) and inert while the width is
the programme's — the same posture as aspect change: the programme's own
width says nothing. Default weight; never alone.
"""

from __future__ import annotations

from collections import deque
from typing import ClassVar

import numpy as np

from adhush.config import StereoWidthConfig
from adhush.detect.base import Detector
from adhush.events import AudioEvent, DetectorVote


def stereo_width(interleaved: np.ndarray) -> float:
    """side / mid RMS of an interleaved stereo float block; 0 for mono."""
    if interleaved.size < 4:
        return 0.0
    left = interleaved[0::2].astype(np.float64)
    right = interleaved[1::2].astype(np.float64)
    mid = (left + right) * 0.5
    side = (left - right) * 0.5
    m = float(np.sqrt(np.mean(mid * mid)))
    s = float(np.sqrt(np.mean(side * side)))
    if s < 1e-6:
        return 0.0
    return min(2.0, s / max(m, 1e-6))


class StereoWidthDetector(Detector):
    name: ClassVar[str] = "stereo_width"
    needs_audio: ClassVar[bool] = True

    def __init__(self, config: StereoWidthConfig) -> None:
        self._cfg = config
        self.warmup()

    def warmup(self) -> None:
        self._window: deque[tuple[float, float]] = deque()
        self._window_dur = 0.0
        self._width: float | None = None
        self._baseline: float | None = None
        self._seen_s = 0.0
        self._other_since: float | None = None
        self._switched_ts: float | None = None
        self._ducked = False

    def audio_ducked(self, ts: float, ducked: bool) -> None:
        self._ducked = ducked  # width is level-free, but a ducked room mic hears nothing useful

    @property
    def width(self) -> float | None:
        return self._width

    @property
    def baseline(self) -> float | None:
        return self._baseline

    def observe_audio(self, event: AudioEvent) -> None:
        if event.width is None or self._ducked:
            return
        self._window.append((event.duration, event.width))
        self._window_dur += event.duration
        while self._window_dur > self._cfg.window_s and len(self._window) > 1:
            d, _ = self._window.popleft()
            self._window_dur -= d
        self._width = sum(d * w for d, w in self._window) / self._window_dur
        self._seen_s += event.duration
        if self._baseline is None:
            if self._seen_s >= self._cfg.window_s:
                self._baseline = self._width
            return
        now = event.ts + event.duration
        other = abs(self._width - self._baseline) >= self._cfg.min_delta
        if other:
            if self._other_since is None:
                self._other_since = now
            if self._switched_ts is None and now - self._other_since >= self._cfg.confirm_s:
                self._switched_ts = self._other_since
            if self._switched_ts is not None and now - self._switched_ts >= self._cfg.max_switch_s:
                # Switched longer than any break: the programme itself changed mix.
                self._baseline = self._width
                self._switched_ts = None
                self._other_since = None
            return  # frozen while on the other side
        self._other_since = None
        self._switched_ts = None
        alpha = min(1.0, event.duration / self._cfg.baseline_s)
        self._baseline += alpha * (self._width - self._baseline)

    @property
    def voting(self) -> bool:
        return self._switched_ts is not None

    def vote(self, ts: float) -> DetectorVote:
        if self._width is None or self._baseline is None:
            return self._vote(ts, 0.0, "width_unknown")
        if self._switched_ts is not None:
            return self._vote(ts, 1.0, f"width_switched from={self._baseline:.2f} to={self._width:.2f} age_s={ts - self._switched_ts:.1f}")
        return self._vote(ts, 0.0, f"width_same width={self._width:.2f}")

    def describe(self) -> str:
        if self._width is None:
            return "no stereo (mono source)"
        return f"width {self._width:.2f}" + (f", programme {self._baseline:.2f}" if self._baseline is not None else " (learning)")
