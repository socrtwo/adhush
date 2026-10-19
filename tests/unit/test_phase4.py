"""ADR 0023/0024: the rating box, ad units, uniform separators, cutscenes,
stereo width, the watermark probe, the schedule, SCTE-35 and the crowd."""

from __future__ import annotations

import json
import threading
import urllib.request
from pathlib import Path

import numpy as np

from adhush.capture.file_replay import write_fixture
from adhush.cli import main
from adhush.config import (
    AdUnitsConfig,
    BlackFrameConfig,
    CrowdConfig,
    CutsceneConfig,
    RatingBugConfig,
    ScheduleConfig,
    Scte35Config,
    StereoWidthConfig,
    WatermarkConfig,
)
from adhush.crowd.server import serve
from adhush.detect.ad_units import AdUnitsDetector, unit_fit
from adhush.detect.black_frame import BlackFrameDetector
from adhush.detect.crowd import CrowdDetector, channel_hash
from adhush.detect.cutscene import CutsceneDetector, CutsceneTemplate, correlation, thumbnail
from adhush.detect.rating_bug import RatingBugDetector
from adhush.detect.schedule import ScheduleDetector
from adhush.detect.scte35 import Scte35Detector
from adhush.detect.stereo_width import StereoWidthDetector, stereo_width
from adhush.detect.watermark import WatermarkDetector
from adhush.events import AudioEvent, CueEvent, FrameEvent
from adhush.util.mpegts import TsDemuxer, parse_splice_info
from adhush.util.xmltv import Schedule, parse_time
from tests.synth import HEIGHT, RATE, WIDTH, Timeline, synthesize, title_card


def _frames(kinds: Timeline) -> tuple[np.ndarray, np.ndarray]:
    frames, frame_ts, _, _ = synthesize(kinds, audio=False)
    assert frames is not None and frame_ts is not None
    return frames, frame_ts


# --- uniform separators ------------------------------------------------------

class TestUniformFrames:
    def test_a_white_flash_is_a_separator(self) -> None:
        d = BlackFrameDetector(BlackFrameConfig(min_run_frames=2, uniform_spread=6.0))
        frames, ts = _frames([("program", 1.0), ("flash", 0.5), ("program", 1.0)])
        seen = []
        for f, t in zip(frames, ts, strict=False):
            d.observe_frame(FrameEvent(ts=float(t), frame=f))
            seen.append((float(t), d.vote(float(t))))
        during = [v for t, v in seen if 1.05 <= t < 1.5]
        assert during and all(v.confidence == 1.0 and v.reason.startswith("uniform_run") for v in during)
        off = BlackFrameDetector(BlackFrameConfig(min_run_frames=2, uniform_spread=0.0))
        for f, t in zip(frames, ts, strict=False):
            off.observe_frame(FrameEvent(ts=float(t), frame=f))
            assert off.vote(float(t)).confidence == 0.0  # white is not black when the cue is off

    def test_a_busy_dark_scene_is_not_uniform(self) -> None:
        d = BlackFrameDetector(BlackFrameConfig(min_run_frames=1))
        dark = np.random.default_rng(3).integers(0, 60, (HEIGHT, WIDTH), dtype=np.uint8)
        d.observe_frame(FrameEvent(ts=0.0, frame=dark))
        assert d.vote(0.0).confidence == 0.0


# --- the rating box ----------------------------------------------------------------

class TestRatingBug:
    def _cfg(self) -> RatingBugConfig:
        return RatingBugConfig(appear_after_s=2.0, min_present_s=0.5, hold_s=6.0)

    def test_box_after_a_break_is_programme_evidence(self) -> None:
        d = RatingBugDetector(self._cfg())
        frames, ts = _frames([("program", 8.0), ("boundary", 1.0), ("ad", 15.0), ("rated", 3.0), ("program", 8.0)])
        present_at: list[float] = []
        for f, t in zip(frames, ts, strict=False):
            d.observe_frame(FrameEvent(ts=float(t), frame=f))
            if d.program_present:
                present_at.append(float(t))
        assert present_at, "the box was never seen"
        assert 24.4 <= min(present_at) <= 25.2  # box at 24.0 + min_present_s, sampled
        assert max(present_at) <= 24.5 + 3.0 + 6.0 + 0.3
        assert not any(t < 24.0 for t in present_at)
        assert not d.voting  # inert once the hold is over

    def test_a_permanently_bright_corner_is_scenery(self) -> None:
        d = RatingBugDetector(self._cfg())
        frames, ts = _frames([("rated", 12.0)])
        for f, t in zip(frames, ts, strict=False):
            d.observe_frame(FrameEvent(ts=float(t), frame=f))
            assert not d.program_present


# --- ad units --------------------------------------------------------------------

class TestAdUnits:
    def test_unit_fit(self) -> None:
        assert unit_fit(30.4, 1.2) == 30.0
        assert unit_fit(22.0, 1.2) is None
        assert unit_fit(119.0, 1.2) == 120.0

    def _silence(self, ts: float) -> AudioEvent:
        return AudioEvent(ts=ts, samples=np.zeros(800, dtype=np.float32), sample_rate=RATE)

    def _tone(self, ts: float) -> AudioEvent:
        t = np.arange(800) / RATE
        return AudioEvent(ts=ts, samples=(0.2 * np.sin(2 * np.pi * 440 * t)).astype(np.float32), sample_rate=RATE)

    def test_separators_on_the_grid_vote_and_the_hold_runs_out(self) -> None:
        d = AdUnitsDetector(AdUnitsConfig(hold_s=35.0))
        ts = 0.0

        def gap(at: float) -> None:
            nonlocal ts
            while ts < at:
                d.observe_audio(self._tone(ts))
                ts += 0.1
            for _ in range(5):  # half a second of silence
                d.observe_audio(self._silence(ts))
                ts += 0.1

        gap(10.0)
        assert not d.voting
        gap(40.0)  # 30 s later
        v = d.vote(ts)
        assert d.voting and v.confidence == 0.5 and "units=30" in v.reason
        gap(55.2)  # a 15 s unit, 0.2 s late
        v = d.vote(ts)
        assert v.confidence == 1.0 and "units=30,15" in v.reason and d.units == [30.0, 15.0]
        gap(77.0)  # 21.8 s: off the grid — the rhythm broke
        assert not d.voting
        gap(107.0)
        gap(137.0)
        assert d.vote(ts).confidence == 1.0
        assert d.vote(ts + 40.0).confidence == 0.0  # past the hold with no next separator
        assert not d.voting

    def test_black_frames_are_separators_too(self) -> None:
        d = AdUnitsDetector(AdUnitsConfig())
        frames, ts = _frames([("program", 5.0), ("boundary", 0.5), ("program", 29.5), ("boundary", 0.5), ("program", 5.0)])
        for f, t in zip(frames, ts, strict=False):
            d.observe_frame(FrameEvent(ts=float(t), frame=f))
        assert d.voting and "units=30" in d.vote(float(ts[-1])).reason


# --- cutscenes ---------------------------------------------------------------------

class TestCutscene:
    def test_template_round_trip_and_match(self, tmp_path: Path) -> None:
        card = title_card()
        t = CutsceneTemplate.from_frame("title", "out", card)
        path = t.save(tmp_path)
        back = CutsceneTemplate.load(path)
        assert back.kind == "out" and back.hash == t.hash and correlation(back.thumb, thumbnail(card)) > 0.99
        d = CutsceneDetector(CutsceneConfig(directory=str(tmp_path), close_hold_s=4.0))
        assert not d.voting
        d.observe_frame(FrameEvent(ts=1.0, frame=card))
        assert d.program_present and d.voting and d.vote(1.0).reason.startswith("cutscene_out")
        plain = np.full((HEIGHT, WIDTH), 120, dtype=np.uint8)
        assert d.match(plain) is None
        for i in range(30):
            d.observe_frame(FrameEvent(ts=1.5 + i * 0.25, frame=plain))
        assert not d.program_present and not d.voting

    def test_an_in_template_ducks(self, tmp_path: Path) -> None:
        card = title_card()
        CutsceneTemplate.from_frame("brb", "in", card).save(tmp_path)
        d = CutsceneDetector(CutsceneConfig(directory=str(tmp_path), hold_s=5.0))
        d.observe_frame(FrameEvent(ts=2.0, frame=card))
        v = d.vote(2.0)
        assert d.voting and v.confidence == 1.0 and "cutscene_in name=brb" in v.reason
        d.user_says_program(3.0)
        assert d.vote(3.0).confidence == 0.0

    def test_cli_adds_and_lists(self, tmp_path: Path, capsys, monkeypatch) -> None:
        monkeypatch.chdir(tmp_path)
        frames, frame_ts, samples, _ = synthesize([("program", 1.0), ("card", 1.0)])
        fixture = tmp_path / "f.npz"
        write_fixture(fixture, frames=frames, frame_ts=frame_ts, audio=samples, audio_rate=RATE)
        (tmp_path / "adhush.toml").write_text('[detect.cutscene]\ndirectory = "cuts"\n')
        assert main(["cutscene", "add", "--config", "adhush.toml", "--name", "title", "--kind", "out", "--from", str(fixture), "--ts", "1.2"]) == 0
        assert (tmp_path / "cuts" / "title.npz").is_file()
        assert main(["cutscene", "list", "--config", "adhush.toml"]) == 0
        assert "title" in capsys.readouterr().out


# --- stereo width -------------------------------------------------------------------

class TestStereoWidth:
    def test_width_measure(self) -> None:
        t = np.arange(800) / RATE
        mono = np.repeat(np.sin(2 * np.pi * 440 * t), 2).astype(np.float32)
        assert stereo_width(mono) < 1e-6
        wide = np.empty(1600, dtype=np.float32)
        wide[0::2] = np.sin(2 * np.pi * 440 * t)
        wide[1::2] = -np.sin(2 * np.pi * 440 * t)
        assert stereo_width(wide) >= 1.9

    def _blocks(self, d: StereoWidthDetector, ts: float, n: int, width: float | None) -> float:
        for _ in range(n):
            d.observe_audio(AudioEvent(ts=ts, samples=np.zeros(800, dtype=np.float32), sample_rate=RATE, width=width))
            ts += 0.1
        return ts

    def test_a_switch_votes_until_it_switches_back(self) -> None:
        d = StereoWidthDetector(StereoWidthConfig(window_s=1.0, baseline_s=30.0, confirm_s=1.0))
        ts = self._blocks(d, 0.0, 200, 0.05)  # 20 s of near-mono studio
        assert not d.voting and abs((d.baseline or 1.0) - 0.05) < 0.01
        ts = self._blocks(d, ts, 30, 0.45)  # a wide spot
        v = d.vote(ts)
        assert d.voting and v.confidence == 1.0 and v.reason.startswith("width_switched")
        ts = self._blocks(d, ts, 30, 0.05)
        assert not d.voting

    def test_inert_without_width(self) -> None:
        d = StereoWidthDetector(StereoWidthConfig())
        self._blocks(d, 0.0, 50, None)
        assert not d.voting and d.vote(5.0).reason == "width_unknown"


# --- the watermark probe ---------------------------------------------------------------

def _marked(bits: list[int], luma: int = 120) -> np.ndarray:
    frame = np.full((HEIGHT, WIDTH), luma, dtype=np.uint8)
    cell = WIDTH / len(bits)
    for i, b in enumerate(bits):
        frame[:2, int(i * cell) : int((i + 1) * cell)] = 210 if b else 30
    return frame


class TestWatermark:
    def test_presence_lost_on_a_marked_channel_votes(self) -> None:
        cfg = WatermarkConfig(min_baseline_s=5.0, confirm_s=1.0, sample_interval_s=0.5)
        d = WatermarkDetector(cfg)
        run_in = [(0xEB52 >> i) & 1 for i in range(15, -1, -1)]
        rng = np.random.default_rng(5)
        ts = 0.0
        for _ in range(40):  # 20 s marked: the run-in, then payload bits that change per frame
            bits = run_in[:] if rng.random() < 0.5 else [int(b) for b in rng.integers(0, 2, 16)]
            d.observe_frame(FrameEvent(ts=ts, frame=_marked(bits)))
            ts += 0.5
        assert d.baseline_present is True and not d.voting
        plain = np.full((HEIGHT, WIDTH), 120, dtype=np.uint8)
        for _ in range(6):
            d.observe_frame(FrameEvent(ts=ts, frame=plain))
            ts += 0.5
        v = d.vote(ts)
        assert d.voting and v.confidence == 1.0 and v.reason.startswith("watermark_absent")
        d.observe_frame(FrameEvent(ts=ts, frame=_marked(run_in)))
        assert not d.voting

    def test_inert_on_an_unmarked_channel(self) -> None:
        d = WatermarkDetector(WatermarkConfig(min_baseline_s=2.0))
        frames, ts = _frames([("program", 10.0), ("ad", 5.0)])
        for f, t in zip(frames, ts, strict=False):
            d.observe_frame(FrameEvent(ts=float(t), frame=f))
            assert not d.voting
        assert d.baseline_present is False


# --- the schedule ----------------------------------------------------------------

XMLTV = """<?xml version="1.0" encoding="UTF-8"?>
<tv>
  <channel id="I123.msnow"><display-name>MS NOW</display-name></channel>
  <channel id="I5.pbs"><display-name>PBS</display-name></channel>
  <programme start="20260916200000 +0000" stop="20260916210000 +0000" channel="I123.msnow"><title>The Nine</title></programme>
  <programme start="20260916210000 +0000" stop="20260916220000 +0000" channel="I123.msnow"><title>Ten</title></programme>
  <programme start="20260916200000 +0000" stop="20260916210000 +0000" channel="I5.pbs"><title>Nature</title></programme>
</tv>
"""


class TestSchedule:
    def test_parse_time(self) -> None:
        assert parse_time("20260916200000 +0000") == 1789588800.0
        assert parse_time("20260916160000 -0400") == parse_time("20260916200000 +0000")

    def test_grace_after_a_start_and_ad_free(self, tmp_path: Path) -> None:
        path = tmp_path / "guide.xml"
        path.write_text(XMLTV)
        sched = Schedule.load(path)
        assert sched.resolve("ms now") == "I123.msnow"
        t0 = parse_time("20260916210000 +0000")
        d = ScheduleDetector(ScheduleConfig(file=str(path), channel="MS NOW", start_grace_s=90.0, ad_free=("PBS",)))
        d.tick(t0 + 30.0)
        v = d.vote(0.0)
        assert d.voting and "programme_started title='Ten'" in v.reason and not d.ad_free_now
        d.tick(t0 + 200.0)
        assert not d.voting and "Ten" in d.describe()
        pbs = ScheduleDetector(ScheduleConfig(file=str(path), channel="pbs", ad_free=("PBS",)))
        pbs.tick(t0 - 100.0)
        assert pbs.ad_free_now
        off = ScheduleDetector(ScheduleConfig())
        off.tick(t0)
        assert not off.voting and off.describe() == "no schedule file"


# --- SCTE-35 ----------------------------------------------------------------------

class _Bits:
    def __init__(self) -> None:
        self.bits: list[int] = []

    def put(self, value: int, n: int) -> _Bits:
        for i in range(n - 1, -1, -1):
            self.bits.append((value >> i) & 1)
        return self

    def bytes(self) -> bytes:
        while len(self.bits) % 8:
            self.bits.append(0)
        return bytes(int("".join(map(str, self.bits[i : i + 8])), 2) for i in range(0, len(self.bits), 8))


def splice_insert_section(out_of_network: bool, duration_s: float | None, event_id: int = 7) -> bytes:
    cmd = _Bits().put(event_id, 32).put(0, 1).put(0, 7)
    cmd.put(int(out_of_network), 1).put(1, 1).put(int(duration_s is not None), 1).put(1, 1).put(0, 4)  # program splice, immediate
    if duration_s is not None:
        cmd.put(0, 1).put(0, 6).put(int(duration_s * 90000), 33)
    cmd.put(1, 16).put(1, 8).put(1, 8)
    command = cmd.bytes()
    body = _Bits().put(0, 8).put(0, 1).put(0, 6).put(0, 33).put(0xFF, 8).put(0xFFF, 12).put(len(command), 12).put(0x05, 8).bytes()
    tail = command + b"\x00\x00" + b"\x00\x00\x00\x00"  # no descriptors, CRC
    length = len(body) + len(tail)
    head = _Bits().put(0xFC, 8).put(0, 1).put(0, 1).put(3, 2).put(length, 12).bytes()
    return head + body + tail


def ts_packet(pid: int, payload: bytes, pusi: bool = True, cc: int = 0) -> bytes:
    header = bytes([0x47, (0x40 if pusi else 0) | (pid >> 8), pid & 0xFF, 0x10 | (cc & 0xF)])
    body = (b"\x00" if pusi else b"") + payload
    return header + body + b"\xff" * (188 - 4 - len(body))


def pat_section(pmt_pid: int) -> bytes:
    body = bytes([0x00, 0x01, 0xC1, 0x00, 0x00]) + bytes([0x00, 0x01, 0xE0 | (pmt_pid >> 8), pmt_pid & 0xFF])
    length = len(body) + 4
    return bytes([0x00, 0xB0 | (length >> 8), length & 0xFF]) + body + b"\x00" * 4


def pmt_section(scte_pid: int) -> bytes:
    body = bytes([0x00, 0x01, 0xC1, 0x00, 0x00, 0xE1, 0x00, 0xF0, 0x00])
    body += bytes([0x1B, 0xE1, 0x00, 0xF0, 0x00])  # video
    body += bytes([0x86, 0xE0 | (scte_pid >> 8), scte_pid & 0xFF, 0xF0, 0x00])  # SCTE-35
    length = len(body) + 4
    return bytes([0x02, 0xB0 | (length >> 8), length & 0xFF]) + body + b"\x00" * 4


class TestScte35:
    def test_parse_splice_insert(self) -> None:
        cues = parse_splice_info(splice_insert_section(True, 30.0))
        assert len(cues) == 1 and cues[0].start and abs((cues[0].duration_s or 0) - 30.0) < 1e-3 and cues[0].event_id == 7
        back = parse_splice_info(splice_insert_section(False, None))
        assert len(back) == 1 and not back[0].start and back[0].duration_s is None

    def test_demux_follows_pat_and_pmt(self) -> None:
        demux = TsDemuxer()
        stream = b"\x00\x11" + ts_packet(0, pat_section(0x100)) + ts_packet(0x100, pmt_section(0x200)) + ts_packet(0x200, splice_insert_section(True, 15.0))
        demux.push(stream[:100])
        demux.push(stream[100:])
        cues = list(demux.drain())
        assert demux.scte35_pids == {0x200} and len(cues) == 1 and cues[0].start

    def test_detector_votes_out_then_sees_in(self) -> None:
        d = Scte35Detector(Scte35Config(in_hold_s=5.0))
        assert not d.voting
        d.observe_cue(CueEvent(ts=10.0, kind="ad_start", duration_s=30.0, detail="splice_insert"))
        v = d.vote(12.0)
        assert d.voting and v.confidence == 1.0 and v.reason.startswith("scte35_out")
        assert d.vote(46.0).confidence == 0.0  # duration + 5 s ran out
        d.observe_cue(CueEvent(ts=50.0, kind="ad_end"))
        d.vote(51.0)
        assert d.program_present and d.voting
        d.vote(60.0)
        assert not d.program_present


# --- the crowd -------------------------------------------------------------------------

class TestCrowd:
    def test_two_other_devices_make_a_break(self) -> None:
        server = serve("127.0.0.1", 0)
        port = server.server_address[1]
        thread = threading.Thread(target=server.serve_forever, daemon=True)
        thread.start()
        try:
            url = f"http://127.0.0.1:{port}"
            cfg = CrowdConfig(url=url, channel="MS NOW", min_reports=2, window_s=45.0, poll_s=0.0)
            h = channel_hash("MS NOW", cfg.salt)
            now = 1_800_000_000.0

            def post(device: str, kind: str, ts: float) -> None:
                req = urllib.request.Request(f"{url}/report", data=json.dumps({"hash": h, "kind": kind, "ts": ts, "device": device}).encode(), headers={"Content-Type": "application/json"})
                urllib.request.urlopen(req, timeout=4).close()

            d = CrowdDetector(cfg, device_id="me")
            d._wall = now
            post("a", "start", now - 5)
            d._fetch(now - 100)
            assert not d.voting  # one device is not a crowd
            post("b", "start", now - 3)
            post("me", "start", now - 1)  # our own report never counts
            d._fetch(now - 100)
            v = d.vote(0.0)
            assert d.voting and v.confidence == 1.0 and "devices=2" in v.reason
            post("a", "end", now + 1)
            post("b", "end", now + 2)
            d._wall = now + 3
            d._fetch(now - 100)
            assert d.program_present and d.vote(1.0).confidence == 0.0
            with urllib.request.urlopen(f"{url}/recent?prefix={h[:4]}&since=0", timeout=4) as resp:
                body = json.loads(resp.read())
            assert len(body["reports"]) == 5
            off = CrowdDetector(CrowdConfig())
            off.tick(now)
            assert not off.enabled and not off.voting
        finally:
            server.shutdown()
            server.server_close()
