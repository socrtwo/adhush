"""Capture backend registry."""

from __future__ import annotations

from pathlib import Path

from adhush.capture.base import CaptureCaps, CaptureError, CaptureSource
from adhush.capture.camera import CameraSource
from adhush.capture.file_replay import FileReplaySource
from adhush.capture.hdmi_uvc import HdmiUvcSource
from adhush.capture.line_in import LineInSource
from adhush.capture.microphone import MicrophoneSource
from adhush.capture.screen import ScreenSource
from adhush.config import CaptureConfig

__all__ = ["CaptureCaps", "CaptureError", "CaptureSource", "build_capture"]


def build_capture(config: CaptureConfig) -> CaptureSource:
    """Instantiate the configured backend."""
    if config.backend == "hdmi_uvc":
        return HdmiUvcSource(config)
    if config.backend == "screen":
        return ScreenSource(config)
    if config.backend == "camera":
        return CameraSource(config)
    if config.backend == "microphone":
        return MicrophoneSource(config)
    if config.backend == "line_in":
        return LineInSource(config)
    if config.backend == "file_replay":
        if not config.path:
            raise CaptureError("capture.path is required for file_replay")
        return FileReplaySource(
            Path(config.path),
            block_ms=config.audio_block_ms,
            width=config.width,
            height=config.height,
            audio_rate=config.audio_rate,
        )
    raise CaptureError(f"unknown capture backend: {config.backend}")
