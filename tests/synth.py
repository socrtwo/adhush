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
# Normalized logo ROI drawn on program frames (matches the config default).
LOGO_ROI = (0.84, 0.80, 0.14, 0.14)
# ad_fp shot length and tone sequence: every airing renders identically.
FP_SHOT_S = 0.4
FP_TONE_HZ = (523.0, 659.0, 784.0, 988.0, 1175.0)
FP_AMP = 0.30

# kind: program | boundary | ad (flat, loud) | ad_fp (textured, repeatable)
Timeline = list[tuple[str, float]]


def _segment_audio(kind: str, n: int, rng: np.random.Generator) -> npt.NDArray[np.float32]:
    t = np.arange(n, dtype=np.float64) / RATE
    if kind == "program":
        samples = PROGRAM_AMP * np.sin(2 * np.pi * PROGRAM_TONE_HZ * t)
    elif kind == "ad":
        samples = AD_AMP * np.sin(2 * np.pi * AD_TONE_HZ * t)
    elif kind == "ad_fp":
        # Deterministic tone sequence, one tone per half-second block.
        blocks = []
        for start in range(0, n, RATE // 2):
            m = min(RATE // 2, n - start)
            tone = FP_TONE_HZ[(start // (RATE // 2)) % len(FP_TONE_HZ)]
            tb = np.arange(m, dtype=np.float64) / RATE
            blocks.append(FP_AMP * np.sin(2 * np.pi * tone * tb))
        samples = np.concatenate(blocks) if blocks else np.zeros(0)
    else:
        samples = rng.uniform(-BOUNDARY_NOISE_AMP, BOUNDARY_NOISE_AMP, n)
    return samples.astype(np.float32)


def draw_logo(frames: npt.NDArray[np.uint8]) -> None:
    """Stamp a bright network-bug rectangle into the LOGO_ROI in place."""
    x0, y0 = int(LOGO_ROI[0] * WIDTH), int(LOGO_ROI[1] * HEIGHT)
    x1 = min(WIDTH, int((LOGO_ROI[0] + LOGO_ROI[2]) * WIDTH)) - 1
    y1 = min(HEIGHT, int((LOGO_ROI[1] + LOGO_ROI[3]) * HEIGHT)) - 1
    frames[..., y0, x0 : x1 + 1] = 255
    frames[..., y1, x0 : x1 + 1] = 255
    frames[..., y0 : y1 + 1, x0] = 255
    frames[..., y0 : y1 + 1, x1] = 255
    frames[..., (y0 + y1) // 2, x0 : x1 + 1] = 255  # crossbar: more edges


def _segment_frames(kind: str, n_frames: int) -> npt.NDArray[np.uint8]:
    if kind == "ad_fp":
        # Coarse-tiled shots (structure survives downscaling, like real
        # footage) that change every FP_SHOT_S, identical per airing so
        # perceptual hashes repeat, and cut fast enough to trip scene_cut.
        frames = np.empty((n_frames, HEIGHT, WIDTH), dtype=np.uint8)
        per_shot = max(1, round(FP_SHOT_S * FPS))
        for i in range(n_frames):
            shot = i // per_shot
            rng = np.random.default_rng(4200 + shot)
            tile = rng.integers(0, 256, (HEIGHT // 8, WIDTH // 8), dtype=np.uint8)
            frames[i] = np.kron(tile, np.ones((8, 8), dtype=np.uint8))
        return frames
    luma = {"program": PROGRAM_LUMA, "boundary": BOUNDARY_LUMA, "ad": AD_LUMA}[kind]
    frames = np.full((n_frames, HEIGHT, WIDTH), luma, dtype=np.uint8)
    if kind == "program":
        draw_logo(frames)
    return frames


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
            pod_has_ad = pod_has_ad or kind in ("ad", "ad_fp")

        n_frames = round(duration * FPS)
        if video and n_frames:
            frames.append(_segment_frames(kind, n_frames))
        if audio:
            audio_parts.append(_segment_audio(kind, round(duration * RATE), rng))
        now += duration

    all_frames = np.concatenate(frames) if video else None
    frame_ts = (
        np.arange(len(all_frames), dtype=np.float64) / FPS if all_frames is not None else None
    )
    all_audio = np.concatenate(audio_parts) if audio else None
    return all_frames, frame_ts, all_audio, labels
