"""Wire protocol and a live API server over a real Pipeline."""

import json
import urllib.error
import urllib.request

import pytest

from adhush.config import BlackFrameConfig, FusionConfig, IpcConfig
from adhush.control import NullController
from adhush.detect.black_frame import BlackFrameDetector
from adhush.detect.fusion import Fusion
from adhush.engine import Pipeline
from adhush.events import MuteDecision
from adhush.ipc.api import ApiServer
from adhush.ipc.protocol import ProtocolError, encode_event, parse_command
from adhush.state import Action, AdState, AdStateMachine


class TestProtocol:
    def test_event_round_trip(self) -> None:
        raw = encode_event("status", {"muted": False, "state": "program"})
        message = json.loads(raw)
        assert message == {"v": 1, "type": "status", "data": {"muted": False, "state": "program"}}

    def test_event_serializes_dataclasses_and_enums(self) -> None:
        from adhush.engine import Transition

        raw = encode_event(
            "transition",
            Transition(ts=1.5, action=Action.MUTE, confidence=0.9, reasons=("x",)),
        )
        data = json.loads(raw)["data"]
        assert data["action"] == "mute"
        assert data["reasons"] == ["x"]

    def test_unknown_event_type_rejected(self) -> None:
        with pytest.raises(ProtocolError):
            encode_event("gossip", {})

    def test_command_parsing_and_validation(self) -> None:
        command = parse_command(b'{"v": 1, "type": "override", "mode": "mute"}')
        assert (command.type, command.mode) == ("override", "mute")
        assert parse_command('{"type": "set_trace", "enabled": true}').enabled
        with pytest.raises(ProtocolError, match="JSON"):
            parse_command(b"nope")
        with pytest.raises(ProtocolError, match="version"):
            parse_command('{"v": 99, "type": "get_status"}')
        with pytest.raises(ProtocolError, match="command type"):
            parse_command('{"type": "self_destruct"}')
        with pytest.raises(ProtocolError, match="mode"):
            parse_command('{"type": "override", "mode": "louder"}')


def _pipeline() -> tuple[Pipeline, NullController, AdStateMachine]:
    fusion_cfg = FusionConfig()
    controller = NullController()
    machine = AdStateMachine(fusion_cfg)
    detectors = [BlackFrameDetector(BlackFrameConfig())]
    fusion = Fusion(fusion_cfg, {}, [d.name for d in detectors])
    return Pipeline(detectors, fusion, machine, controller), controller, machine


def _serve(pipeline: Pipeline, token: str = "") -> ApiServer:
    server = ApiServer(pipeline, IpcConfig(enabled=True, host="127.0.0.1", port=0, token=token))
    server.start()
    return server


def _get(server: ApiServer, path: str, token: str = "") -> tuple[int, dict]:
    host, port = server.address
    request = urllib.request.Request(f"http://{host}:{port}{path}")
    if token:
        request.add_header("Authorization", f"Bearer {token}")
    try:
        with urllib.request.urlopen(request, timeout=5.0) as response:
            return response.status, json.loads(response.read())
    except urllib.error.HTTPError as exc:
        return exc.code, json.loads(exc.read() or b"{}")


def _post(server: ApiServer, body: dict, token: str = "") -> tuple[int, dict]:
    host, port = server.address
    request = urllib.request.Request(
        f"http://{host}:{port}/command",
        data=json.dumps(body).encode(),
        method="POST",
    )
    if token:
        request.add_header("Authorization", f"Bearer {token}")
    try:
        with urllib.request.urlopen(request, timeout=5.0) as response:
            return response.status, json.loads(response.read())
    except urllib.error.HTTPError as exc:
        return exc.code, json.loads(exc.read() or b"{}")


class TestApiServer:
    def test_status_endpoint(self) -> None:
        pipeline, _, _ = _pipeline()
        server = _serve(pipeline)
        try:
            status, message = _get(server, "/status")
            assert status == 200
            assert message["type"] == "status"
            assert message["data"]["state"] == "program"
            assert message["data"]["detectors"] == ["black_frame"]
        finally:
            server.close()

    def test_override_command_drives_controller(self) -> None:
        pipeline, controller, _ = _pipeline()
        server = _serve(pipeline)
        try:
            status, reply = _post(server, {"v": 1, "type": "override", "mode": "mute"})
            assert status == 200 and reply["ok"]
            assert [a for _, a in controller.actions] == ["mute"]
            _, message = _get(server, "/status")
            assert message["data"]["override"] == "mute"
            _post(server, {"type": "override", "mode": "auto"})
            assert [a for _, a in controller.actions] == ["mute", "unmute"]
        finally:
            server.close()

    def test_bad_command_is_400(self) -> None:
        pipeline, _, _ = _pipeline()
        server = _serve(pipeline)
        try:
            status, reply = _post(server, {"type": "self_destruct"})
            assert status == 400 and "error" in reply
        finally:
            server.close()

    def test_token_auth(self) -> None:
        pipeline, _, _ = _pipeline()
        server = _serve(pipeline, token="hush")
        try:
            assert _get(server, "/status")[0] == 401
            assert _get(server, "/status", token="hush")[0] == 200
            assert _post(server, {"type": "get_status"})[0] == 401
            assert _post(server, {"type": "get_status"}, token="hush")[0] == 200
        finally:
            server.close()

    def test_sse_stream_delivers_events(self) -> None:
        pipeline, _, _ = _pipeline()
        server = _serve(pipeline)
        try:
            host, port = server.address
            with urllib.request.urlopen(
                f"http://{host}:{port}/events", timeout=5.0
            ) as stream:
                first = stream.readline().decode()
                assert first.startswith("data: ")
                assert json.loads(first[6:])["type"] == "status"
                stream.readline()  # blank separator
                pipeline.set_override("mute")  # emits a status event
                second = stream.readline().decode()
                assert json.loads(second[6:])["data"]["override"] == "mute"
        finally:
            server.close()


class TestPipelineIpcSurface:
    def test_confirm_only_in_ad(self) -> None:
        pipeline, _, machine = _pipeline()
        assert not pipeline.confirm_ad()
        machine.update(MuteDecision(ts=1.0, mute=False, confidence=0.0), promote=True)
        assert pipeline.confirm_ad()

    def test_reject_unmutes_and_records(self) -> None:
        pipeline, controller, machine = _pipeline()
        machine.update(MuteDecision(ts=1.0, mute=False, confidence=0.0), promote=True)
        assert machine.state is AdState.AD
        assert pipeline.reject_ad()
        assert machine.state is AdState.RECOVERY
        assert [a for _, a in controller.actions] == ["unmute"]
        assert pipeline.transitions[-1].reasons == ("user:reject_ad",)

    def test_reject_outside_ad_is_noop(self) -> None:
        pipeline, controller, _ = _pipeline()
        assert not pipeline.reject_ad()
        assert controller.actions == []

    def test_override_pins_controller_against_machine_actions(self) -> None:
        pipeline, controller, machine = _pipeline()
        pipeline.set_override("unmute")
        machine.update(MuteDecision(ts=1.0, mute=False, confidence=0.0), promote=True)
        # Machine entered AD, but with the override pinned the engine must
        # not have driven the controller beyond the override itself.
        from adhush.engine import Transition

        pipeline._record(  # simulate the engine recording the machine's MUTE
            Transition(ts=1.0, action=Action.MUTE, confidence=0.9, reasons=())
        )
        assert [a for _, a in controller.actions] == ["unmute"]
