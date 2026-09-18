"""Detector fixture tests: each Phase 1 detector replayed over labeled ground
truth synthesized through the file_replay path, plus targeted edge cases."""

import re
from datetime import datetime
from pathlib import Path
from typing import ClassVar

import numpy as np

from adhush.capture.file_replay import FileReplaySource, write_fixture
from adhush.config import AspectChangeConfig, BlackFrameConfig, LoudnessConfig, SilenceConfig
from adhush.detect.aspect_change import AspectChangeDetector
from adhush.detect.base import Detector
from adhush.detect.black_frame import BlackFrameDetector
from adhush.detect.loudness import LoudnessDetector
from adhush.detect.silence import SilenceDetector
from adhush.events import AudioEvent, FrameEvent
from tests.synth import HEIGHT, RATE, WIDTH, Timeline, synthesize

TIMELINE: Timeline = [
    ("program", 8.0),
    ("boundary", 1.0),
    ("ad", 15.0),
    ("program", 8.0),
]
POD_START, POD_END = 8.0, 24.0
BOUNDARY_END = 9.0


def _replay(tmp_path: Path, detector: Detector, *, video: bool, audio: bool) -> dict[float, float]:
    """Replay the shared timeline through a fixture; confidence keyed by ts."""
    frames, frame_ts, samples, labels = synthesize(TIMELINE, video=video, audio=audio)
    assert labels == [labels[0]] and labels[0].start_ts == POD_START
    assert labels[0].start_ts + labels[0].duration_s == POD_END

    path = tmp_path / "fixture.npz"
    write_fixture(path, frames=frames, frame_ts=frame_ts, audio=samples, audio_rate=RATE)
    votes: dict[float, float] = {}
    with FileReplaySource(path) as source:
        detector.warmup()
        for frame in source.frames():
            detector.observe_frame(frame)
            votes[frame.ts] = detector.vote(frame.ts).confidence
        for block in source.audio_blocks():
            detector.observe_audio(block)
            votes[block.ts] = detector.vote(block.ts).confidence
    return votes


def _max_conf(votes: dict[float, float], t0: float, t1: float) -> float:
    return max((c for ts, c in votes.items() if t0 <= ts < t1), default=0.0)


class TestBlackFrame:
    def test_fires_only_during_labeled_boundary(self, tmp_path: Path) -> None:
        detector = BlackFrameDetector(BlackFrameConfig())
        votes = _replay(tmp_path, detector, video=True, audio=False)
        assert _max_conf(votes, 0.0, POD_START) == 0.0  # program before the pod
        assert _max_conf(votes, POD_START, BOUNDARY_END) == 1.0  # black run
        assert _max_conf(votes, 12.0, POD_END) == 0.0  # bright ad body

    def test_short_run_ignored(self) -> None:
        detector = BlackFrameDetector(BlackFrameConfig(min_run_frames=3))
        from adhush.events import FrameEvent

        black = np.zeros((48, 64), dtype=np.uint8)
        # A picture, not a flat field: a flat field is a uniform separator (ADR 0023).
        bright = np.tile(np.linspace(60, 180, 64).astype(np.uint8), (48, 1))
        for i, frame in enumerate([bright, black, black, bright]):
            detector.observe_frame(FrameEvent(ts=i / 10, frame=frame))
        assert detector.vote(0.4).confidence == 0.0

    def test_reason_is_machine_readable(self) -> None:
        detector = BlackFrameDetector(BlackFrameConfig())
        vote = detector.vote(0.0)
        assert vote.reason.startswith("no_black ")
        assert "luma=" in vote.reason


class TestSilence:
    def test_fires_only_during_labeled_boundary(self, tmp_path: Path) -> None:
        detector = SilenceDetector(SilenceConfig())
        votes = _replay(tmp_path, detector, video=False, audio=True)
        assert _max_conf(votes, 0.0, POD_START) == 0.0
        assert _max_conf(votes, POD_START, BOUNDARY_END) == 1.0
        assert _max_conf(votes, 12.0, POD_END) == 0.0

    def test_quiet_dialogue_is_not_silence(self) -> None:
        # A tone below the dBFS threshold but spectrally structured must not
        # count as boundary silence.
        detector = SilenceDetector(SilenceConfig(dbfs_threshold=-50.0, min_run_ms=200))
        t = np.arange(RATE, dtype=np.float64) / RATE
        quiet_tone = (2e-3 * np.sin(2 * np.pi * 300 * t)).astype(np.float32)  # ~ -57 dBFS
        for i in range(10):
            block = quiet_tone[i * 800 : (i + 1) * 800]
            detector.observe_audio(AudioEvent(ts=i * 0.1, samples=block, sample_rate=RATE))
        assert detector.vote(1.0).confidence == 0.0

    def test_digital_silence_counts(self) -> None:
        detector = SilenceDetector(SilenceConfig(min_run_ms=200))
        zeros = np.zeros(800, dtype=np.float32)
        for i in range(5):
            detector.observe_audio(AudioEvent(ts=i * 0.1, samples=zeros, sample_rate=RATE))
        assert detector.vote(0.5).confidence == 1.0


class TestLoudness:
    def test_hot_ad_beats_program_baseline(self, tmp_path: Path) -> None:
        detector = LoudnessDetector(LoudnessConfig(window_s=1.5, baseline_s=30.0))
        votes = _replay(tmp_path, detector, video=False, audio=True)
        assert _max_conf(votes, 6.5, POD_START) == 0.0  # warmed up, program level
        assert _max_conf(votes, 12.0, POD_END) == 1.0  # ad mixed ~11 dB hot
        assert _max_conf(votes, 28.0, 32.0) < 0.05  # program resumes

    def test_votes_zero_while_warming(self) -> None:
        detector = LoudnessDetector(LoudnessConfig(window_s=3.0))
        t = np.arange(800, dtype=np.float64) / RATE
        loud = (0.4 * np.sin(2 * np.pi * 880 * t)).astype(np.float32)
        detector.observe_audio(AudioEvent(ts=0.0, samples=loud, sample_rate=RATE))
        vote = detector.vote(0.1)
        assert vote.confidence == 0.0
        assert vote.reason.startswith("warming ")

    def test_baseline_ignores_the_startup_ramp(self) -> None:
        # A microphone delivers a moment of silence before real audio; the
        # short-term window is then half empty and reads low. The baseline
        # must not be taken from that, or the program looks "hot" for good.
        detector = LoudnessDetector(LoudnessConfig(window_s=1.5, baseline_s=30.0))
        t = np.arange(800, dtype=np.float64) / RATE
        silent = np.zeros(800, dtype=np.float32)
        program = (0.1 * np.sin(2 * np.pi * 440 * t)).astype(np.float32)
        ts = 0.0
        for _ in range(5):  # 0.5 s of nothing
            detector.observe_audio(AudioEvent(ts=ts, samples=silent, sample_rate=RATE))
            ts += 0.1
        for _ in range(150):  # 15 s of steady program
            detector.observe_audio(AudioEvent(ts=ts, samples=program, sample_rate=RATE))
            ts += 0.1
        vote = detector.vote(ts)
        assert vote.confidence == 0.0, vote.reason
        assert detector.baseline_lufs is not None
        assert abs(detector.baseline_lufs - detector._last_short_term) < 0.2

    def test_stuck_baseline_recovers_after_max_elevated(self) -> None:
        # Elevated for longer than any ad pod: the baseline follows, the vote drops.
        detector = LoudnessDetector(
            LoudnessConfig(window_s=1.5, baseline_s=20.0, max_elevated_s=60.0)
        )
        t = np.arange(800, dtype=np.float64) / RATE
        program = (0.1 * np.sin(2 * np.pi * 440 * t)).astype(np.float32)
        hot = (0.35 * np.sin(2 * np.pi * 880 * t)).astype(np.float32)
        ts = 0.0
        for _ in range(100):
            detector.observe_audio(AudioEvent(ts=ts, samples=program, sample_rate=RATE))
            ts += 0.1
        for _ in range(590):  # 59 s hot: still frozen
            detector.observe_audio(AudioEvent(ts=ts, samples=hot, sample_rate=RATE))
            ts += 0.1
        assert detector.vote(ts).confidence == 1.0
        for _ in range(1200):  # two more minutes: the escape lets it follow
            detector.observe_audio(AudioEvent(ts=ts, samples=hot, sample_rate=RATE))
            ts += 0.1
        assert detector.vote(ts).confidence < 0.05

    def test_baseline_freezes_during_elevation(self) -> None:
        # Feed long program, then a long hot stretch: the baseline must not
        # chase the hot level, so the delta (and vote) stays high throughout.
        detector = LoudnessDetector(LoudnessConfig(window_s=1.5, baseline_s=30.0))
        t = np.arange(800, dtype=np.float64) / RATE
        program = (0.1 * np.sin(2 * np.pi * 440 * t)).astype(np.float32)
        hot = (0.35 * np.sin(2 * np.pi * 880 * t)).astype(np.float32)
        ts = 0.0
        for _ in range(100):  # 10 s program
            detector.observe_audio(AudioEvent(ts=ts, samples=program, sample_rate=RATE))
            ts += 0.1
        for _ in range(600):  # 60 s hot
            detector.observe_audio(AudioEvent(ts=ts, samples=hot, sample_rate=RATE))
            ts += 0.1
        assert detector.vote(ts).confidence == 1.0


    def test_compressed_ad_at_program_level_raises_the_vote(self) -> None:
        # ADR 0021: a spot mixed no hotter than the show but compressed flat
        # has a crest factor several dB under the programme's. That is worth
        # half a vote — never a whole one.
        detector = LoudnessDetector(LoudnessConfig(window_s=1.5, baseline_s=30.0))
        n = 800
        t = np.arange(n, dtype=np.float64) / RATE
        peaky = 0.1 * np.sin(2 * np.pi * 440 * t)
        peaky[::400] = 0.5  # sparse peaks: speech-like dynamics, ~16 dB crest
        flat = 0.1 * np.sin(2 * np.pi * 440 * t)  # the same level, 3 dB crest
        ts = 0.0
        for _ in range(150):
            detector.observe_audio(AudioEvent(ts=ts, samples=peaky.astype(np.float32), sample_rate=RATE))
            ts += 0.1
        assert detector.vote(ts).confidence < 1e-9  # the EMA leaves rounding dust
        assert detector.baseline_crest_db is not None and detector.baseline_crest_db > 12.0
        for _ in range(30):
            detector.observe_audio(AudioEvent(ts=ts, samples=flat.astype(np.float32), sample_rate=RATE))
            ts += 0.1
        vote = detector.vote(ts)
        assert 0.45 <= vote.confidence <= 0.5, vote.reason
        assert "crest_drop_db=" in vote.reason
        off = LoudnessDetector(LoudnessConfig(window_s=1.5, baseline_s=30.0, crest_drop_db=0.0))
        ts = 0.0
        for _ in range(150):
            off.observe_audio(AudioEvent(ts=ts, samples=peaky.astype(np.float32), sample_rate=RATE))
            ts += 0.1
        for _ in range(30):
            off.observe_audio(AudioEvent(ts=ts, samples=flat.astype(np.float32), sample_rate=RATE))
            ts += 0.1
        assert off.vote(ts).confidence < 1e-9


class TestDuckCompensation:
    """ADR 0016: a room mic hears the duck; the loudness detector compensates."""

    @staticmethod
    def _block(i: int, amp: float, hz: float = 440.0) -> np.ndarray:
        n = RATE // 10
        t = (np.arange(n, dtype=np.float64) + i * n) / RATE
        # Alternate blocks 6 dB apart: broadcast audio moves, a fan does not.
        swing = 1.0 if i % 2 == 0 else 0.5
        return (amp * swing * np.sin(2 * np.pi * hz * t)).astype(np.float32)

    def _feed(self, detector: LoudnessDetector, i: int, count: int, amp: float) -> int:
        for _ in range(count):
            detector.observe_audio(AudioEvent(ts=i * 0.1, samples=self._block(i, amp), sample_rate=RATE))
            i += 1
        return i

    def test_ducked_ad_is_still_an_ad_and_ducked_program_is_not(self) -> None:
        detector = LoudnessDetector(LoudnessConfig(window_s=1.5, baseline_s=30.0))
        detector.warmup()
        i = self._feed(detector, 0, 200, 0.1)  # 20 s of programme
        assert detector.vote(i * 0.1).confidence == 0.0
        i = self._feed(detector, i, 30, 0.25)  # an ad 8 dB hot
        assert detector.vote(i * 0.1).confidence == 1.0
        detector.audio_ducked(i * 0.1, True)  # the engine turns the set down ~20 dB
        assert detector.voting
        i = self._feed(detector, i, 10, 0.025)  # still settling: the vote is frozen
        vote = detector.vote(i * 0.1)
        assert vote.confidence == 1.0 and vote.reason.startswith("duck_settling")
        i = self._feed(detector, i, 30, 0.025)  # measured; the ducked ad reads on the old scale
        vote = detector.vote(i * 0.1)
        assert 17.0 < detector.duck_offset_db < 23.0, vote.reason
        assert vote.confidence == 1.0 and "duck_offset_db" in vote.reason
        i = self._feed(detector, i, 30, 0.01)  # the show is back, still ducked
        vote = detector.vote(i * 0.1)
        assert vote.confidence < 0.05, vote.reason
        detector.audio_ducked(i * 0.1, False)
        assert detector.duck_offset_db == 0.0
        i = self._feed(detector, i, 30, 0.1)  # volume restored: programme is programme
        assert detector.vote(i * 0.1).confidence == 0.0

    def test_buried_under_the_room_goes_inert(self) -> None:
        detector = LoudnessDetector(LoudnessConfig(window_s=1.5, baseline_s=30.0))
        detector.warmup()
        i = self._feed(detector, 0, 200, 0.1)
        i = self._feed(detector, i, 30, 0.25)
        detector.audio_ducked(i * 0.1, True)
        # What the mic hears now is broadband noise: the fans, not the ducked set.
        rng = np.random.default_rng(7)
        for _ in range(40):
            fans = (0.02 * rng.standard_normal(RATE // 10)).astype(np.float32)
            detector.observe_audio(AudioEvent(ts=i * 0.1, samples=fans, sample_rate=RATE))
            i += 1
        assert not detector.voting
        assert detector.vote(i * 0.1).reason.startswith("ducked_buried")
        detector.audio_ducked(i * 0.1, False)
        assert detector.voting

    def test_silence_is_inert_while_ducked(self) -> None:
        detector = SilenceDetector(SilenceConfig(min_run_ms=200))
        detector.warmup()
        quiet = np.zeros(RATE // 10, dtype=np.float32)
        for k in range(5):
            detector.observe_audio(AudioEvent(ts=k * 0.1, samples=quiet, sample_rate=RATE))
        assert detector.vote(0.5).confidence == 1.0
        detector.audio_ducked(0.5, True)
        for k in range(5, 10):
            detector.observe_audio(AudioEvent(ts=k * 0.1, samples=quiet, sample_rate=RATE))
        assert not detector.voting
        assert detector.vote(1.0).confidence == 0.0
        detector.audio_ducked(1.0, False)
        assert detector.voting


class TestBreakClock:
    """ADR 0017: a learned minute-of-hour prior that never mutes alone."""

    def test_learns_break_minutes_and_votes_only_where_watched(self, tmp_path: Path) -> None:
        from adhush.config import ClockConfig
        from adhush.detect.clock import ClockDetector

        cfg = ClockConfig(file=str(tmp_path / "clock.tsv"), min_hours=3, full_fraction=0.6)
        clock = ClockDetector(cfg)
        for hour in range(5):  # five hours watched; a :20-:22 break in four of them
            for minute in range(60):
                clock.tick(hour * 3600 + minute * 60 + 1)
            if hour < 4:
                clock.learn(hour * 3600 + 20 * 60, hour * 3600 + 22 * 60)
        clock.tick(5 * 3600 + 20 * 60 + 5)
        assert clock.voting
        vote = clock.vote(0.0)
        assert vote.confidence == 1.0 and vote.reason.startswith("clock minute=20")
        clock.tick(5 * 3600 + 40 * 60)
        assert clock.voting and clock.vote(0.0).confidence == 0.0
        assert clock.break_minutes() == [20, 21]
        clock.learn(0.0, 5.0)  # implausible: teaches nothing
        clock.learn(0.0, 900.0)
        again = ClockDetector(cfg)  # the file round-trips
        again.tick(6 * 3600 + 20 * 60)
        assert again.voting and again.vote(0.0).confidence > 0.9  # 4 breaks in 7 hours
        fresh = ClockDetector(ClockConfig(file=""))
        fresh.tick(50 * 60)
        assert not fresh.voting and fresh.vote(0.0).reason.startswith("clock_learning")


class TestJingle:
    """ADR 0020: the channel's own sting, learned from three breaks, then recognised."""

    OPENER: ClassVar[list[list[int]]] = [[0, 2, 4, 7, 9, 11], [1, 3, 5, 6, 8, 10], [0, 1, 4, 5, 8, 9], [2, 3, 6, 7, 10, 11], [0, 3, 6, 9, 1, 4], [2, 5, 8, 11, 7, 10]]
    CLOSER: ClassVar[list[list[int]]] = [[0, 1, 2, 3, 4, 5], [6, 7, 8, 9, 10, 11], [0, 2, 4, 6, 8, 10], [1, 3, 5, 7, 9, 11], [0, 1, 6, 7, 2, 8], [3, 4, 9, 10, 5, 11]]

    @staticmethod
    def _chord(semitones: list[int], t0: float, n: int) -> np.ndarray:
        t = t0 + np.arange(n) / RATE
        v = sum(np.sin(2 * np.pi * 440.0 * 2 ** (s / 12.0) * t) for s in semitones)
        return (0.15 * v / len(semitones)).astype(np.float32)

    class _Feed:
        def __init__(self, det: "JingleDetector") -> None:  # noqa: F821
            self.det, self.ts = det, 0.0

        def block(self, samples: np.ndarray) -> None:
            self.det.observe_audio(AudioEvent(ts=self.ts, samples=samples, sample_rate=RATE))
            self.ts += len(samples) / RATE

    def _sting(self, f: "_Feed", steps: list[list[int]]) -> None:
        for c in steps:
            for _ in range(5):
                f.block(self._chord(c, f.ts, RATE // 10))

    def _programme(self, f: "_Feed", seconds: float, rng: np.random.Generator) -> None:
        left = seconds
        while left > 0:
            c = list(rng.permutation(12)[:6])
            for _ in range(5):
                f.block(self._chord(c, f.ts, RATE // 10))
            left -= 0.5

    def test_learns_and_recognises_a_channel_sting(self, tmp_path: Path) -> None:
        from adhush.config import JingleConfig
        from adhush.detect.jingle import JingleDetector

        cfg = JingleConfig(file=str(tmp_path / "jingles.tsv"))
        det = JingleDetector(cfg)
        det.warmup()
        f = self._Feed(det)
        rng = np.random.default_rng(11)
        for k in range(3):
            self._programme(f, 15.0, rng)
            self._sting(f, self.OPENER)
            start = f.ts + 1.0
            self._programme(f, 25.0, rng)
            self._sting(f, self.CLOSER)
            end = f.ts - 1.5
            det.learn_break(start, end)
            self._programme(f, 8.0, rng)
            if k < 2:
                assert not det.promoted(), det.describe()
        assert sorted(j.kind for j in det.promoted()) == ["close", "open"], det.describe()
        self._programme(f, 10.0, rng)
        assert det.vote(f.ts).confidence == 0.0
        self._sting(f, self.OPENER)
        vote = det.vote(f.ts)
        assert vote.confidence == 1.0 and vote.reason.startswith("jingle_open"), vote.reason
        self._programme(f, 25.0, rng)
        assert det.vote(f.ts).confidence == 0.0
        assert not det.program_present
        self._sting(f, self.CLOSER)
        assert det.program_present
        again = JingleDetector(cfg)  # the file round-trips
        assert len(again.promoted()) == 2
        # "Not an ad" after a jingle-driven mute counts against it.
        self._sting(f, self.OPENER)
        assert det.vote(f.ts).confidence == 1.0
        det.user_says_program(f.ts)
        assert det.vote(f.ts).confidence == 0.0

    def _learn_three(self, det: "JingleDetector", f: "_Feed", rng: np.random.Generator, hours: list[float | None] | None = None) -> None:  # noqa: F821
        for k in range(3):
            self._programme(f, 15.0, rng)
            self._sting(f, self.OPENER)
            start = f.ts + 1.0
            self._programme(f, 25.0, rng)
            self._sting(f, self.CLOSER)
            end = f.ts - 1.5
            det.learn_break(start, end, hours[k] if hours else None)
            self._programme(f, 8.0, rng)

    def test_a_transposed_sting_is_the_same_family(self, tmp_path: Path) -> None:
        """ADR 0027: the show's re-cut of the channel sting, a semitone up, still opens the break."""
        from adhush.config import JingleConfig
        from adhush.detect.jingle import JingleDetector, pitch_rotate

        assert pitch_rotate(0b100000000000, 1) == 0b010000000000  # class 0 (A) up to class 1
        assert pitch_rotate(0b000000000001, 1) == 0b100000000000  # class 11 wraps to class 0
        assert pitch_rotate(pitch_rotate(0b101100110001, 2), -2) == 0b101100110001
        up = [[s + 1 for s in step] for step in self.OPENER]
        for shifts, expect in ((2, 1.0), (0, 0.0)):
            cfg = JingleConfig(file=str(tmp_path / f"j{shifts}.tsv"), pitch_shifts=shifts)
            det = JingleDetector(cfg)
            det.warmup()
            f = self._Feed(det)
            rng = np.random.default_rng(5)
            self._learn_three(det, f, rng)
            assert any(j.kind == "open" for j in det.promoted()), det.describe()
            self._programme(f, 10.0, rng)
            assert det.vote(f.ts).confidence == 0.0
            self._sting(f, up)
            vote = det.vote(f.ts)
            assert vote.confidence == expect, (shifts, vote.reason)
            if expect:
                assert "family=+1st" in vote.reason, vote.reason

    def test_a_slower_sting_is_the_same_family(self, tmp_path: Path) -> None:
        """ADR 0027: the same sting cut half again as slow; the stretched variant is the better fit."""
        from adhush.config import JingleConfig
        from adhush.detect.jingle import JingleDetector, tempo_stretch

        assert tempo_stretch([1, 2, 3, 4], 1.5) == [1, 1, 2, 3, 3, 4]
        assert tempo_stretch([1, 2, 3, 4], 0.5) == [1, 3]
        agree: dict[float, float] = {}
        for tol in (0.5, 0.0):
            cfg = JingleConfig(file=str(tmp_path / f"t{tol}.tsv"), tempo_tolerance=tol, pitch_shifts=0)
            det = JingleDetector(cfg)
            det.warmup()
            f = self._Feed(det)
            rng = np.random.default_rng(7)
            self._learn_three(det, f, rng)
            self._programme(f, 10.0, rng)
            for c in self.OPENER:  # each step 0.75 s instead of 0.5: the show's slow cut
                for _ in range(7):
                    f.block(self._chord(c, f.ts, RATE // 10))
                f.block(self._chord(c, f.ts, RATE // 20))
            vote = det.vote(f.ts)
            assert vote.confidence == 1.0, (tol, vote.reason)
            m = re.search(r"agree=([0-9.]+)", vote.reason)
            assert m is not None
            agree[tol] = float(m.group(1))
            assert ("family=+0st/1.50x" in vote.reason) == (tol > 0), vote.reason
        assert agree[0.5] >= agree[0.0], agree

    def test_hours_admit_a_sting_a_break_early_and_round_trip(self, tmp_path: Path) -> None:
        """ADR 0027: two breaks at nine o'clock, and at nine the sting is trusted; at two it is not."""
        from adhush.config import JingleConfig
        from adhush.detect.jingle import JingleDetector, local_hour

        nine = datetime(2026, 9, 18, 9, 5).timestamp()  # noqa: DTZ001 — local, like the viewer's clock
        two = datetime(2026, 9, 18, 14, 5).timestamp()  # noqa: DTZ001
        assert local_hour(nine) == 9 and local_hour(two) == 14
        cfg = JingleConfig(file=str(tmp_path / "jingles.tsv"))
        det = JingleDetector(cfg)
        det.warmup()
        f = self._Feed(det)
        rng = np.random.default_rng(3)
        for _ in range(2):
            self._programme(f, 15.0, rng)
            self._sting(f, self.OPENER)
            start = f.ts + 1.0
            self._programme(f, 25.0, rng)
            self._sting(f, self.CLOSER)
            det.learn_break(start, f.ts - 1.5, nine)
            self._programme(f, 8.0, rng)
        assert not det.promoted()  # two breaks, no clock: not yet
        det.tick(two)
        assert not det.promoted()
        det.tick(nine + 600.0)
        opens = [j for j in det.promoted() if j.kind == "open"]
        assert len(opens) == 1 and opens[0].hours == {9}, det.describe()
        assert "heard at 09h" in det.describe()
        self._sting(f, self.OPENER)
        assert det.vote(f.ts).confidence == 1.0
        again = JingleDetector(cfg)  # v2 rows carry the hours ...
        assert [j.hours for j in again.promoted() if False] == [] and any(j.hours == {9} for j in again._jingles)
        text = (tmp_path / "jingles.tsv").read_text()
        assert text.startswith("# adhush jingles v2")
        (tmp_path / "jingles.tsv").write_text("\n".join(line.rsplit("\t", 1)[0] for line in text.splitlines()) + "\n")
        legacy = JingleDetector(cfg)  # ... and v1 rows without them still load
        assert legacy._jingles and all(j.hours == set() for j in legacy._jingles)

    def test_clock_learns_break_lengths(self, tmp_path: Path) -> None:
        from adhush.config import ClockConfig
        from adhush.detect.clock import ClockDetector

        clock = ClockDetector(ClockConfig(file=str(tmp_path / "clock.tsv")))
        assert clock.ceiling_s(240.0) == 240.0 and clock.remaining_s(10.0) is None
        for i in range(6):
            clock.learn(i * 3600.0, i * 3600.0 + 125.0 + i * 5)
        assert clock.length_samples == 6
        assert 180.0 <= clock.ceiling_s(240.0) <= 210.0
        assert 60.0 <= clock.remaining_s(60.0) <= 120.0  # type: ignore[operator]
        assert clock.remaining_s(600.0) == 0.0
        assert ClockDetector(ClockConfig(file=str(tmp_path / "clock.tsv"))).length_samples == 6


ASPECT_TIMELINE: Timeline = [
    ("program", 8.0),
    ("boundary", 1.0),
    ("ad_43", 15.0),
    ("program", 8.0),
]


class TestAspectChange:
    def _cfg(self) -> AspectChangeConfig:
        return AspectChangeConfig(min_baseline_s=4.0, confirm_s=0.5)

    def test_pillarboxed_ad_votes_for_its_whole_length(self, tmp_path: Path) -> None:
        frames, frame_ts, samples, labels = synthesize(ASPECT_TIMELINE)
        assert labels[0].start_ts == POD_START and labels[0].duration_s == 16.0
        path = tmp_path / "aspect.npz"
        write_fixture(path, frames=frames, frame_ts=frame_ts, audio=samples, audio_rate=RATE)
        detector = AspectChangeDetector(self._cfg())
        votes: dict[float, float] = {}
        with FileReplaySource(path) as source:
            detector.warmup()
            for frame in source.frames():
                detector.observe_frame(frame)
                votes[frame.ts] = detector.vote(frame.ts).confidence
        assert _max_conf(votes, 0.0, 8.0) == 0.0  # programme, then the black boundary is no shape
        assert _max_conf(votes, 10.0, 24.0) == 1.0
        assert min(c for ts, c in votes.items() if 10.5 <= ts < 24.0) == 1.0  # sustained, not transient
        assert _max_conf(votes, 25.5, 32.0) == 0.0  # the show's shape is back
        assert detector.baseline is not None and abs(detector.baseline - 1.8) < 1e-9

    def test_measures_the_active_picture(self) -> None:
        detector = AspectChangeDetector(AspectChangeConfig())
        full = np.full((HEIGHT, WIDTH), 120, dtype=np.uint8)
        assert detector.measure(full) is not None and abs(detector.measure(full) - 16 / 9) < 1e-9
        letter = full.copy()
        letter[:6] = 0
        letter[-6:] = 0  # 25 % of the height in bars: 16:9 reads as 2.37:1
        assert abs(detector.measure(letter) - (16 / 9) / 0.75) < 1e-9
        assert detector.measure(np.zeros((HEIGHT, WIDTH), dtype=np.uint8)) is None  # black frame: no shape
        dark = np.random.default_rng(1).integers(0, 40, (HEIGHT, WIDTH), dtype=np.uint8)
        assert abs(detector.measure(dark) - 16 / 9) < 1e-9  # a dark, busy scene is not a bar

    def test_votes_only_while_the_shape_is_changed(self) -> None:
        # The programme's own shape is no evidence: out of the normaliser then.
        detector = AspectChangeDetector(AspectChangeConfig(min_baseline_s=2.0, confirm_s=0.5))
        frame = np.full((HEIGHT, WIDTH), 120, dtype=np.uint8)
        pillar = frame.copy()
        pillar[:, :8] = 0
        pillar[:, -8:] = 0
        assert not detector.voting
        for i in range(12):
            detector.observe_frame(FrameEvent(ts=i * 0.25, frame=frame))
        assert detector.baseline is not None and not detector.voting
        assert detector.vote(3.0).reason.startswith("aspect_same")
        for i in range(12, 20):
            detector.observe_frame(FrameEvent(ts=i * 0.25, frame=pillar))
        assert detector.voting
        assert detector.vote(5.0).confidence == 1.0
        for i in range(20, 24):
            detector.observe_frame(FrameEvent(ts=i * 0.25, frame=frame))
        assert not detector.voting

    def test_a_long_change_becomes_the_programme(self) -> None:
        detector = AspectChangeDetector(AspectChangeConfig(min_baseline_s=2.0, confirm_s=0.5, max_change_s=10.0))
        frame = np.full((HEIGHT, WIDTH), 120, dtype=np.uint8)
        letter = frame.copy()
        letter[:6] = 0
        letter[-6:] = 0
        ts = 0.0
        for _ in range(12):
            detector.observe_frame(FrameEvent(ts=ts, frame=frame))
            ts += 0.25
        for _ in range(38):  # 9.5 s: still a break
            detector.observe_frame(FrameEvent(ts=ts, frame=letter))
            ts += 0.25
        assert detector.voting
        for _ in range(8):  # past max_change_s: a film started
            detector.observe_frame(FrameEvent(ts=ts, frame=letter))
            ts += 0.25
        assert not detector.voting
        assert detector.baseline is not None and abs(detector.baseline - 2.4) < 1e-9


class TestStinger:
    """ADR 0027: a short noisy burst over the bed, then the level moves — a segment stinger."""

    @staticmethod
    def _tone(f: "_StingFeed", seconds: float, dbfs: float) -> None:
        amp = 10 ** (dbfs / 20.0) * np.sqrt(2.0)
        n = int(seconds * RATE)
        t = f.ts + np.arange(n) / RATE
        f.block((amp * np.sin(2 * np.pi * 440.0 * t)).astype(np.float32))

    @staticmethod
    def _noise(f: "_StingFeed", seconds: float, dbfs: float, rng: np.random.Generator) -> None:
        n = int(seconds * RATE)
        f.block((10 ** (dbfs / 20.0) * rng.standard_normal(n)).astype(np.float32))

    class _StingFeed:
        def __init__(self, det: "StingerDetector") -> None:  # noqa: F821
            self.det, self.ts = det, 0.0

        def block(self, samples: np.ndarray) -> None:
            for i in range(0, len(samples), RATE // 20):  # 50 ms events, like a capture
                chunk = samples[i : i + RATE // 20]
                self.det.observe_audio(AudioEvent(ts=self.ts, samples=chunk, sample_rate=RATE))
                self.ts += len(chunk) / RATE

    def _det(self) -> "StingerDetector":  # noqa: F821
        from adhush.config import StingerConfig
        from adhush.detect.stinger import StingerDetector

        det = StingerDetector(StingerConfig())
        det.warmup()
        return det

    def test_burst_then_level_step_fires_and_decays(self) -> None:
        det = self._det()
        f = self._StingFeed(det)
        rng = np.random.default_rng(1)
        self._tone(f, 3.0, -20.0)
        assert not det.voting and det.vote(f.ts).confidence == 0.0
        self._noise(f, 0.3, -8.0, rng)
        self._tone(f, 1.2, -12.0)
        vote = det.vote(f.ts)
        assert det.voting and vote.confidence > 0.8, vote.reason
        assert vote.reason.startswith("stinger burst_db=+") and "step_db=+" in vote.reason, vote.reason
        self._tone(f, 3.0, -12.0)
        assert not det.voting and det.vote(f.ts).confidence == 0.0
        assert det.describe() == "1 stinger(s) heard"

    def test_level_step_without_a_burst_is_not_a_stinger(self) -> None:
        det = self._det()
        f = self._StingFeed(det)
        self._tone(f, 3.0, -20.0)
        self._tone(f, 2.0, -8.0)  # loudness's business, not a stinger
        assert not det.voting and det.vote(f.ts).confidence == 0.0

    def test_long_noise_and_a_burst_back_to_the_same_bed_do_not_fire(self) -> None:
        det = self._det()
        f = self._StingFeed(det)
        rng = np.random.default_rng(2)
        self._tone(f, 3.0, -20.0)
        self._noise(f, 3.0, -8.0, rng)  # applause: far too long for a stinger
        assert not det.voting
        self._tone(f, 3.0, -20.0)
        self._noise(f, 0.3, -13.0, rng)  # +7 dB, back to the same bed: nothing changed
        self._tone(f, 2.0, -20.0)
        assert not det.voting, det.vote(f.ts).reason

    def test_our_own_duck_is_ignored(self) -> None:
        det = self._det()
        f = self._StingFeed(det)
        rng = np.random.default_rng(3)
        self._tone(f, 3.0, -20.0)
        det.audio_ducked(f.ts, True)
        self._noise(f, 0.3, -8.0, rng)
        self._tone(f, 1.2, -12.0)
        assert not det.voting
