"""Break jingles (ADR 0020, after AdVent): the sting a channel plays into and
out of every break, learned rather than hard-coded.

Every break the engine ends contributes the seconds around its start (an
opener) and its end (a closer) as *candidates*, cut from a rolling ring of
chroma blocks. A candidate that turns out to open several different breaks
is promoted to a jingle and starts voting: an opener heard live votes 1.0
for ``hold_s`` (it carries the logo weight, so it mutes alone); a closer
heard while muted is program evidence (``program_present``), which ends a
break as fast as a returning logo. "Not an ad" after a jingle-driven mute
counts against that jingle; enough wrong calls demote it to a candidate.
Same TSV as the phone (``jingles.tsv``), so a memory can move.

Families and hours (ADR 0027): a channel re-cuts its sting per show, so a
sting is also recognised transposed by a few semitones (the 12 chroma bits
rotate) or played a little faster or slower (the stored blocks stretch), at a
higher bar; a variant hit counts for the jingle it resembles, so one memory
covers the family. Every jingle also remembers the local hours it opened
breaks in: heard again at one of those hours it is trusted a break earlier
and matched a little more loosely; a replay, which has no wall clock, learns
no hours.
"""

from __future__ import annotations

from dataclasses import dataclass, field
from datetime import datetime
from pathlib import Path
from typing import ClassVar

import numpy as np
import numpy.typing as npt

from adhush.config import JingleConfig
from adhush.detect.base import Detector
from adhush.events import AudioEvent, DetectorVote
from adhush.fingerprint.audio_chroma import CHROMA_BITS, agreement, chroma_bits

_MASK = (1 << CHROMA_BITS) - 1


def pitch_rotate(bits: int, semitones: int) -> int:
    """The chroma word of the same block transposed up by ``semitones``
    (down when negative): pitch class c moves to c + k, and class c lives in
    bit ``11 - c``, so up is a rotate towards bit 0."""
    k = semitones % CHROMA_BITS
    if k == 0:
        return bits & _MASK
    return ((bits >> k) | (bits << (CHROMA_BITS - k))) & _MASK


def tempo_stretch(blocks: list[int], factor: float) -> list[int]:
    """The same sting played at ``1 / factor`` speed: ``factor`` 1.1 is 10 % slower."""
    n = max(1, round(len(blocks) * factor))
    return [blocks[min(len(blocks) - 1, int(i / factor))] for i in range(n)]


def local_hour(wall: float) -> int:
    return datetime.fromtimestamp(wall).hour  # noqa: DTZ006 — the viewer's own clock, by design


@dataclass
class Jingle:
    id: int
    kind: str  # "open" | "close"
    blocks: list[int]
    hits: int
    false_hits: int
    created_ts: float
    hours: set[int] = field(default_factory=set)  # local hours this sting opened a break in
    last_wall: float = 0.0  # wall time of the last break that counted as a hit; 0 unknown


class JingleDetector(Detector):
    name: ClassVar[str] = "jingle"
    needs_audio: ClassVar[bool] = True

    def __init__(self, config: JingleConfig) -> None:
        self._cfg = config
        self._ring: list[tuple[float, int]] = []
        self._pending: list[npt.NDArray[np.float32]] = []
        self._pending_n = 0
        self._block_start: float | None = None
        self._jingles: list[Jingle] = []
        self._next_id = 1
        self._pending_close: float | None = None
        self._open_hit_ts: float | None = None
        self._open_hit_id: int | None = None
        self._close_hit_ts: float | None = None
        self._last_agreement = 0.0
        self._last_variant = ""
        self._last_ts = 0.0
        self._hour: int | None = None
        self._pending_close_hour: int | None = None
        self._pending_close_wall: float | None = None
        self._load()

    # -- sizes ---------------------------------------------------------------

    @property
    def _sting_blocks(self) -> int:
        return max(2, int(self._cfg.jingle_s / self._cfg.sample_interval_s))

    @property
    def _candidate_blocks(self) -> int:
        return max(self._sting_blocks, int(self._cfg.candidate_s / self._cfg.sample_interval_s))

    # -- persistence (j<TAB>id<TAB>kind<TAB>hits<TAB>false<TAB>created<TAB>b,b,b<TAB>h,h) --

    def _path(self) -> Path | None:
        return Path(self._cfg.file) if self._cfg.file else None

    def _load(self) -> None:
        path = self._path()
        if path is None or not path.is_file():
            return
        for line in path.read_text().splitlines():
            p = line.split("\t")
            if p[0] != "j" or len(p) < 7:
                continue
            blocks = [int(b) for b in p[6].split(",") if b]
            hours = {int(h) for h in p[7].split(",") if h} if len(p) > 7 else set()  # v1 lines carry none
            last_wall = float(p[8]) if len(p) > 8 and p[8] else 0.0
            self._jingles.append(Jingle(int(p[1]), p[2], blocks, int(p[3]), int(p[4]), float(p[5]), hours, last_wall))
            self._next_id = max(self._next_id, int(p[1]) + 1)

    def _save(self) -> None:
        path = self._path()
        if path is None:
            return
        path.parent.mkdir(parents=True, exist_ok=True)
        tmp = path.with_suffix(path.suffix + ".tmp")
        lines = ["# adhush jingles v2\tid\tkind\thits\tfalse_hits\tcreated\tblocks\thours\tlast_wall"]
        lines += [
            f"j\t{j.id}\t{j.kind}\t{j.hits}\t{j.false_hits}\t{j.created_ts}\t{','.join(map(str, j.blocks))}"
            f"\t{','.join(map(str, sorted(j.hours)))}\t{j.last_wall}"
            for j in self._jingles
        ]
        tmp.write_text("\n".join(lines) + "\n")
        tmp.replace(path)

    # -- audio ---------------------------------------------------------------

    def warmup(self) -> None:
        self._ring.clear()
        self._pending.clear()
        self._pending_n = 0
        self._block_start = None
        self._pending_close = None
        self._open_hit_ts = self._close_hit_ts = None

    def observe_audio(self, event: AudioEvent) -> None:
        start = self._block_start if self._block_start is not None else event.ts
        self._pending.append(event.samples)
        self._pending_n += len(event.samples)
        need = round(self._cfg.sample_interval_s * event.sample_rate)
        while self._pending_n >= need:
            all_samples = np.concatenate(self._pending)
            head, rest = all_samples[:need], all_samples[need:]
            self._ring.append((start, chroma_bits(head, event.sample_rate)))
            keep = int(self._cfg.history_s / self._cfg.sample_interval_s)
            if len(self._ring) > keep:
                del self._ring[: len(self._ring) - keep]
            start += need / event.sample_rate
            self._pending = [rest] if len(rest) else []
            self._pending_n = len(rest)
            self._on_sample(start)
        self._block_start = start

    def _blocks_between(self, t0: float, t1: float) -> list[int]:
        return [bits for ts, bits in self._ring if t0 <= ts < t1]

    def _best_agreement(self, live: list[int], candidate: list[int]) -> float:
        if len(live) < self._sting_blocks or len(candidate) < len(live):
            return 0.0
        return max(
            agreement(live, candidate[off : off + len(live)])
            for off in range(len(candidate) - len(live) + 1)
        )

    def _variants(self, blocks: list[int]) -> list[tuple[list[int], str]]:
        """The stored sting as heard, then every transposition and stretch the
        config allows, each labelled; the identity comes first and unlabelled."""
        out: list[tuple[list[int], str]] = [(blocks, "")]
        shifts = [k for k in range(-self._cfg.pitch_shifts, self._cfg.pitch_shifts + 1) if k]
        tol = self._cfg.tempo_tolerance
        factors = [1.0 - tol, 1.0 + tol] if tol > 0 else []
        for k in [0, *shifts]:
            rotated = [pitch_rotate(b, k) for b in blocks] if k else blocks
            for f in factors:
                out.append((tempo_stretch(rotated, f), f"{k:+d}st/{f:.2f}x"))
            if k:
                out.append((rotated, f"{k:+d}st"))
        return out

    def _best_family(self, live: list[int], jingle_blocks: list[int]) -> tuple[float, str]:
        """Best agreement of ``live`` against the sting or any variant of it, the
        variants docked ``family_penalty`` so the sting as heard always wins a tie."""
        best, label = 0.0, ""
        for blocks, variant in self._variants(jingle_blocks):
            a = self._best_agreement(live, blocks) - (self._cfg.family_penalty if variant else 0.0)
            if a > best:
                best, label = a, variant
        return best, label

    def _same_sting(self, a: list[int], b: list[int]) -> bool:
        core = self._sting_blocks
        return any(
            self._best_family(a[i : i + core], b)[0] >= self._cfg.min_agreement
            for i in range(len(a) - core + 1)
        )

    def _trusted(self, j: Jingle) -> bool:
        """Promoted outright, or one break short of it at an hour it has opened breaks in before."""
        if j.hits >= self._cfg.promote_hits:
            return True
        return self._hour is not None and self._hour in j.hours and j.hits >= self._cfg.promote_hits - 1 > 0

    def _threshold(self, j: Jingle) -> float:
        bonus = self._cfg.hour_bonus if self._hour is not None and self._hour in j.hours else 0.0
        return self._cfg.min_agreement - bonus

    def _on_sample(self, ts: float) -> None:
        self._last_ts = ts
        if self._pending_close is not None and ts >= self._pending_close + self._cfg.candidate_s - 2.0:
            end, self._pending_close = self._pending_close, None
            hour, self._pending_close_hour = self._pending_close_hour, None
            wall, self._pending_close_wall = self._pending_close_wall, None
            self._add_candidate("close", self._blocks_between(end - 2.0, end + self._cfg.candidate_s - 2.0), ts, hour, wall)
        live = [bits for _, bits in self._ring[-self._sting_blocks :]]
        if len(live) < self._sting_blocks:
            return
        best: Jingle | None = None
        best_a = 0.0
        best_v = ""
        for j in self._jingles:
            if not self._trusted(j):
                continue
            a, variant = self._best_family(live, j.blocks)
            if a >= self._threshold(j) and a > best_a:
                best, best_a, best_v = j, a, variant
        if best is None:
            return
        self._last_agreement, self._last_variant = best_a, best_v
        if best.kind == "open":
            if self._open_hit_ts is None or ts - self._open_hit_ts > self._cfg.hold_s:
                self._open_hit_ts, self._open_hit_id = ts, best.id
        else:
            self._close_hit_ts = ts

    def _add_candidate(
        self, kind: str, blocks: list[int], ts: float, hour: int | None = None, wall: float | None = None,
    ) -> None:
        if len(blocks) < self._sting_blocks:
            return
        for j in self._jingles:
            if j.kind == kind and self._same_sting(blocks, j.blocks):
                # Heard again within the spacing: the same session, not another break; it counts once.
                if wall is not None and j.last_wall > 0.0 and wall - j.last_wall < self._cfg.hit_spacing_s:
                    return
                j.hits += 1
                if hour is not None:
                    j.hours.add(hour)
                if wall is not None:
                    j.last_wall = wall
                self._save()
                return
        self._jingles.append(
            Jingle(self._next_id, kind, blocks, 1, 0, ts, {hour} if hour is not None else set(), wall or 0.0)
        )
        self._next_id += 1
        candidates = [j for j in self._jingles if j.hits < self._cfg.promote_hits]
        while len(candidates) > self._cfg.max_candidates:
            victim = min(candidates, key=lambda j: j.created_ts)
            self._jingles.remove(victim)
            candidates.remove(victim)
        self._save()

    # -- what the engine tells it ---------------------------------------------

    def tick(self, wall: float) -> None:
        self._hour = local_hour(wall)

    def learn_break(self, start_ts: float, end_ts: float, wall_start: float | None = None) -> None:
        """A real break ran from ``start_ts`` to ``end_ts`` (media time); it
        began at ``wall_start`` on the viewer's clock when the engine has one."""
        if end_ts - start_ts < self._cfg.min_break_s:
            return  # a mute that ended in seconds was no break (ADR 0027, amended)
        hour = local_hour(wall_start) if wall_start is not None else self._hour
        lead = self._cfg.candidate_s - 2.0
        self._add_candidate("open", self._blocks_between(start_ts - lead, start_ts + 2.0), end_ts, hour, wall_start)
        self._pending_close = end_ts
        self._pending_close_hour = hour
        self._pending_close_wall = wall_start

    def forget_all(self) -> None:
        """Drop every sting and candidate."""
        self._jingles.clear()
        self._open_hit_ts = self._open_hit_id = None
        self._close_hit_ts = self._pending_close = None
        self._save()

    def user_says_program(self, ts: float) -> None:
        if self._open_hit_id is not None and self._open_hit_ts is not None and ts - self._open_hit_ts < 60.0:
            for j in self._jingles:
                if j.id == self._open_hit_id:
                    j.false_hits += 1
                    if j.false_hits >= j.hits:
                        j.hits = 0
                    self._save()
        self._open_hit_ts = self._open_hit_id = None
        self._close_hit_ts = None
        self._pending_close = None

    @property
    def program_present(self) -> bool:
        return self._close_hit_ts is not None and self._last_ts - self._close_hit_ts < self._cfg.close_hold_s

    def promoted(self) -> list[Jingle]:
        """The jingles trusted right now (the hour of day can admit one a break early)."""
        return [j for j in self._jingles if self._trusted(j)]

    @property
    def voting(self) -> bool:
        """Silent until a sting has opened enough breaks to be trusted: a learner
        with nothing to say must not dilute the detectors that do (fusion norm)."""
        return any(j.kind == "open" for j in self.promoted())

    def vote(self, ts: float) -> DetectorVote:
        open_ts = self._open_hit_ts
        if open_ts is not None and ts - open_ts < self._cfg.hold_s and (
            self._close_hit_ts is None or self._close_hit_ts < open_ts
        ):
            family = f" family={self._last_variant}" if self._last_variant else ""
            return self._vote(
                ts, 1.0,
                f"jingle_open id={self._open_hit_id} agree={self._last_agreement:.2f} age_s={ts - open_ts:.1f}{family}",
            )
        known = len(self.promoted())
        return self._vote(ts, 0.0, f"jingle_quiet known={known} candidates={len(self._jingles) - known}")

    def describe(self) -> str:
        p = self.promoted()
        cands = len(self._jingles) - len(p)
        if not p:
            return f"learning ({cands} candidates; a sting must open 3 breaks)"
        opens = sum(1 for j in p if j.kind == "open")
        hours = sorted({h for j in p for h in j.hours})
        when = f"; heard at {', '.join(f'{h:02d}h' for h in hours)}" if hours else ""
        return f"{opens} opener(s), {len(p) - opens} closer(s) known, {cands} candidates{when}"
