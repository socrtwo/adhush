from collections.abc import Sequence

import pytest

from adhush.config import Config, ControlConfig, load_config, load_profile
from adhush.control import build_controller
from adhush.control.base import ControlError
from adhush.control.local_audio import (
    LocalAudioController,
    mute_commands,
    parse_mute_state,
)
from adhush.control.probe import probe_backends

from .test_control_phase3 import PROFILES, REPO


class RecordingRunner:
    def __init__(self, output: str = "", exit_code: int = 0) -> None:
        self.calls: list[list[str]] = []
        self.output = output
        self.exit_code = exit_code

    def __call__(self, args: Sequence[str]) -> tuple[int, str]:
        self.calls.append(list(args))
        return self.exit_code, self.output


class TestLocalAudio:
    def test_linux_pactl_discrete_mute(self) -> None:
        runner = RecordingRunner(output="Mute: yes\n")
        controller = LocalAudioController({}, runner=runner, platform="linux")
        assert controller.supports_discrete()
        controller.mute()
        controller.unmute()
        assert runner.calls[0] == ["pactl", "set-sink-mute", "@DEFAULT_SINK@", "1"]
        assert runner.calls[1] == ["pactl", "set-sink-mute", "@DEFAULT_SINK@", "0"]
        assert controller.state() is True

    def test_linux_amixer_variant(self) -> None:
        runner = RecordingRunner(output="  Mono: Playback [on] ... [off]\n")
        controller = LocalAudioController({"tool": "amixer"}, runner=runner, platform="linux")
        controller.mute()
        assert runner.calls[0][0] == "amixer"
        assert controller.state() is True  # "[off]" present

    def test_macos_osascript(self) -> None:
        runner = RecordingRunner(output="true\n")
        controller = LocalAudioController({}, runner=runner, platform="darwin")
        controller.mute()
        assert runner.calls[0][0] == "osascript"
        assert controller.state() is True

    def test_windows_nircmd_no_readback(self) -> None:
        runner = RecordingRunner()
        controller = LocalAudioController({}, runner=runner, platform="win32")
        controller.mute()
        assert runner.calls[0] == ["nircmd", "mutesysvolume", "1"]
        assert controller.state() is None

    def test_unsupported_platform_rejected(self) -> None:
        with pytest.raises(ControlError, match="platform"):
            LocalAudioController({}, runner=RecordingRunner(), platform="plan9")

    def test_nonzero_exit_raises(self) -> None:
        controller = LocalAudioController(
            {}, runner=RecordingRunner(exit_code=1), platform="linux"
        )
        with pytest.raises(ControlError, match="exited 1"):
            controller.mute()

    def test_state_parsing_table(self) -> None:
        assert parse_mute_state("linux", "pactl", "Mute: no") is False
        assert parse_mute_state("darwin", "", "false") is False
        assert parse_mute_state("linux", "pactl", "???") is None
        assert "state" not in mute_commands("win32", "")


def test_registry_builds_local_audio() -> None:
    controller = build_controller(ControlConfig(backend="local_audio"))
    assert isinstance(controller, LocalAudioController)


def test_probe_reports_local_audio() -> None:
    base = load_config(REPO / "config" / "adhush.example.toml", PROFILES)
    config = Config(
        capture=base.capture,
        detect=base.detect,
        fusion=base.fusion,
        control=ControlConfig(
            backend="local_audio", sections={"local_audio": {"platform": "linux"}}
        ),
        profile=load_profile(PROFILES, "sharp-lc46le830u"),  # lists local_audio
        fingerprint=base.fingerprint,
    )
    results = probe_backends(
        config,
        which=lambda name: f"/usr/bin/{name}",
        path_exists=lambda _p: True,
        connect=lambda _h, _p, _t: None,
        can_import=lambda _m: True,
    )
    by_name = {r.backend: r for r in results}
    assert by_name["local_audio"].available
    assert by_name["local_audio"].discrete
    assert "pactl" in by_name["local_audio"].detail