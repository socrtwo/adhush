"""The phone's lessons, ported (ADR 0014): no absence vote before a sighting,
a search window that follows the bug, whole screen or nothing, the quiet
period after "Not an ad", teach mode with "Show's back", and a network
controller that keeps one connection and ducks instead of muting."""

from __future__ import annotations

import socket
import threading
from pathlib import Path
from typing import ClassVar

import numpy as np
import pytest

from adhush.capture.camera import screen_complete
from adhush.config import BlackFrameConfig, FusionConfig, LogoAbsenceConfig, RoiConfig
from adhush.control import NullController
from adhush.control.base import ControlError
from adhush.control.network_ip import NetworkIpController, PersistentTcp
from adhush.detect.base import Detector
from adhush.detect.black_frame import BlackFrameDetector
from adhush.detect.fusion import Fusion
from adhush.detect.logo_absence import LogoAbsenceDetector, build_template
from adhush.engine import Pipeline
from adhush.events import AudioEvent, DetectorVote, FrameEvent
from adhush.ipc.protocol import parse_command
from adhush.state import AdState, AdStateMachine
from adhush.util.imageops import extract_roi
from tests.synth import LOGO_ROI, synthesize

ROI = RoiConfig(x=LOGO_ROI[0], y=LOGO_ROI[1], w=LOGO_ROI[2], h=LOGO_ROI[3])
HANDHELD = LogoAbsenceConfig(
    roi=ROI, absence_frames=4, present_threshold=0.4, search_px=6, require_sighting=True, stale_s=2.0
)


def _frames(kind: str, seconds: float) -> list[np.ndarray]:
    frames, _, _, _ = synthesize([(kind, seconds)], audio=False)
    assert frames is not None
    return list(frames)


def _detector(cfg: LogoAbsenceConfig = HANDHELD) -> LogoAbsenceDetector:
    rois = [extract_roi(f, ROI.x, ROI.y, ROI.w, ROI.h) for f in _frames("program", 3.0)]
    det = LogoAbsenceDetector(cfg, template=build_template(rois))
    det.warmup()
    return det


def _feed(det: LogoAbsenceDetector, frames: list[np.ndarray], t0: float, dt: float = 0.1) -> float:
    ts = t0
    for f in frames:
        det.observe_frame(FrameEvent(ts=ts, frame=f))
        ts += dt
    return ts


class TestSighting:
    def test_a_logo_never_seen_is_never_called_absent(self) -> None:
        det = _detector()
        ts = _feed(det, _frames("ad_fp", 2.0), 0.0)   # no bug at all
        assert not det.sighted and not det.voting
        assert det.vote(ts).reason == "logo_not_yet_seen"
        assert det.describe(ts) == "looking for the bug"
        ts = _feed(det, _frames("program", 1.0), ts)  # now it is seen
        assert det.sighted and det.voting and det.describe(ts) == "bug seen"
        ts = _feed(det, _frames("ad_fp", 1.0), ts)    # and now it is gone
        assert det.vote(ts).confidence == 1.0 and det.describe(ts) == "bug gone"
        det.user_says_program(ts)                     # "Not an ad": start over
        assert not det.voting and det.vote(ts).confidence == 0.0
        ts = _feed(det, _frames("ad_fp", 1.0), ts)
        assert det.vote(ts).confidence == 0.0, "still no sighting, still no vote"

    def test_the_search_window_follows_a_bug_a_few_pixels_off(self) -> None:
        shifted = [np.roll(f, -1, axis=1) for f in _frames("program", 1.5)]   # 1 px of a 64 px frame ≈ 5 px of a 320 px screen
        det = _detector()
        ts = _feed(det, shifted, 0.0)
        assert det.program_present, det.vote(ts).reason
        assert det.last_offset == (-1, 0), det.last_offset
        fixed = _detector(LogoAbsenceConfig(roi=ROI, absence_frames=4, search_px=0))
        _feed(fixed, shifted, 0.0)
        assert fixed._score < det._score, "sliding helps"
        ts = _feed(det, _frames("ad_fp", 1.0), ts)
        assert det.vote(ts).confidence == 1.0, "the window does not conjure a bug from content"

    def test_no_frames_means_no_screen_means_no_vote(self) -> None:
        det = _detector()
        ts = _feed(det, _frames("program", 1.0), 0.0)
        assert det.voting
        assert det.vote(ts + 3.0).reason == "no_screen"
        assert not det.voting and det.describe(ts + 3.0) == "whole TV not in view"

    def test_whole_screen_test(self) -> None:
        assert screen_complete((10, 10, 150, 90), 160, 120)
        assert not screen_complete((0, 10, 150, 90), 160, 120), "runs off the left edge"
        assert not screen_complete((10, 10, 60, 100), 160, 120), "portrait is not a TV"


class _Puppet(Detector):
    """Says "ad" whenever told to; remembers being told it was wrong."""

    name: ClassVar[str] = "puppet"
    needs_audio: ClassVar[bool] = True

    def __init__(self) -> None:
        self.saying = 0.0
        self.told = 0
        self.inert = False

    @property
    def voting(self) -> bool:
        return not self.inert

    def user_says_program(self, ts: float) -> None:
        self.told += 1
        self.saying = 0.0

    def vote(self, ts: float) -> DetectorVote:
        return self._vote(ts, self.saying, "puppet")


class TestFusionInert:
    def test_an_inert_detector_neither_adds_nor_dilutes(self) -> None:
        fusion = Fusion(FusionConfig(), {"puppet": 0.45}, ["puppet", "black_frame", "silence"])
        alone = fusion.combine([DetectorVote("puppet", 0.0, 1.0, "x")], 0.0)
        assert alone.confidence == 1.0, "the others did not vote, so they are not in the normalizer"
        fusion.reset()
        diluted = fusion.combine(
            [DetectorVote("puppet", 0.0, 1.0, "x"), DetectorVote("black_frame", 0.0, 0.0, "y"),
             DetectorVote("silence", 0.0, 0.0, "z")], 0.0)
        assert diluted.confidence == 1.0
        fusion.reset()
        assert fusion.combine([], 0.0).confidence == 0.0


def _pipeline(quiet_s: float = 60.0) -> tuple[Pipeline, NullController, AdStateMachine, _Puppet]:
    cfg = FusionConfig(not_ad_quiet_s=quiet_s)
    puppet = _Puppet()
    detectors: list[Detector] = [puppet, BlackFrameDetector(BlackFrameConfig())]
    fusion = Fusion(cfg, {"puppet": 0.45}, [d.name for d in detectors])
    controller = NullController()
    machine = AdStateMachine(cfg)
    pipeline = Pipeline(detectors, fusion, machine, controller)
    pipeline.warmup()
    return pipeline, controller, machine, puppet


def _tick(pipeline: Pipeline, ts: float, n: int) -> float:
    for _ in range(n):
        pipeline.process(AudioEvent(ts=ts, samples=np.zeros(4800, dtype=np.float32), sample_rate=48_000))
        ts += 0.1
    return ts


class TestNotAnAdAndTeaching:
    def test_after_not_an_ad_nothing_mutes_for_a_minute(self) -> None:
        pipeline, controller, machine, puppet = _pipeline()
        puppet.saying = 1.0
        ts = _tick(pipeline, 0.0, 15)
        assert machine.state is AdState.AD
        assert pipeline.reject_ad()
        assert puppet.told == 1 and machine.state is AdState.RECOVERY
        assert pipeline.status()["quiet_s"] > 55.0
        puppet.saying = 1.0
        ts = _tick(pipeline, ts, 300)                     # thirty seconds of insisting
        assert machine.state is not AdState.AD, "the quiet period holds"
        assert [a for _, a in controller.actions] == ["mute", "unmute"]
        ts = _tick(pipeline, ts, 320)                     # past the minute
        assert machine.state is AdState.AD, "then the detectors count again"
        assert pipeline.status()["quiet_s"] == 0.0

    def test_is_an_ad_ducks_now_and_shows_back_restores(self) -> None:
        pipeline, controller, machine, puppet = _pipeline()
        ts = _tick(pipeline, 0.0, 5)
        assert pipeline.confirm_ad(), "outside AD: teach mode"
        assert machine.state is AdState.AD and pipeline.status()["teaching"] is True
        assert [a for _, a in controller.actions] == ["mute"]
        ts = _tick(pipeline, ts, 100)                     # ten seconds; the puppet says nothing
        assert machine.state is AdState.AD, "the hold outlasts the detectors' silence"
        assert pipeline.show_back()
        assert machine.state is AdState.RECOVERY and pipeline.status()["teaching"] is False
        assert pipeline.transitions[-1].reasons == ("user:show_back",)
        assert puppet.told == 1
        assert not pipeline.show_back(), "nothing to end"

    def test_the_commands_parse(self) -> None:
        assert parse_command('{"v": 1, "type": "show_back"}').type == "show_back"


SHARP = {
    "volume_set": {"send": "VOLM{level:<4}\r", "expect": "OK"},
    "volume_query": {"send": "VOLM?   \r"},
}


class TestDucking:
    def test_mute_turns_down_and_unmute_puts_back(self, tmp_path: Path) -> None:
        sent: list[bytes] = []

        def exchange(payload: bytes) -> bytes:
            sent.append(payload)
            return b"19\r" if payload.startswith(b"VOLM?") else b"OK\r"

        state = tmp_path / "preduck.txt"
        ctl = NetworkIpController(
            {"transport": "tcp", "commands": SHARP, "duck_level": 4, "duck_state_file": str(state)},
            tcp=exchange,
        )
        assert ctl.supports_discrete()
        ctl.mute()
        assert sent == [b"VOLM?   \r", b"VOLM4   \r"] and state.read_text() == "19"
        ctl.mute()
        assert len(sent) == 2, "already ducked: nothing sent"
        ctl.unmute()
        assert sent[-1] == b"VOLM19  \r" and not state.exists()
        # A run that died ducked is repaired by the next start.
        state.write_text("21")
        again = NetworkIpController(
            {"transport": "tcp", "commands": SHARP, "duck_level": 4, "duck_state_file": str(state)},
            tcp=exchange,
        )
        assert again.recover_on_start() and sent[-1] == b"VOLM21  \r" and not state.exists()

    def test_duck_level_needs_volume_set(self) -> None:
        with pytest.raises(ControlError, match="volume_set"):
            NetworkIpController({"transport": "tcp", "commands": {}, "duck_level": 4}, tcp=lambda p: b"")


class _FakeTv:
    """A set with a login, one connection at a time, that may hang up at will."""

    def __init__(self, busy: bool = False, hang_up_after_each: bool = False) -> None:
        self.server = socket.socket()
        self.server.bind(("127.0.0.1", 0))
        self.server.listen(4)
        self.port = self.server.getsockname()[1]
        self.busy, self.hang_up = busy, hang_up_after_each
        self.connections = 0
        self.received: list[bytes] = []
        threading.Thread(target=self._serve, daemon=True).start()

    def _serve(self) -> None:
        while True:
            try:
                sock, _ = self.server.accept()
            except OSError:
                return
            self.connections += 1
            with sock:
                sock.settimeout(5.0)
                try:
                    if self.busy:
                        continue
                    sock.sendall(b"Login:")
                    self._read_field(sock)
                    sock.sendall(b"\r\nPassword:")
                    self._read_field(sock)
                    sock.sendall(b"\r\n")
                    while True:
                        data = sock.recv(64)
                        if not data:
                            break
                        self.received.append(data)
                        sock.sendall(b"19\r" if data.startswith(b"VOLM?") else b"OK\r")
                        if self.hang_up:
                            break
                except OSError:
                    pass

    def _read_field(self, sock: socket.socket) -> bytes:
        buf = b""
        while not buf.endswith(b"\r"):
            chunk = sock.recv(1)
            if not chunk:
                raise OSError("client gone")
            buf += chunk
        return buf

    def close(self) -> None:
        self.server.close()


class TestPersistentConnection:
    def test_one_connection_serves_many_commands(self) -> None:
        tv = _FakeTv()
        try:
            tcp = PersistentTcp("127.0.0.1", tv.port, 3.0, ("me", "pw"))
            assert tcp(b"VOLM?   \r") == b"19\r"
            assert tcp(b"VOLM4   \r") == b"OK\r"
            assert tcp(b"VOLM19  \r") == b"OK\r"
            assert tv.connections == 1 and tcp.connections == 1
            tcp.close()
        finally:
            tv.close()

    def test_a_set_that_hangs_up_is_reconnected(self) -> None:
        tv = _FakeTv(hang_up_after_each=True)
        try:
            tcp = PersistentTcp("127.0.0.1", tv.port, 3.0, ("me", "pw"))
            assert tcp(b"VOLM4   \r") == b"OK\r"
            assert tcp(b"VOLM19  \r") == b"OK\r"
            assert tv.connections >= 2
            tcp.close()
        finally:
            tv.close()

    def test_a_busy_set_is_named_not_blamed_on_the_password(self) -> None:
        tv = _FakeTv(busy=True)
        try:
            tcp = PersistentTcp("127.0.0.1", tv.port, 3.0, ("me", "pw"))
            with pytest.raises(ControlError, match="one control connection"):
                tcp(b"VOLM?   \r")
        finally:
            tv.close()


class _RecordingDetector(BlackFrameDetector):
    """A detector that remembers what the engine told it about the volume."""

    def __init__(self) -> None:
        super().__init__(BlackFrameConfig())
        self.ducks: list[tuple[float, bool]] = []

    def audio_ducked(self, ts: float, ducked: bool) -> None:
        self.ducks.append((ts, ducked))


def _room_pipeline(hears_room: bool) -> tuple[Pipeline, _RecordingDetector]:
    fusion_cfg = FusionConfig()
    detector = _RecordingDetector()
    fusion = Fusion(fusion_cfg, {}, [detector.name])
    pipeline = Pipeline(
        [detector], fusion, AdStateMachine(fusion_cfg), NullController(), hears_room=hears_room
    )
    return pipeline, detector


def test_room_mic_detectors_hear_about_every_duck() -> None:
    """ADR 0016: with a microphone in the room, every duck and restore reaches
    the detectors; with a line tap nothing does, because the tap never changes."""
    pipeline, detector = _room_pipeline(hears_room=True)
    pipeline.set_override("mute")
    pipeline.set_override("unmute")
    pipeline.set_override("auto")  # reconcile: the machine is not muted
    assert [d for _, d in detector.ducks] == [True, False, False]

    pipeline, detector = _room_pipeline(hears_room=False)
    pipeline.set_override("mute")
    assert detector.ducks == []
