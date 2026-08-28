"""Detector plugin registry and discovery."""

from __future__ import annotations

import logging

from adhush.config import DetectConfig, FingerprintConfig
from adhush.detect.base import Detector
from adhush.detect.black_frame import BlackFrameDetector
from adhush.detect.fingerprint import FingerprintDetector
from adhush.detect.logo_absence import LogoAbsenceDetector
from adhush.detect.loudness import LoudnessDetector
from adhush.detect.scene_cut import SceneCutDetector
from adhush.detect.silence import SilenceDetector
from adhush.fingerprint.matcher import Matcher

log = logging.getLogger(__name__)

__all__ = ["Detector", "build_detectors"]

_REGISTRY: dict[str, type[Detector]] = {
    BlackFrameDetector.name: BlackFrameDetector,
    SilenceDetector.name: SilenceDetector,
    LoudnessDetector.name: LoudnessDetector,
    LogoAbsenceDetector.name: LogoAbsenceDetector,
    SceneCutDetector.name: SceneCutDetector,
    FingerprintDetector.name: FingerprintDetector,
}


def build_detectors(
    config: DetectConfig,
    *,
    video: bool,
    audio: bool,
    fingerprint: tuple[FingerprintConfig, Matcher] | None = None,
) -> list[Detector]:
    """Instantiate enabled detectors whose required modalities are available.

    An uncalibrated ``logo_absence`` is dropped (a permanently-zero vote
    would only dilute fusion's enabled mass); run ``adhush calibrate`` to
    activate it. ``fingerprint`` is built only when its store/matcher pair is
    supplied. Enabled-but-unimplemented detectors are skipped silently.
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
        elif cls is SceneCutDetector:
            detectors.append(SceneCutDetector(config.scene_cut))
        elif cls is LogoAbsenceDetector:
            logo = LogoAbsenceDetector(config.logo_absence)
            if logo.calibrated:
                detectors.append(logo)
            else:
                log.info(
                    "logo_absence disabled: no template at %s (run 'adhush calibrate')",
                    config.logo_absence.template,
                )
        elif cls is FingerprintDetector and fingerprint is not None:
            fp_config, matcher = fingerprint
            if fp_config.enabled:
                detectors.append(FingerprintDetector(fp_config, matcher))
    return detectors
