"""Phase 5: the passthrough box's relay controller and its wiring."""

import pytest

from adhush.config import Config, ControlConfig, load_config, load_profile
from adhush.control import build_controller
from adhush.control.probe import probe_backends
from adhush.control.relay_hdmi import RelayHdmiController

from .test_control_phase3 import PROFILES, REPO


class FakePin:
    def __init__(self) -> None:
        self.levels: list[bool] = []
        self.closed = False

    def write(self, level: bool) -> None:
        self.levels.append(level)

    def close(self) -> None:
        self.closed = True


class TestRelayHdmi:
    def test_starts_passing_audio(self) -> None:
        pin = FakePin()
        RelayHdmiController({}, pin=pin)
        assert pin.levels == [False]  # de-energized: NC contacts pass audio

    def test_discrete_mute_with_commanded_state(self) -> None:
        pin = FakePin()
        controller = RelayHdmiController({}, pin=pin)
        assert controller.supports_discrete()
        assert controller.state() is False
        controller.mute()
        assert pin.levels[-1] is True and controller.state() is True
        controller.unmute()
        assert pin.levels[-1] is False and controller.state() is False

    def test_active_low_inverts_drive(self) -> None:
        pin = FakePin()
        controller = RelayHdmiController({"active_high": False}, pin=pin)
        assert pin.levels == [True]  # resting level for an active-low board
        controller.mute()
        assert pin.levels[-1] is False

    def test_close_fails_unmuted(self) -> None:
        pin = FakePin()
        controller = RelayHdmiController({}, pin=pin)
        controller.mute()
        controller.close()
        assert pin.levels[-1] is False  # released before letting go
        assert pin.closed
        assert controller.state() is False


def test_registry_builds_relay_and_has_no_unimplemented_backends_left() -> None:
    from adhush.config import KNOWN_CONTROL_BACKENDS
    from adhush.control import IMPLEMENTED_BACKENDS

    assert set(IMPLEMENTED_BACKENDS) == set(KNOWN_CONTROL_BACKENDS)
    with pytest.raises(Exception, match="pigpio"):
        # Constructing for real requires pigpio; absence proves dispatch works.
        build_controller(ControlConfig(backend="relay_hdmi"))


class TestPassthroughBox:
    def test_profile_loads(self) -> None:
        profile = load_profile(PROFILES, "passthrough-box")
        assert profile.control_backends == ("relay_hdmi",)
        assert profile.discrete_mute and profile.state_readback

    def test_example_config_loads(self) -> None:
        config = load_config(REPO / "config" / "adhush-passthrough.example.toml", PROFILES)
        assert config.control.backend == "relay_hdmi"
        assert config.control.options["gpio"] == 23
        assert not config.control.verify_with_audio
        assert config.ipc.enabled and config.ipc.token

    def test_probe_reports_relay(self) -> None:
        base = load_config(REPO / "config" / "adhush-passthrough.example.toml", PROFILES)
        config = Config(
            capture=base.capture,
            detect=base.detect,
            fusion=base.fusion,
            control=base.control,
            profile=base.profile,
            fingerprint=base.fingerprint,
        )
        results = probe_backends(
            config,
            which=lambda _n: None,
            path_exists=lambda _p: False,
            connect=lambda _h, _p, _t: "down",
            can_import=lambda _m: True,  # pigpio "installed"
        )
        assert [r.backend for r in results] == ["relay_hdmi"]
        relay = results[0]
        assert relay.available and relay.discrete
        assert "GPIO 23" in relay.detail

    def test_probe_needs_pigpio(self) -> None:
        base = load_config(REPO / "config" / "adhush-passthrough.example.toml", PROFILES)
        results = probe_backends(
            base,
            which=lambda _n: None,
            path_exists=lambda _p: False,
            connect=lambda _h, _p, _t: "down",
            can_import=lambda _m: False,
        )
        assert not results[0].available
        assert "pigpio" in results[0].detail