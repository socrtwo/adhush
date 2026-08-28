"""Platform capture backends: ffmpeg argv builders, screen-box detection,
audio device parsing, and registry wiring. No ffmpeg required."""

import numpy as np
import pytest

from adhush.capture import build_capture
from adhush.capture.base import CaptureError
from adhush.capture.camera import CameraSource, camera_ffmpeg_args, detect_screen_bbox
from adhush.capture.line_in import LineInSource
from adhush.capture.microphone import (
    MicrophoneSource,
    audio_ffmpeg_args,
    parse_audio_device,
)
from adhush.capture.screen import ScreenSource, screen_ffmpeg_args
from adhush.config import CaptureConfig


class TestAudioDeviceParsing:
    def test_explicit_prefix_wins(self) -> None:
        assert parse_audio_device("pulse:tv.monitor", "linux") == ("pulse", "tv.monitor")
        assert parse_audio_device("alsa:hw:1,0", "win32") == ("alsa", "hw:1,0")

    def test_platform_defaults(self) -> None:
        assert parse_audio_device("default", "linux") == ("alsa", "default")
        assert parse_audio_device(":0", "darwin") == ("avfoundation", ":0")
        assert parse_audio_device("Microphone", "win32") == ("dshow", "Microphone")

    def test_unknown_platform_rejected(self) -> None:
        with pytest.raises(CaptureError):
            parse_audio_device("default", "plan9")

    def test_dshow_and_avfoundation_spellings(self) -> None:
        args = audio_ffmpeg_args(CaptureConfig(audio_device="Microphone"), "win32")
        assert "audio=Microphone" in args
        args = audio_ffmpeg_args(CaptureConfig(audio_device="0"), "darwin")
        assert ":0" in args


class TestArgvBuilders:
    def test_screen_linux_x11grab(self) -> None:
        args = screen_ffmpeg_args(CaptureConfig(width=1280, height=720, fps=30), "linux")
        assert "x11grab" in args and ":0.0" in args and "1280x720" in args

    def test_screen_macos_and_windows(self) -> None:
        args = screen_ffmpeg_args(CaptureConfig(device="2"), "darwin")
        assert "avfoundation" in args and "2:none" in args
        args = screen_ffmpeg_args(CaptureConfig(), "win32")
        assert "gdigrab" in args and "desktop" in args

    def test_camera_per_platform(self) -> None:
        args = camera_ffmpeg_args(CaptureConfig(device="/dev/video2"), "linux")
        assert "v4l2" in args and "/dev/video2" in args
        args = camera_ffmpeg_args(CaptureConfig(device="1"), "darwin")
        assert "1:none" in args
        args = camera_ffmpeg_args(CaptureConfig(device="HD Webcam"), "win32")
        assert "video=HD Webcam" in args

    def test_unsupported_platform_rejected(self) -> None:
        with pytest.raises(CaptureError):
            screen_ffmpeg_args(CaptureConfig(), "plan9")
        with pytest.raises(CaptureError):
            camera_ffmpeg_args(CaptureConfig(), "plan9")


class TestScreenBboxDetection:
    def _scene(self) -> np.ndarray:
        luma = np.full((60, 80), 12, dtype=np.uint8)  # dark room
        luma[10:40, 20:60] = 180  # lit screen
        return luma

    def test_finds_lit_screen(self) -> None:
        box = detect_screen_bbox(self._scene())
        assert box == (20, 10, 60, 40)

    def test_glare_pixels_do_not_stretch_the_box(self) -> None:
        luma = self._scene()
        luma[55:58, 70:78] = 255  # lamp reflection outside the screen
        assert detect_screen_bbox(luma) == (20, 10, 60, 40)

    def test_flat_scene_yields_none(self) -> None:
        assert detect_screen_bbox(np.full((60, 80), 100, dtype=np.uint8)) is None

    def test_tiny_bright_region_rejected(self) -> None:
        luma = np.full((60, 80), 12, dtype=np.uint8)
        luma[5:8, 5:8] = 200
        assert detect_screen_bbox(luma) is None

    def test_camera_crop_applies_and_respects_autocrop(self) -> None:
        frame = np.full((64, 80), 10, dtype=np.uint8)
        frame[16:48, 16:64] = 190
        cropping = CameraSource(CaptureConfig(autocrop=True), platform="linux")
        cropped = cropping._crop(frame, ts=0.0)
        assert cropped.shape[0] < frame.shape[0] and cropped.shape[1] < frame.shape[1]
        plain = CameraSource(CaptureConfig(autocrop=False), platform="linux")
        assert plain._crop(frame, ts=0.0).shape == frame.shape


class TestCapsAndRegistry:
    def test_microphone_is_audio_only(self) -> None:
        caps = MicrophoneSource(CaptureConfig(), platform="linux").caps()
        assert caps.audio and not caps.video

    def test_screen_audio_can_be_disabled(self) -> None:
        caps = ScreenSource(CaptureConfig(audio_device="none"), platform="linux").caps()
        assert caps.video and not caps.audio

    def test_registry_builds_new_backends(self) -> None:
        assert isinstance(build_capture(CaptureConfig(backend="screen")), ScreenSource)
        assert isinstance(build_capture(CaptureConfig(backend="camera")), CameraSource)
        assert isinstance(
            build_capture(CaptureConfig(backend="microphone")), MicrophoneSource
        )
        assert isinstance(build_capture(CaptureConfig(backend="line_in")), LineInSource)

    def test_open_without_ffmpeg_raises(self) -> None:
        # This environment has no ffmpeg on PATH.
        with pytest.raises(CaptureError, match="ffmpeg"):
            MicrophoneSource(CaptureConfig(), platform="linux").open()
