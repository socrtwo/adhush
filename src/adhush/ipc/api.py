"""Local HTTP API: status, override, confirm/reject ad, live detector trace.

Stdlib-only server for platform front ends (docs/adr/0002). Endpoints:

- ``GET  /status``      → status event JSON
- ``POST /command``     → one protocol command (override, confirm_ad,
                          reject_ad, set_trace, get_status)
- ``GET  /events``      → Server-Sent Events stream of transition/decision/
                          status events (decision events only while trace is
                          enabled)
- ``GET  /`` and the web front end's few static files, served from
  ``IpcConfig.web_root`` so a phone or another PC needs only the core's
  address — no file copying. Static files skip the token (they are public
  HTML); every API endpoint still requires it. Only a fixed allow-list of
  file names is served, so there is no path to traverse.

``shutdown`` is the one command that reaches outside the pipeline: the
server hands it to the ``on_shutdown`` callback the CLI supplies (which sets
the run loop's stop event), after the reply has been written.

SSE rather than WebSocket on purpose: every listed feature is one-directional
streaming plus request/response, ``EventSource`` works from any browser or
platform shell with zero dependencies, and the wire payloads are the same
``ipc/protocol.py`` messages either way (ADR 0006). Binds loopback by
default; an optional bearer token gates every request when the LAN is
involved, and permissive CORS lets a ``file://`` front end talk to it.
"""

from __future__ import annotations

import json
import logging
import mimetypes
import queue
import threading
from collections.abc import Callable
from http.server import BaseHTTPRequestHandler, ThreadingHTTPServer
from pathlib import Path
from typing import Any

from adhush.config import IpcConfig
from adhush.engine import Pipeline
from adhush.ipc.protocol import Command, ProtocolError, encode_event, parse_command

log = logging.getLogger(__name__)

_MAX_BODY = 64 * 1024
_SSE_QUEUE_SIZE = 256
# The complete set of static files the front end consists of; nothing else on
# disk is reachable through the server, whatever the request path says.
_STATIC_FILES = frozenset(
    {
        "index.html",
        "manifest.webmanifest",
        "sw.js",
        "icon-192.png",
        "icon-512.png",
        "apple-touch-icon.png",
    }
)
_STATIC_TYPES = {".webmanifest": "application/manifest+json", ".js": "text/javascript"}
# Let the HTTP reply flush before the stop event tears the server down.
_SHUTDOWN_DELAY_S = 0.05


def resolve_web_root(web_root: str) -> Path | None:
    """Locate the front end: as given, else relative to a source checkout."""
    candidates = [Path(web_root)]
    if not Path(web_root).is_absolute():
        candidates.append(Path(__file__).resolve().parents[3] / web_root)
    for candidate in candidates:
        if (candidate / "index.html").is_file():
            return candidate
    return None


def static_target(path: str, web_root: Path | None) -> Path | None:
    """Map a request path to a servable file, or None if it is not one of ours."""
    name = "index.html" if path in ("/", "") else path.lstrip("/")
    if web_root is None or name not in _STATIC_FILES:
        return None
    target = web_root / name
    return target if target.is_file() else None


class ApiServer:
    """Serves one Pipeline's IPC surface until close()."""

    def __init__(
        self,
        pipeline: Pipeline,
        config: IpcConfig,
        on_shutdown: Callable[[], None] | None = None,
    ) -> None:
        self._pipeline = pipeline
        self._config = config
        self._on_shutdown = on_shutdown
        self._web_root = resolve_web_root(config.web_root)
        self._subscribers: list[queue.Queue[str]] = []
        self._subscribers_lock = threading.Lock()
        pipeline.add_listener(self._on_event)

        server = self

        class Handler(BaseHTTPRequestHandler):
            protocol_version = "HTTP/1.1"

            def log_message(self, fmt: str, *args: Any) -> None:
                log.debug("api: " + fmt, *args)

            def _cors(self) -> None:
                self.send_header("Access-Control-Allow-Origin", "*")
                self.send_header("Access-Control-Allow-Headers", "Authorization, Content-Type")
                self.send_header("Access-Control-Allow-Methods", "GET, POST, OPTIONS")

            def _authorized(self) -> bool:
                token = server._config.token
                if not token:
                    return True
                return self.headers.get("Authorization", "") == f"Bearer {token}"

            def _reply(self, status: int, body: str) -> None:
                payload = body.encode()
                self.send_response(status)
                self._cors()
                self.send_header("Content-Type", "application/json")
                self.send_header("Content-Length", str(len(payload)))
                self.end_headers()
                self.wfile.write(payload)

            def do_OPTIONS(self) -> None:  # CORS preflight
                self.send_response(204)
                self._cors()
                self.send_header("Content-Length", "0")
                self.end_headers()

            def _serve_static(self, target: Path) -> None:
                payload = target.read_bytes()
                content_type = _STATIC_TYPES.get(target.suffix) or (
                    mimetypes.guess_type(target.name)[0] or "application/octet-stream"
                )
                self.send_response(200)
                self._cors()
                self.send_header("Content-Type", content_type)
                self.send_header("Content-Length", str(len(payload)))
                self.send_header("Cache-Control", "no-cache")
                self.end_headers()
                self.wfile.write(payload)

            def do_GET(self) -> None:
                target = static_target(self.path.split("?", 1)[0], server._web_root)
                if target is not None:
                    self._serve_static(target)
                    return
                if not self._authorized():
                    self._reply(401, '{"error": "unauthorized"}')
                    return
                if self.path == "/status":
                    self._reply(200, encode_event("status", server._pipeline.status()))
                elif self.path == "/events":
                    self._stream_events()
                elif self.path == "/" and server._web_root is None:
                    self._reply(
                        404,
                        json.dumps(
                            {
                                "error": "web front end not found",
                                "hint": f"set [ipc] web_root (looked for {server._config.web_root})",
                            }
                        ),
                    )
                else:
                    self._reply(404, '{"error": "not found"}')

            def do_POST(self) -> None:
                if not self._authorized():
                    self._reply(401, '{"error": "unauthorized"}')
                    return
                if self.path != "/command":
                    self._reply(404, '{"error": "not found"}')
                    return
                length = int(self.headers.get("Content-Length", 0))
                if length > _MAX_BODY:
                    self._reply(413, '{"error": "body too large"}')
                    return
                try:
                    command = parse_command(self.rfile.read(length))
                except ProtocolError as exc:
                    self._reply(400, json.dumps({"error": str(exc)}))
                    return
                self._reply(200, json.dumps(server._run_command(command)))

            def _stream_events(self) -> None:
                events = server._subscribe()
                try:
                    self.send_response(200)
                    self._cors()
                    self.send_header("Content-Type", "text/event-stream")
                    self.send_header("Cache-Control", "no-cache")
                    self.end_headers()
                    # Initial status so a client renders without a poll.
                    self._write_sse(encode_event("status", server._pipeline.status()))
                    while True:
                        try:
                            message = events.get(timeout=15.0)
                        except queue.Empty:
                            self.wfile.write(b": keepalive\n\n")
                            self.wfile.flush()
                            continue
                        self._write_sse(message)
                except (BrokenPipeError, ConnectionResetError):
                    pass  # client went away
                finally:
                    server._unsubscribe(events)

            def _write_sse(self, message: str) -> None:
                self.wfile.write(b"data: " + message.encode() + b"\n\n")
                self.wfile.flush()

        self._http = ThreadingHTTPServer((config.host, config.port), Handler)
        self._http.daemon_threads = True
        self._thread = threading.Thread(target=self._http.serve_forever, daemon=True)

    # -- lifecycle -----------------------------------------------------------

    def start(self) -> None:
        self._thread.start()
        log.info("ipc api listening on http://%s:%d", *self.address)

    @property
    def address(self) -> tuple[str, int]:
        host, port = self._http.server_address[:2]
        return str(host), int(port)

    def close(self) -> None:
        self._pipeline.remove_listener(self._on_event)
        self._http.shutdown()
        self._http.server_close()

    # -- plumbing ------------------------------------------------------------

    def _run_command(self, command: Command) -> dict[str, Any]:
        if command.type == "get_status":
            return {"ok": True, "status": self._pipeline.status()}
        if command.type == "override":
            self._pipeline.set_override(command.mode)
            return {"ok": True, "override": command.mode}
        if command.type == "set_trace":
            self._pipeline.set_trace(command.enabled)
            return {"ok": True, "trace": command.enabled}
        if command.type == "confirm_ad":
            return {"ok": self._pipeline.confirm_ad()}
        if command.type == "reject_ad":
            return {"ok": self._pipeline.reject_ad()}
        if command.type == "shutdown":
            if self._on_shutdown is None:
                return {"ok": False, "error": "shutdown not available on this core"}
            threading.Timer(_SHUTDOWN_DELAY_S, self._on_shutdown).start()
            return {"ok": True, "shutdown": True}
        return {"ok": False, "error": f"unhandled command {command.type}"}

    def _subscribe(self) -> queue.Queue[str]:
        events: queue.Queue[str] = queue.Queue(maxsize=_SSE_QUEUE_SIZE)
        with self._subscribers_lock:
            self._subscribers.append(events)
        return events

    def _unsubscribe(self, events: queue.Queue[str]) -> None:
        with self._subscribers_lock:
            if events in self._subscribers:
                self._subscribers.remove(events)

    def _on_event(self, kind: str, payload: object) -> None:
        message = encode_event(kind, payload)
        with self._subscribers_lock:
            subscribers = list(self._subscribers)
        for events in subscribers:
            try:
                events.put_nowait(message)
            except queue.Full:
                log.warning("dropping IPC event for a slow subscriber")
