"""A minimal MPEG transport-stream demuxer for one thing: SCTE-35 (ADR 0024).

Feed it bytes; it finds the 188-byte packets, follows PAT → PMT to the
SCTE-35 PID (stream type 0x86), reassembles those sections, and hands back
``Cue`` objects for splice_insert commands and for time_signal commands
that carry a segmentation descriptor of an ad or break type. Nothing else
in the stream is touched; video and audio go to ffmpeg untouched.
"""

from __future__ import annotations

from collections.abc import Callable, Iterator
from dataclasses import dataclass

PACKET = 188
SYNC = 0x47
SCTE35_STREAM_TYPE = 0x86

# segmentation_type_id → (is_start, label)
SEGMENTATION_TYPES: dict[int, tuple[bool, str]] = {
    0x22: (True, "break_start"), 0x23: (False, "break_end"),
    0x30: (True, "provider_ad_start"), 0x31: (False, "provider_ad_end"),
    0x32: (True, "distributor_ad_start"), 0x33: (False, "distributor_ad_end"),
    0x34: (True, "provider_placement_start"), 0x35: (False, "provider_placement_end"),
    0x36: (True, "distributor_placement_start"), 0x37: (False, "distributor_placement_end"),
}


@dataclass(frozen=True, slots=True)
class Cue:
    start: bool  # True = out of network (an ad break begins)
    duration_s: float | None
    detail: str
    event_id: int = 0


class BitReader:
    def __init__(self, data: bytes) -> None:
        self._data = data
        self._pos = 0  # in bits

    def bits(self, n: int) -> int:
        value = 0
        for _ in range(n):
            byte = self._data[self._pos >> 3]
            bit = (byte >> (7 - (self._pos & 7))) & 1
            value = (value << 1) | bit
            self._pos += 1
        return value

    def bytes_(self, n: int) -> bytes:
        assert self._pos % 8 == 0
        start = self._pos >> 3
        self._pos += 8 * n
        return self._data[start : start + n]

    def skip(self, n: int) -> None:
        self._pos += n

    @property
    def byte_pos(self) -> int:
        return self._pos >> 3


def _splice_time(r: BitReader) -> float | None:
    specified = r.bits(1)
    if specified:
        r.skip(6)
        return r.bits(33) / 90000.0
    r.skip(7)
    return None


def parse_splice_info(section: bytes) -> list[Cue]:
    """Cues in one SCTE-35 splice_info_section (table_id 0xFC)."""
    if len(section) < 14 or section[0] != 0xFC:
        return []
    r = BitReader(section)
    r.skip(8)  # table_id
    r.skip(1 + 1 + 2)
    section_length = r.bits(12)
    r.skip(8)  # protocol_version
    encrypted = r.bits(1)
    r.skip(6)  # encryption_algorithm
    r.skip(33)  # pts_adjustment
    r.skip(8)  # cw_index
    r.skip(12)  # tier
    command_length = r.bits(12)
    command_type = r.bits(8)
    if encrypted:
        return []
    cues: list[Cue] = []
    command_start = r.byte_pos
    if command_type == 0x05:  # splice_insert
        event_id = r.bits(32)
        cancel = r.bits(1)
        r.skip(7)
        if not cancel:
            out_of_network = r.bits(1)
            program_splice = r.bits(1)
            duration_flag = r.bits(1)
            immediate = r.bits(1)
            r.skip(4)
            if program_splice and not immediate:
                _splice_time(r)
            if not program_splice:
                count = r.bits(8)
                for _ in range(count):
                    r.skip(8)
                    if not immediate:
                        _splice_time(r)
            duration = None
            if duration_flag:
                r.skip(1 + 6)  # auto_return, reserved
                duration = r.bits(33) / 90000.0
            cues.append(Cue(bool(out_of_network), duration, "splice_insert", event_id))
    elif command_type == 0x06:  # time_signal
        _splice_time(r)
    # descriptors follow the command whatever it was
    if command_length != 0xFFF:
        r = BitReader(section)
        r.skip(8 * (command_start + command_length))
    loop_length = r.bits(16)
    end = r.byte_pos + loop_length
    while r.byte_pos + 2 <= end and r.byte_pos + 2 <= len(section):
        tag = r.bits(8)
        length = r.bits(8)
        body_start = r.byte_pos
        if tag == 0x02 and length >= 11:  # segmentation_descriptor
            cue = _segmentation(BitReader(section[body_start : body_start + length]))
            if cue is not None:
                cues.append(cue)
        r = BitReader(section)
        r.skip(8 * (body_start + length))
    del section_length
    return cues


def _segmentation(r: BitReader) -> Cue | None:
    r.skip(32)  # "CUEI"
    event_id = r.bits(32)
    cancel = r.bits(1)
    r.skip(7)
    if cancel:
        return None
    program_flag = r.bits(1)
    duration_flag = r.bits(1)
    not_restricted = r.bits(1)
    if not_restricted:
        r.skip(5)
    else:
        r.skip(1 + 1 + 1 + 2)
    if not program_flag:
        count = r.bits(8)
        r.skip(48 * count)
    duration = None
    if duration_flag:
        duration = r.bits(40) / 90000.0
    upid_type = r.bits(8)
    upid_len = r.bits(8)
    r.skip(8 * upid_len)
    seg_type = r.bits(8)
    kind = SEGMENTATION_TYPES.get(seg_type)
    if kind is None:
        return None
    del upid_type
    return Cue(kind[0], duration, f"segmentation:{kind[1]}", event_id)


class TsDemuxer:
    """Feed ``push(bytes)``; collect cues with ``drain()``."""

    def __init__(self, on_cue: Callable[[Cue], None] | None = None) -> None:
        self._buf = bytearray()
        self._pmt_pids: set[int] = set()
        self._scte_pids: set[int] = set()
        self._sections: dict[int, bytearray] = {}
        self._pending: list[Cue] = []
        self._on_cue = on_cue
        self.packets = 0

    @property
    def scte35_pids(self) -> set[int]:
        return set(self._scte_pids)

    def push(self, data: bytes) -> None:
        self._buf.extend(data)
        # Resynchronise on the first sync byte that is followed by another 188 later.
        while len(self._buf) >= PACKET:
            if self._buf[0] != SYNC:
                nxt = self._buf.find(bytes([SYNC]), 1)
                if nxt < 0:
                    self._buf.clear()
                    return
                del self._buf[:nxt]
                continue
            packet = bytes(self._buf[:PACKET])
            del self._buf[:PACKET]
            self._packet(packet)

    def drain(self) -> Iterator[Cue]:
        pending, self._pending = self._pending, []
        return iter(pending)

    def _packet(self, p: bytes) -> None:
        self.packets += 1
        pusi = (p[1] & 0x40) != 0
        pid = ((p[1] & 0x1F) << 8) | p[2]
        afc = (p[3] >> 4) & 0x3
        pos = 4
        if afc in (2, 3):
            pos += 1 + p[4]
        if afc in (0, 2) or pos >= PACKET:
            return
        payload = p[pos:]
        if pid == 0 or pid in self._pmt_pids or pid in self._scte_pids:
            self._psi(pid, payload, pusi)

    def _psi(self, pid: int, payload: bytes, pusi: bool) -> None:
        if pusi:
            pointer = payload[0]
            payload = payload[1 + pointer :]
            self._sections[pid] = bytearray(payload)
        else:
            buf = self._sections.get(pid)
            if buf is None:
                return
            buf.extend(payload)
        buf = self._sections[pid]
        if len(buf) < 3:
            return
        length = ((buf[1] & 0x0F) << 8) | buf[2]
        total = 3 + length
        if len(buf) < total:
            return
        section = bytes(buf[:total])
        del self._sections[pid]
        table_id = section[0]
        if pid == 0 and table_id == 0x00:
            self._pat(section)
        elif pid in self._pmt_pids and table_id == 0x02:
            self._pmt(section)
        elif pid in self._scte_pids and table_id == 0xFC:
            for cue in parse_splice_info(section):
                self._pending.append(cue)
                if self._on_cue is not None:
                    self._on_cue(cue)

    def _pat(self, s: bytes) -> None:
        length = ((s[1] & 0x0F) << 8) | s[2]
        end = 3 + length - 4  # minus CRC
        pos = 8
        while pos + 4 <= end:
            program = (s[pos] << 8) | s[pos + 1]
            pid = ((s[pos + 2] & 0x1F) << 8) | s[pos + 3]
            if program != 0:
                self._pmt_pids.add(pid)
            pos += 4

    def _pmt(self, s: bytes) -> None:
        length = ((s[1] & 0x0F) << 8) | s[2]
        end = 3 + length - 4
        info_length = ((s[10] & 0x0F) << 8) | s[11]
        pos = 12 + info_length
        while pos + 5 <= end:
            stream_type = s[pos]
            pid = ((s[pos + 1] & 0x1F) << 8) | s[pos + 2]
            es_length = ((s[pos + 3] & 0x0F) << 8) | s[pos + 4]
            if stream_type == SCTE35_STREAM_TYPE:
                self._scte_pids.add(pid)
            pos += 5 + es_length
