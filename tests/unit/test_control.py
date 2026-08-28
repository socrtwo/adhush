from collections.abc import Sequence

import pytest

from adhush.config import ControlConfig
from adhush.control import NullController, build_controller
from adhush.control.base import ControlError
from adhush.control.ir_lirc import IrLircController
from adhush.control.rs232_sharp import SharpRs232Controller, frame_command


class FakeLink:
    def __init__(self, replies: list[bytes]) -> None:
        self.written: list[bytes] = []
        self._replies = replies

    def write(self, data: bytes) -> None:
        self.written.append(data)

    def read_line(self) -> bytes:
        return self._replies.pop(0)

    def close(self) -> None:
        pass


class TestSharpRs232:
    def test_command_framing_is_padded_8_chars_plus_cr(self) -> None:
        assert frame_command("MUTE", "1") == b"MUTE1   \r"
        assert frame_command("MUTE", "?") == b"MUTE?   \r"
        assert frame_command("POWR", "0") == b"POWR0   \r"

    def test_bad_mnemonic_rejected(self) -> None:
        with pytest.raises(ValueError):
            frame_command("TOOLONG", "1")

    def test_mute_and_unmute_are_discrete(self) -> None:
        link = FakeLink([b"OK\r", b"OK\r"])
        controller = SharpRs232Controller({}, link=link)
        assert controller.supports_discrete()
        controller.mute()
        controller.unmute()
        assert link.written == [b"MUTE1   \r", b"MUTE2   \r"]

    def test_state_readback(self) -> None:
        link = FakeLink([b"1\r", b"2\r"])
        controller = SharpRs232Controller({}, link=link)
        assert controller.state() is True
        assert controller.state() is False
        assert link.written == [b"MUTE?   \r", b"MUTE?   \r"]

    def test_err_reply_raises(self) -> None:
        controller = SharpRs232Controller({}, link=FakeLink([b"ERR\r"]))
        with pytest.raises(ControlError, match="rejected"):
            controller.mute()

    def test_closed_controller_refuses(self) -> None:
        controller = SharpRs232Controller({}, link=FakeLink([]))
        controller.close()
        with pytest.raises(ControlError, match="closed"):
            controller.mute()


class RecordingRunner:
    def __init__(self, exit_code: int = 0) -> None:
        self.calls: list[list[str]] = []
        self.exit_code = exit_code

    def __call__(self, args: Sequence[str]) -> int:
        self.calls.append(list(args))
        return self.exit_code


class TestIrLirc:
    def test_discrete_keys_used_when_present(self) -> None:
        runner = RecordingRunner()
        controller = IrLircController(
            {"remote": "sharp_aquos", "mute_on": "KEY_MUTE_ON", "mute_off": "KEY_MUTE_OFF"},
            runner=runner,
        )
        assert controller.supports_discrete()
        controller.mute()
        controller.unmute()
        assert runner.calls == [
            ["irsend", "SEND_ONCE", "sharp_aquos", "KEY_MUTE_ON"],
            ["irsend", "SEND_ONCE", "sharp_aquos", "KEY_MUTE_OFF"],
        ]

    def test_toggle_fallback_is_not_discrete(self) -> None:
        runner = RecordingRunner()
        controller = IrLircController(
            {"remote": "tv", "mute_toggle": "KEY_MUTE"}, runner=runner
        )
        assert not controller.supports_discrete()
        controller.mute()
        controller.unmute()
        assert [c[-1] for c in runner.calls] == ["KEY_MUTE", "KEY_MUTE"]

    def test_repeat_adds_count(self) -> None:
        runner = RecordingRunner()
        controller = IrLircController(
            {"remote": "tv", "mute_toggle": "KEY_MUTE", "repeat": 2}, runner=runner
        )
        controller.mute()
        assert runner.calls[0][1] == "--count=3"

    def test_nonzero_exit_raises(self) -> None:
        controller = IrLircController(
            {"remote": "tv", "mute_toggle": "KEY_MUTE"}, runner=RecordingRunner(exit_code=1)
        )
        with pytest.raises(ControlError, match="exited 1"):
            controller.mute()

    def test_missing_keys_rejected(self) -> None:
        with pytest.raises(ControlError, match="mute_on"):
            IrLircController({"remote": "tv"}, runner=RecordingRunner())


def test_registry_rejects_unimplemented_backend() -> None:
    with pytest.raises(ControlError, match="not implemented"):
        build_controller(ControlConfig(backend="cec"))


def test_null_controller_records() -> None:
    controller = NullController()
    controller.mute()
    controller.unmute()
    assert [a for _, a in controller.actions] == ["mute", "unmute"]
