"""Synthesized labeled A/V material for file_replay fixture tests.

Generates deterministic program / pod-boundary / ad content with known
timings, so every detector test runs against labeled ground truth as
CLAUDE.md requires. Kept deliberately small (64x48 @ 10 fps, 8 kHz mono) so
CI stays fast.
"""

from __future__ import annotations

import numpy as np
import numpy.typing as npt

from adhush.events import AdSegment

FPS = 10
WIDTH = 64
HEIGHT = 48
RATE = 8000

PROGRAM_LUMA = 120
BOUNDARY_LUMA = 5
AD_LUMA = 180
PROGRAM_TONE_HZ, PROGRAM_AMP = 440.0, 0.10  # ~ -23 dBFS
AD_TONE_HZ, AD_AMP = 880.0, 0.35  # ~ -12 dBFS: ads are mixed hot
BOUNDARY_NOISE_AMP = 1e-4  # < -80 dBFS noise floor

Timeline = list[tuple[str, float]]  # (kind, duration_s); kind: program|boundary|ad


def _segment_audio(kind: str, n: int, rng: np.random.Generator) -> npt.NDArray[np.float32]:
    t = np.arange(n, dtype=np.float64) / RATE
    if kind == "program":
        samples = PROGRAM_AMP * np.sin(2 * np.pi * PROGRAM_TONE_HZ * t)
    elif kind == "ad":
        samples = AD_AMP * np.sin(2 * np.pi * AD_TONE_HZ * t)
    else:
        samples = rng.uniform(-BOUNDARY_NOISE_AMP, BOUNDARY_NOISE_AMP, n)
    return samples.astype(np.float32)


def _segment_luma(kind: str) -> int:
    return {"program": PROGRAM_LUMA, "boundary": BOUNDARY_LUMA, "ad": AD_LUMA}[kind]


def synthesize(
    timeline: Timeline, *, video: bool = True, audio: bool = True
) -> tuple[
    npt.NDArray[np.uint8] | None,
    npt.NDArray[np.float64] | None,
    npt.NDArray[np.float32] | None,
    list[AdSegment],
]:
    """Render a timeline to (frames, frame_ts, audio, labeled ad segments).

    A labeled ad segment is a maximal run of non-program material that
    contains at least one "ad" stretch; lone boundaries inside program (a
    dramatic pause) are deliberately NOT labeled as ads.
    """
    rng = np.random.default_rng(7)
    frames: list[npt.NDArray[np.uint8]] = []
    audio_parts: list[npt.NDArray[np.float32]] = []
    labels: list[AdSegment] = []

    now = 0.0
    pod_start: float | None = None
    pod_has_ad = False
    for kind, duration in timeline + [("program", 0.0)]:
        if kind == "program":
            if pod_start is not None and pod_has_ad:
                labels.append(
                    AdSegment(start_ts=pod_start, duration_s=now - pod_start, source="label")
                )
            pod_start = None
            pod_has_ad = False
        else:
            if pod_start is None:
                pod_start = now
            pod_has_ad = pod_has_ad or kind == "ad"

        n_frames = round(duration * FPS)
        if video and n_frames:
            frames.append(
                np.full((n_frames, HEIGHT, WIDTH), _segment_luma(kind), dtype=np.uint8)
            )
        if audio:
            audio_parts.append(_segment_audio(kind, round(duration * RATE), rng))
        now += duration

    all_frames = np.concatenate(frames) if video else None
    frame_ts = (
        np.arange(len(all_frames), dtype=np.float64) / FPS if all_frames is not None else None
    )
    all_audio = np.concatenate(audio_parts) if audio else None
    return all_frames, frame_ts, all_audio, labels
