"""Detector plugin registry and discovery."""

from __future__ import annotations

from adhush.config import DetectConfig
from adhush.detect.base import Detector
from adhush.detect.black_frame import BlackFrameDetector
from adhush.detect.loudness import LoudnessDetector
from adhush.detect.silence import SilenceDetector

__all__ = ["Detector", "build_detectors"]

# Phase 1 detectors. Later phases append here as they land.
_REGISTRY: dict[str, type[Detector]] = {
    BlackFrameDetector.name: BlackFrameDetector,
    SilenceDetector.name: SilenceDetector,
    LoudnessDetector.name: LoudnessDetector,
}


def build_detectors(config: DetectConfig, *, video: bool, audio: bool) -> list[Detector]:
    """Instantiate enabled detectors whose required modalities are available.

    Detectors enabled in config but not yet implemented are skipped silently;
    the roadmap phases land them one at a time.
    """
    detectors: list[Detector] = []
    for name in config.enabled:
        cls = _REGISTRY.get(name)
        if cls is None:
            continue
        if cls.needs_video and not video:
            continue
        if cls.needs_audio and not audio:
            continue
        if cls is BlackFrameDetector:
            detectors.append(BlackFrameDetector(config.black_frame))
        elif cls is SilenceDetector:
            detectors.append(SilenceDetector(config.silence))
        elif cls is LoudnessDetector:
            detectors.append(LoudnessDetector(config.loudness))
    return detectors
