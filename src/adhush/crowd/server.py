"""The crowd server (ADR 0024): ``adhush crowd serve``.

A few dozen lines of HTTP. Devices POST ``/report`` with the hash of their
channel, a kind ("start" or "end"), a wall-clock time and a random device
id; they GET ``/recent?prefix=<4 hex>&since=<epoch>`` and receive every
report whose channel hash begins with that prefix (k-anonymity, after
SponsorBlock: the server never learns which channel a device watches, only
a sixteenth of the space). Reports live for ten minutes in memory; nothing
is written to disk.
"""

from __future__ import annotations

import json
import threading
import time
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from typing import TypedDict
from urllib.parse import parse_qs, urlparse

KEEP_S = 600.0


class Report(TypedDict):
    hash: str
    kind: str
    ts: float
    device: str


class CrowdStore:
    def __init__(self) -> None:
        self._lock = threading.Lock()
        self._reports: list[Report] = []

    def add(self, report: Report) -> None:
        with self._lock:
            self._reports.append(report)
            self._prune()

    def recent(self, prefix: str, since: float) -> list[Report]:
        with self._lock:
            self._prune()
            return [r for r in self._reports if r["hash"].startswith(prefix) and r["ts"] >= since]

    def _prune(self) -> None:
        cutoff = time.time() - KEEP_S
        self._reports = [r for r in self._reports if r["ts"] >= cutoff]


def make_handler(store: CrowdStore) -> type[BaseHTTPRequestHandler]:
    class Handler(BaseHTTPRequestHandler):
        def log_message(self, format: str, *args: object) -> None:
            pass

        def _json(self, code: int, body: object) -> None:
            data = json.dumps(body).encode()
            self.send_response(code)
            self.send_header("Content-Type", "application/json")
            self.send_header("Content-Length", str(len(data)))
            self.end_headers()
            self.wfile.write(data)

        def do_GET(self) -> None:
            url = urlparse(self.path)
            if url.path != "/recent":
                self._json(404, {"error": "not found"})
                return
            q = parse_qs(url.query)
            prefix = q.get("prefix", [""])[0][:8]
            since = float(q.get("since", ["0"])[0] or 0)
            if len(prefix) < 2:
                self._json(400, {"error": "prefix too short"})
                return
            self._json(200, {"reports": list(store.recent(prefix, since)), "now": time.time()})

        def do_POST(self) -> None:
            if urlparse(self.path).path != "/report":
                self._json(404, {"error": "not found"})
                return
            length = int(self.headers.get("Content-Length", "0"))
            try:
                body = json.loads(self.rfile.read(length) or b"{}")
                report = Report(
                    hash=str(body["hash"])[:64],
                    kind="start" if body.get("kind") == "start" else "end",
                    ts=float(body.get("ts", time.time())),
                    device=str(body.get("device", ""))[:32],
                )
            except (KeyError, ValueError, TypeError):
                self._json(400, {"error": "bad report"})
                return
            if len(report["hash"]) < 16:
                self._json(400, {"error": "bad hash"})
                return
            store.add(report)
            self._json(200, {"ok": True})

    return Handler


def serve(host: str = "0.0.0.0", port: int = 8676) -> ThreadingHTTPServer:
    server = ThreadingHTTPServer((host, port), make_handler(CrowdStore()))
    return server
