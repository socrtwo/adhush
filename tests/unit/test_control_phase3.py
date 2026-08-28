"""Phase 3 controllers: cec, ir_pigpio, ir_blaster_net, network_ip, probe,
option resolution, and the shipped profile library."""

from pathlib import Path
from typing import ClassVar

import pytest

from adhush.config import Config, ControlConfig, load_config, load_profile
from adhush.control import build_controller, resolve_options
from adhush.control.base import ControlError
from adhush.control.cec import CecController, frame_user_control
from adhush.control.ir_blaster_net import IrBlasterNetController
from adhush.control.ir_pigpio import (
    IrPigpioController,
    Pulses,
    encode_code,
    encode_nec,
    encode_nec_ext,
    encode_raw,
    encode_rc5,
    encode_samsung,
    encode_sharp,
    encode_sirc,
)
from adhush.control.network_ip import NetworkIpController
from adhush.control.probe import probe_backends

REPO = Path(__file__).resolve().parents[2]
PROFILES = REPO / "config" / "profiles"


class TestCec:
    def test_frame_formatting(self) -> None:
        assert frame_user_control(1, 0, 0x43) == "tx 10:44:43"
        assert frame_user_control(1, 0, None) == "tx 10:45"
        assert frame_user_control(4, 5, 0x43) == "tx 45:44:43"

    def test_bad_address_rejected(self) -> None:
        with pytest.raises(ValueError):
            frame_user_control(16, 0, 0x43)

    def test_mute_sends_press_and_release(self) -> None:
        sent: list[list[str]] = []

        def runner(lines):  # type: ignore[no-untyped-def]
            sent.append(list(lines))
            return 0

        controller = CecController({}, runner=runner)
        assert not controller.supports_discrete()
        controller.mute()
        controller.unmute()  # toggle: same frames both ways
        assert sent == [["tx 10:44:43", "tx 10:45"], ["tx 10:44:43", "tx 10:45"]]

    def test_nonzero_exit_raises(self) -> None:
        controller = CecController({}, runner=lambda _lines: 1)
        with pytest.raises(ControlError, match="exited 1"):
            controller.mute()


def _long_spaces(pulses: Pulses, threshold: int = 1000) -> int:
    return sum(1 for _, space in pulses[1:] if space > threshold)


class TestIrEncoders:
    def test_nec_shape_and_bit_invariant(self) -> None:
        pulses = encode_nec(0x04, 0x09)  # LG mute
        assert len(pulses) == 34  # header + 32 bits + trailer
        assert pulses[0] == (9000, 4500)
        assert pulses[-1] == (562, 0)
        # addr+~addr and cmd+~cmd each carry exactly eight 1-bits.
        assert _long_spaces(pulses) == 16

    def test_nec_ext_carries_16bit_address(self) -> None:
        pulses = encode_nec_ext(0xFB04, 0x08)  # Vizio mute
        assert len(pulses) == 34
        assert _long_spaces(pulses) == 0xFB04.bit_count() + 8

    def test_samsung_header_and_doubled_address(self) -> None:
        pulses = encode_samsung(0x07, 0x0F)  # Samsung mute (0xE0E0F00F)
        assert pulses[0] == (4500, 4500)
        assert len(pulses) == 34
        assert _long_spaces(pulses) == 2 * 0x07.bit_count() + 8

    def test_sharp_sends_frame_then_inverted_frame(self) -> None:
        pulses = encode_sharp(0x01, 0x16)
        assert len(pulses) == 32  # 2 x (15 bits + trailer)
        assert pulses[15] == (320, 40000)  # inter-frame gap
        assert pulses[-1] == (320, 0)
        ones_first = 0x01.bit_count() + 0x16.bit_count() + 1  # exp=1, chk=0
        ones_second = 0x01.bit_count() + (~0x16 & 0xFF).bit_count() + 1  # chk=1
        assert _long_spaces(pulses) == ones_first + ones_second

    def test_sirc_marks_encode_bits(self) -> None:
        pulses = encode_sirc(0x01, 0x14, bits=12)  # Sony TV mute
        assert pulses[0] == (2400, 600)
        assert len(pulses) == 13  # header + 7 command + 5 address bits
        long_marks = sum(1 for mark, _ in pulses[1:] if mark == 1200)
        assert long_marks == 0x14.bit_count() + 0x01.bit_count()
        with pytest.raises(ValueError):
            encode_sirc(1, 1, bits=13)

    def test_rc5_biphase_duration(self) -> None:
        pulses = encode_rc5(0x00, 0x0D)  # mute, address 0
        total_us = sum(mark + space for mark, space in pulses)
        # 14 bits x 1778 us, minus the leading idle half of the first start bit.
        assert total_us == 14 * 1778 - 889
        assert all(mark % 889 == 0 and space % 889 == 0 for mark, space in pulses)

    def test_raw_pairs_and_validation(self) -> None:
        assert encode_raw([100, 200, 300]) == [(100, 200), (300, 0)]
        with pytest.raises(ValueError):
            encode_raw([])
        with pytest.raises(ValueError):
            encode_raw([100, -1])

    def test_encode_code_dispatch_and_carrier_defaults(self) -> None:
        _, carrier = encode_code({"protocol": "sirc", "address": 1, "command": 20}, "nec")
        assert carrier == 40000
        _, carrier = encode_code({"address": 7, "command": 15}, "samsung")
        assert carrier == 38000
        with pytest.raises(ControlError, match="unsupported"):
            encode_code({"protocol": "rc6", "address": 0, "command": 0}, "nec")


class RecordingTransmitter:
    def __init__(self) -> None:
        self.sent: list[tuple[int, int, float, Pulses]] = []

    def __call__(self, gpio: int, carrier: int, duty: float, pulses: Pulses) -> None:
        self.sent.append((gpio, carrier, duty, pulses))


class TestIrPigpioController:
    OPTIONS: ClassVar[dict] = {
        "gpio": 17,
        "protocol": "samsung",
        "codes": {"mute_toggle": {"address": 0x07, "command": 0x0F}},
    }

    def test_toggle_transmits_on_configured_gpio(self) -> None:
        tx = RecordingTransmitter()
        controller = IrPigpioController(dict(self.OPTIONS), transmitter=tx)
        assert not controller.supports_discrete()
        controller.mute()
        gpio, carrier, _, pulses = tx.sent[0]
        assert (gpio, carrier) == (17, 38000)
        assert pulses == encode_samsung(0x07, 0x0F)

    def test_discrete_codes_and_repeat(self) -> None:
        tx = RecordingTransmitter()
        options = {
            "protocol": "nec",
            "repeat": 2,
            "codes": {
                "mute_on": {"address": 1, "command": 2},
                "mute_off": {"address": 1, "command": 3},
            },
        }
        controller = IrPigpioController(options, transmitter=tx)
        assert controller.supports_discrete()
        controller.mute()
        controller.unmute()
        assert len(tx.sent) == 4  # each action repeated twice
        assert tx.sent[0][3] == encode_nec(1, 2)
        assert tx.sent[2][3] == encode_nec(1, 3)

    def test_missing_codes_rejected(self) -> None:
        with pytest.raises(ControlError, match="codes"):
            IrPigpioController({}, transmitter=RecordingTransmitter())


class TestIrBlasterNet:
    def test_itach_sendir_framed_with_cr(self) -> None:
        sent: list[bytes] = []

        def exchange(payload: bytes) -> bytes:
            sent.append(payload)
            return b"completeir,1:1,1\r"

        controller = IrBlasterNetController(
            {"codes": {"mute_toggle": "sendir,1:1,1,38000,1,1,347,173"}}, exchange=exchange
        )
        controller.mute()
        assert sent == [b"sendir,1:1,1,38000,1,1,347,173\r"]

    def test_itach_error_reply_raises(self) -> None:
        controller = IrBlasterNetController(
            {"codes": {"mute_toggle": "sendir,1:1,1,38000,1,1,347,173"}},
            exchange=lambda _p: b"ERR_1:1,014\r",
        )
        with pytest.raises(ControlError, match="rejected"):
            controller.mute()

    def test_non_sendir_code_rejected(self) -> None:
        controller = IrBlasterNetController(
            {"codes": {"mute_toggle": "0xDEADBEEF"}}, exchange=lambda _p: b""
        )
        with pytest.raises(ControlError, match="sendir"):
            controller.mute()

    def test_missing_codes_and_unknown_kind_rejected(self) -> None:
        with pytest.raises(ControlError, match="codes"):
            IrBlasterNetController({}, exchange=lambda _p: b"")
        with pytest.raises(ControlError, match="kind"):
            IrBlasterNetController(
                {"kind": "psychic", "codes": {"mute_toggle": "sendir,x"}},
                exchange=lambda _p: b"",
            )


SONY_COMMANDS = {
    "mute_on": {"send": "*SCAMUT0000000000000001\n", "expect": "*SAAMUT0000000000000000"},
    "mute_off": {"send": "*SCAMUT0000000000000000\n", "expect": "*SAAMUT0000000000000000"},
    "state": {
        "send": "*SEAMUT################\n",
        "expect_on": "0000000000000001",
        "expect_off": "0000000000000000",
    },
}


class TestNetworkIp:
    def test_tcp_discrete_commands_and_reply_check(self) -> None:
        sent: list[bytes] = []

        def exchange(payload: bytes) -> bytes:
            sent.append(payload)
            return b"*SAAMUT0000000000000000\n"

        controller = NetworkIpController(
            {"transport": "tcp", "commands": SONY_COMMANDS}, tcp=exchange
        )
        assert controller.supports_discrete()
        controller.mute()
        controller.unmute()
        assert sent[0] == b"*SCAMUT0000000000000001\n"
        assert sent[1] == b"*SCAMUT0000000000000000\n"

    def test_tcp_reply_mismatch_raises(self) -> None:
        controller = NetworkIpController(
            {"transport": "tcp", "commands": SONY_COMMANDS}, tcp=lambda _p: b"*SAAMUTFFFF\n"
        )
        with pytest.raises(ControlError, match="rejected"):
            controller.mute()

    def test_tcp_state_readback(self) -> None:
        replies = [b"*SAAMUT0000000000000001\n", b"*SAAMUT0000000000000000\n", b"garbage"]
        controller = NetworkIpController(
            {"transport": "tcp", "commands": SONY_COMMANDS}, tcp=lambda _p: replies.pop(0)
        )
        assert controller.state() is True
        assert controller.state() is False
        assert controller.state() is None

    def test_http_toggle_request_formation(self) -> None:
        calls: list[tuple[str, str]] = []

        def http(method, url, _body, _headers):  # type: ignore[no-untyped-def]
            calls.append((method, url))
            return 200, b""

        controller = NetworkIpController(
            {
                "transport": "http",
                "host": "10.0.0.5",
                "port": 8060,
                "commands": {"mute_toggle": {"method": "POST", "path": "/keypress/VolumeMute"}},
            },
            http=http,
        )
        assert not controller.supports_discrete()
        assert controller.state() is None
        controller.mute()
        assert calls == [("POST", "http://10.0.0.5:8060/keypress/VolumeMute")]

    def test_http_error_status_raises(self) -> None:
        controller = NetworkIpController(
            {
                "transport": "http",
                "host": "h",
                "commands": {"mute_toggle": {"path": "/x"}},
            },
            http=lambda *_a: (503, b""),
        )
        with pytest.raises(ControlError, match="HTTP 503"):
            controller.mute()

    def test_missing_commands_rejected(self) -> None:
        with pytest.raises(ControlError, match="commands"):
            NetworkIpController({"transport": "tcp"}, tcp=lambda _p: b"")


class TestResolveOptions:
    def test_profile_ir_section_feeds_ir_backends(self) -> None:
        profile = load_profile(PROFILES, "samsung-generic")
        options = resolve_options(ControlConfig(backend="ir_pigpio"), profile, "ir_pigpio")
        assert options["protocol"] == "samsung"
        assert options["codes"]["mute_toggle"]["command"] == 0x0F

    def test_main_config_overrides_profile(self) -> None:
        profile = load_profile(PROFILES, "sony-bravia-generic")
        config = ControlConfig(backend="network_ip", options={"host": "192.168.1.44"})
        options = resolve_options(config, profile, "network_ip")
        assert options["host"] == "192.168.1.44"  # from main config
        assert options["port"] == 20060  # from profile
        assert options["commands"]["mute_on"]["send"].startswith("*SCAMUT")

    def test_explicit_options_only_apply_to_selected_backend(self) -> None:
        profile = load_profile(PROFILES, "samsung-generic")
        config = ControlConfig(backend="network_ip", options={"host": "x"})
        assert "host" not in resolve_options(config, profile, "ir_pigpio")


class TestProfileLibrary:
    PROFILE_NAMES = (
        "generic",
        "sharp-lc46le830u",
        "samsung-generic",
        "lg-generic",
        "sony-bravia-generic",
        "vizio-generic",
        "roku-tv-generic",
    )

    @pytest.mark.parametrize("name", PROFILE_NAMES)
    def test_profile_loads(self, name: str) -> None:
        profile = load_profile(PROFILES, name)
        assert profile.make

    def test_sony_profile_drives_network_controller(self) -> None:
        profile = load_profile(PROFILES, "sony-bravia-generic")
        config = ControlConfig(backend="network_ip", options={"host": "tv.local"})
        options = resolve_options(config, profile, "network_ip")
        sent: list[bytes] = []

        def exchange(payload: bytes) -> bytes:
            sent.append(payload)
            return b"*SAAMUT0000000000000000\n"

        controller = NetworkIpController(options, tcp=exchange)
        assert controller.supports_discrete()
        controller.mute()
        assert sent == [b"*SCAMUT0000000000000001\n"]

    def test_roku_profile_drives_http_controller(self) -> None:
        profile = load_profile(PROFILES, "roku-tv-generic")
        config = ControlConfig(backend="network_ip", options={"host": "10.1.1.2"})
        options = resolve_options(config, profile, "network_ip")
        calls: list[tuple[str, str]] = []

        def http(method, url, _body, _headers):  # type: ignore[no-untyped-def]
            calls.append((method, url))
            return 200, b""

        NetworkIpController(options, http=http).mute()
        assert calls == [("POST", "http://10.1.1.2:8060/keypress/VolumeMute")]

    @pytest.mark.parametrize("name", ("samsung-generic", "lg-generic", "vizio-generic"))
    def test_ir_profiles_encode_cleanly(self, name: str) -> None:
        profile = load_profile(PROFILES, name)
        options = resolve_options(ControlConfig(backend="ir_pigpio"), profile, "ir_pigpio")
        tx = RecordingTransmitter()
        IrPigpioController(options, transmitter=tx).mute()
        assert tx.sent, name


def test_build_controller_covers_new_backends() -> None:
    assert isinstance(build_controller(ControlConfig(backend="cec")), CecController)
    with pytest.raises(ControlError, match="not implemented"):
        build_controller(ControlConfig(backend="local_audio"))


class TestProbe:
    def _config(self) -> Config:
        example = REPO / "config" / "adhush.example.toml"
        return load_config(example, PROFILES)

    def test_reports_in_profile_order_with_details(self) -> None:
        config = self._config()  # sharp profile: rs232, ir_lirc, ir_pigpio, cec, local_audio
        results = probe_backends(
            config,
            which=lambda name: f"/usr/bin/{name}",  # every binary "installed"
            path_exists=lambda _p: True,  # serial port "present"
            connect=lambda _h, _p, _t: None,
            can_import=lambda _m: True,
        )
        assert [r.backend for r in results] == list(config.profile.control_backends)
        by_name = {r.backend: r for r in results}
        assert by_name["rs232_sharp"].available and by_name["rs232_sharp"].discrete
        assert by_name["ir_lirc"].available
        assert by_name["cec"].available and by_name["cec"].discrete is False
        # Sharp profile deliberately ships no IR codes ("do not guess").
        assert not by_name["ir_pigpio"].available
        assert not by_name["local_audio"].available  # later phase

    def test_missing_environment_reported(self) -> None:
        config = self._config()
        results = probe_backends(
            config,
            which=lambda _name: None,
            path_exists=lambda _p: False,
            connect=lambda _h, _p, _t: "timed out",
            can_import=lambda _m: False,
        )
        assert all(not r.available for r in results)
        by_name = {r.backend: r for r in results}
        assert "pyserial" in by_name["rs232_sharp"].detail
        assert "irsend" in by_name["ir_lirc"].detail

    def test_network_profile_probes_reachability(self) -> None:
        config = self._config()
        profile = load_profile(PROFILES, "sony-bravia-generic")
        config = Config(
            capture=config.capture,
            detect=config.detect,
            fusion=config.fusion,
            control=ControlConfig(backend="network_ip", options={"host": "tv.local"}),
            profile=profile,
            fingerprint=config.fingerprint,
        )
        seen: list[tuple[str, int]] = []

        def connect(host: str, port: int, _t: float) -> str | None:
            seen.append((host, port))
            return None

        results = probe_backends(
            config,
            which=lambda _n: None,
            path_exists=lambda _p: False,
            connect=connect,
            can_import=lambda _m: False,
        )
        by_name = {r.backend: r for r in results}
        assert by_name["network_ip"].available and by_name["network_ip"].discrete
        assert ("tv.local", 20060) in seen


def test_cli_probe_reports_and_fails_without_hardware(capsys) -> None:
    from adhush.cli import main

    code = main(["probe", "--config", str(REPO / "config" / "adhush.example.toml")])
    out = capsys.readouterr().out
    assert "Sharp LC-46LE830U" in out
    assert "rs232_sharp" in out and "cec" in out
    # This machine has no serial port, LIRC, or CEC adapter.
    assert code == 1
    assert "no usable control path" in out
