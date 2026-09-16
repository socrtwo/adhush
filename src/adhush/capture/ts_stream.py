"""MPEG transport stream over HTTP or UDP — an HDHomeRun, a DVB dongle's
streamer, any IPTV multicast (ADR 0024).

One reader thread pulls the stream and fans it out three ways: into ffmpeg
for the video frames, into a second ffmpeg for the mono (or stereo-measured)
audio, and into the in-process SCTE-35 demuxer, whose cues come out of
``cues()``. The two ffmpegs each demux the stream again; that is far
cheaper than the one decode and keeps the pipes simple.
"""

from __future__ import annotations

import contextlib
import logging
import queue
import shutil
import socket
import subprocess
import threading
import urllib.request
from collections.abc import Iterator

import numpy as np

from adhush.capture.base import CaptureCaps, CaptureError, CaptureSource
from adhush.config import CaptureConfig
from adhush.detect.stereo_width import stereo_width
from adhush.events import AudioEvent, CueEvent, FrameEvent
from adhush.util.mpegts import Cue, TsDemuxer
from adhush.util.timing import Clock, monotonic_clock

log = logging.getLogger(__name__)
_CHUNK = 188 * 64


class TsStreamSource(CaptureSource):
    def __init__(self, config: CaptureConfig, clock: Clock = monotonic_clock) -> None:
        if not config.url:
            raise CaptureError("capture.url is required for ts_stream")
        self._cfg = config
        self._clock = clock
        self._video_proc: subprocess.Popen[bytes] | None = None
        self._audio_proc: subprocess.Popen[bytes] | None = None
        self._reader: threading.Thread | None = None
        self._stop = threading.Event()
        self._cues: queue.Queue[CueEvent | None] = queue.Queue()
        self._demux = TsDemuxer(self._on_cue)
        self._t0: float | None = None
        self._channels = 2 if config.stereo else 1

    def open(self) -> None:
        if shutil.which("ffmpeg") is None:
            raise CaptureError("ts_stream requires ffmpeg on PATH")
        cfg = self._cfg
        self._video_proc = subprocess.Popen(
            [
                "ffmpeg", "-v", "error", "-f", "mpegts", "-i", "pipe:0",
                "-an", "-vf", f"scale={cfg.width}:{cfg.height},fps={cfg.fps}",
                "-f", "rawvideo", "-pix_fmt", "bgr24", "pipe:1",
            ],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE,
        )
        self._audio_proc = subprocess.Popen(
            [
                "ffmpeg", "-v", "error", "-f", "mpegts", "-i", "pipe:0",
                "-vn", "-f", "f32le", "-ac", str(self._channels), "-ar", str(cfg.audio_rate), "pipe:1",
            ],
            stdin=subprocess.PIPE, stdout=subprocess.PIPE,
        )
        self._t0 = self._clock()
        self._stop.clear()
        self._reader = threading.Thread(target=self._pump, name="adhush-ts", daemon=True)
        self._reader.start()

    def _on_cue(self, cue: Cue) -> None:
        self._cues.put(CueEvent(ts=self._now(), kind="ad_start" if cue.start else "ad_end", duration_s=cue.duration_s, detail=cue.detail))

    def _open_stream(self) -> Iterator[bytes]:
        url = self._cfg.url
        if url.startswith("udp://"):
            host, _, port = url[6:].partition(":")
            sock = socket.socket(socket.AF_INET, socket.SOCK_DGRAM)
            sock.settimeout(5.0)
            sock.bind((host or "", int(port or 1234)))
            try:
                while not self._stop.is_set():
                    yield sock.recv(65536)
            finally:
                sock.close()
            return
        with urllib.request.urlopen(url, timeout=10) as resp:
            while not self._stop.is_set():
                chunk = resp.read(_CHUNK)
                if not chunk:
                    return
                yield chunk

    def _pump(self) -> None:
        try:
            for chunk in self._open_stream():
                for proc in (self._video_proc, self._audio_proc):
                    if proc is not None and proc.stdin is not None:
                        try:
                            proc.stdin.write(chunk)
                        except (BrokenPipeError, ValueError):
                            pass
                self._demux.push(chunk)
        except (OSError, ValueError) as e:
            log.error("ts_stream: %s", e)
        finally:
            for proc in (self._video_proc, self._audio_proc):
                if proc is not None and proc.stdin is not None:
                    with contextlib.suppress(OSError, ValueError):
                        proc.stdin.close()
            self._cues.put(None)

    def close(self) -> None:
        self._stop.set()
        for proc in (self._video_proc, self._audio_proc):
            if proc is not None:
                proc.terminate()
                proc.wait()
        self._video_proc = None
        self._audio_proc = None

    def caps(self) -> CaptureCaps:
        return CaptureCaps(video=True, audio=True, width=self._cfg.width, height=self._cfg.height, fps=float(self._cfg.fps), sample_rate=self._cfg.audio_rate, realtime=True, cues=True)

    def _now(self) -> float:
        assert self._t0 is not None
        return self._clock() - self._t0

    def frames(self) -> Iterator[FrameEvent]:
        proc = self._video_proc
        if proc is None or proc.stdout is None:
            raise CaptureError("iterate after open()")
        n = self._cfg.width * self._cfg.height * 3
        while True:
            chunk = proc.stdout.read(n)
            if len(chunk) < n:
                return
            yield FrameEvent(ts=self._now(), frame=np.frombuffer(chunk, dtype=np.uint8).reshape(self._cfg.height, self._cfg.width, 3))

    def audio_blocks(self) -> Iterator[AudioEvent]:
        proc = self._audio_proc
        if proc is None or proc.stdout is None:
            raise CaptureError("iterate after open()")
        rate = self._cfg.audio_rate
        block = max(1, rate * self._cfg.audio_block_ms // 1000)
        while True:
            chunk = proc.stdout.read(block * 4 * self._channels)
            if not chunk:
                return
            raw = np.frombuffer(chunk, dtype=np.float32)
            if self._channels == 2:
                width = stereo_width(raw)
                samples = ((raw[0::2] + raw[1::2]) * 0.5).astype(np.float32)
            else:
                width, samples = None, raw
            yield AudioEvent(ts=self._now() - len(samples) / rate, samples=samples, sample_rate=rate, width=width)

    def cues(self) -> Iterator[CueEvent]:
        while True:
            cue = self._cues.get()
            if cue is None:
                return
            yield cue
