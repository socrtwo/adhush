"""The overlay's headless half: status styling and the IPC client, on a live core."""

from __future__ import annotations

import threading
import time

from adhush.config import BlackFrameConfig, FusionConfig, IpcConfig
from adhush.control import NullController
from adhush.detect.black_frame import BlackFrameDetector
from adhush.detect.fusion import Fusion
from adhush.engine import Pipeline
from adhush.ipc.api import ApiServer
from adhush.state import AdStateMachine
from adhush.ui.overlay import (
    AMBER,
    GREEN,
    GREY,
    RED,
    OverlayClient,
    load_position,
    save_position,
    state_style,
)


class TestStateStyle:
    def test_every_state_maps_to_a_readable_label(self) -> None:
        assert state_style({"muted": True, "state": "ad"}) == ("MUTED", RED)
        assert state_style({"muted": False, "state": "program"}) == ("PROGRAM", GREEN)
        assert state_style({"muted": False, "state": "recovery"}) == ("PROGRAM", GREEN)
        assert state_style({"muted": False, "state": "suspect_ad"}) == ("SUSPECT", AMBER)
        assert state_style({"muted": False, "state": "program", "override": "mute"}) == ("MUTED", RED)
        assert state_style({"muted": True, "state": "ad", "override": "unmute"}) == ("PROGRAM", GREEN)
        assert state_style({"unreachable": True}) == ("OFFLINE", GREY)
        assert state_style(None) == ("OFFLINE", GREY)
        assert state_style({}) == ("OFFLINE", GREY)
        assert state_style({"state": "weird"}) == ("WEIRD", GREY)


def _core(token: str = "") -> tuple[ApiServer, Pipeline, threading.Event]:
    fusion_cfg = FusionConfig()
    detectors = [BlackFrameDetector(BlackFrameConfig())]
    pipeline = Pipeline(
        detectors,
        Fusion(fusion_cfg, {}, [d.name for d in detectors]),
        AdStateMachine(fusion_cfg),
        NullController(),
    )
    stopped = threading.Event()
    server = ApiServer(
        pipeline, IpcConfig(enabled=True, host="127.0.0.1", port=0, token=token),
        on_shutdown=stopped.set,
    )
    server.start()
    return server, pipeline, stopped


class TestOverlayClient:
    def test_follows_status_over_sse_with_a_token(self) -> None:
        server, _pipeline, _ = _core(token="tok")
        seen: list[dict] = []
        got_one = threading.Event()

        def on_status(status: dict) -> None:
            seen.append(status)
            got_one.set()

        host, port = server.address
        client = OverlayClient(f"http://{host}:{port}", token="tok", on_status=on_status)
        client.start()
        try:
            assert got_one.wait(3.0), "no initial status over SSE"
            assert seen[0]["state"] == "program"
            # a command changes state; the change arrives without polling
            got_one.clear()
            client.command({"type": "override", "mode": "mute"})
            deadline = time.monotonic() + 3.0
            while time.monotonic() < deadline and not any(s.get("override") == "mute" for s in seen):
                time.sleep(0.05)
            assert any(s.get("override") == "mute" for s in seen)
        finally:
            client.stop()
            server.close()

    def test_reports_unreachable_and_recovers_by_polling(self) -> None:
        seen: list[dict] = []
        client = OverlayClient("http://127.0.0.1:1", on_status=seen.append, retry_s=0.05)
        client.start()
        deadline = time.monotonic() + 2.0
        while time.monotonic() < deadline and not seen:
            time.sleep(0.02)
        client.stop()
        assert seen and seen[0] == {"unreachable": True}

    def test_shutdown_reaches_the_core(self) -> None:
        server, _, stopped = _core()
        host, port = server.address
        client = OverlayClient(f"http://{host}:{port}")
        try:
            assert client.shutdown_core()["ok"] is True
            assert stopped.wait(2.0)
        finally:
            server.close()

    def test_reject_and_confirm_are_plain_commands(self) -> None:
        server, _, _ = _core()
        host, port = server.address
        client = OverlayClient(f"http://{host}:{port}")
        try:
            assert "ok" in client.reject_ad()
            assert "ok" in client.confirm_ad()
        finally:
            server.close()


class TestPositionMemory:
    def test_round_trip_and_tolerance(self, tmp_path) -> None:
        path = tmp_path / "deep" / "overlay.json"
        assert load_position(path) is None
        save_position(path, 40, 900)
        assert load_position(path) == (40, 900)
        path.write_text("not json")
        assert load_position(path) is None
