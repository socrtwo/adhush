"""Always-on-top mini window: PROGRAM/MUTED at a glance, three buttons, draggable.

A thin client over the core's HTTP+SSE API, in the standard library only
(``tkinter`` for the window, ``urllib`` for the wire). Split in two so the
part that matters is testable without a display:

- ``OverlayClient`` — a background thread that follows ``/events`` (falling
  back to polling ``/status``) and posts commands. Unlike a browser's
  ``EventSource``, ``urllib`` can send the bearer token, so SSE works with a
  token set.
- ``OverlayWindow`` — the borderless, topmost, translucent pill that renders
  what the client reports. Drag anywhere to move; the position is remembered.
  ``✗`` = not an ad, ``✓`` = is an ad, ``■`` = stop the core (ADR 0008).

Runs as its own process (``adhush overlay``), so nothing here can stall the
detection loop, and closing the window never stops detection — only ``■``
does, deliberately, through the ``shutdown`` command.
"""

from __future__ import annotations

import json
import queue
import threading
import urllib.error
import urllib.request
from collections.abc import Callable
from pathlib import Path
from typing import Any

# Colours chosen to read at a glance from across a room.
GREEN = "#46c46f"
RED = "#e05555"
AMBER = "#e0a545"
GREY = "#8a97a5"

_POLL_S = 2.0
_SSE_TIMEOUT_S = 40.0  # the server sends a keepalive every 15 s
_RETRY_S = 2.0


def state_style(status: dict[str, Any] | None) -> tuple[str, str]:
    """(label, colour) for a status payload; tolerant of anything missing."""
    if not status or status.get("unreachable"):
        return ("OFFLINE", GREY)
    # A forced override is what the user asked for; it wins over the state
    # machine, even before the machine has had a tick to act on it.
    if status.get("override") == "mute":
        return ("MUTED", RED)
    if status.get("override") == "unmute":
        return ("PROGRAM", GREEN)
    if status.get("muted"):
        return ("MUTED", RED)
    state = str(status.get("state", "")).lower()
    if state == "suspect_ad":
        return ("SUSPECT", AMBER)
    if state in ("program", "recovery"):
        return ("PROGRAM", GREEN)
    if state == "ad":
        return ("MUTED", RED)
    return (state.upper() or "…", GREY)


class OverlayClient:
    """Follows a core's status on a background thread and sends it commands."""

    def __init__(
        self,
        base: str,
        token: str = "",
        on_status: Callable[[dict[str, Any]], None] | None = None,
        *,
        poll_s: float = _POLL_S,
        retry_s: float = _RETRY_S,
    ) -> None:
        self._base = base.rstrip("/")
        self._token = token
        self._on_status = on_status or (lambda _status: None)
        self._poll_s = poll_s
        self._retry_s = retry_s
        self._stop = threading.Event()
        self._thread = threading.Thread(target=self._run, daemon=True, name="adhush-overlay")

    # -- lifecycle -------------------------------------------------------------

    def start(self) -> None:
        self._thread.start()

    def stop(self) -> None:
        self._stop.set()

    # -- wire ------------------------------------------------------------------

    def _headers(self) -> dict[str, str]:
        headers = {"Content-Type": "application/json"}
        if self._token:
            headers["Authorization"] = f"Bearer {self._token}"
        return headers

    def command(self, body: dict[str, Any]) -> dict[str, Any]:
        """POST one protocol command; returns the server's JSON reply."""
        data = json.dumps({"v": 1, **body}).encode()
        request = urllib.request.Request(
            f"{self._base}/command", data=data, headers=self._headers(), method="POST"
        )
        with urllib.request.urlopen(request, timeout=5.0) as response:
            reply: dict[str, Any] = json.loads(response.read())
            return reply

    def status(self) -> dict[str, Any]:
        request = urllib.request.Request(f"{self._base}/status", headers=self._headers())
        with urllib.request.urlopen(request, timeout=5.0) as response:
            payload: dict[str, Any] = json.loads(response.read())["data"]
            return payload

    def reject_ad(self) -> dict[str, Any]:
        return self.command({"type": "reject_ad"})

    def confirm_ad(self) -> dict[str, Any]:
        return self.command({"type": "confirm_ad"})

    def shutdown_core(self) -> dict[str, Any]:
        return self.command({"type": "shutdown"})

    # -- following -------------------------------------------------------------

    def _run(self) -> None:
        while not self._stop.is_set():
            try:
                self._follow_events()
            except (urllib.error.URLError, OSError, ValueError):
                self._on_status({"unreachable": True})
                # SSE unavailable (older core?) — poll until it is back.
                self._poll_once()
            if not self._stop.is_set():
                self._stop.wait(self._retry_s)

    def _poll_once(self) -> None:
        try:
            self._on_status(self.status())
        except (urllib.error.URLError, OSError, ValueError):
            pass

    def _follow_events(self) -> None:
        request = urllib.request.Request(f"{self._base}/events", headers=self._headers())
        with urllib.request.urlopen(request, timeout=_SSE_TIMEOUT_S) as response:
            buffer: list[str] = []
            for raw in response:
                if self._stop.is_set():
                    return
                line = raw.decode("utf-8", errors="replace").rstrip("\r\n")
                if line.startswith("data:"):
                    buffer.append(line[5:].strip())
                elif line == "" and buffer:
                    self._dispatch("\n".join(buffer))
                    buffer = []
                # comment / keepalive lines are ignored

    def _dispatch(self, payload: str) -> None:
        message = json.loads(payload)
        if message.get("type") == "status":
            self._on_status(dict(message.get("data", {})))
        elif message.get("type") == "transition":
            self._poll_once()


# -- the window ----------------------------------------------------------------

_DEFAULT_POSITION_FILE = Path.home() / ".adhush" / "overlay.json"
_DRAG_THRESHOLD_PX = 4


def load_position(path: Path) -> tuple[int, int] | None:
    try:
        data = json.loads(path.read_text())
        return int(data["x"]), int(data["y"])
    except (OSError, ValueError, KeyError, TypeError):
        return None


def save_position(path: Path, x: int, y: int) -> None:
    try:
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(json.dumps({"x": x, "y": y}))
    except OSError:
        pass  # a forgotten position is not worth a crash


class OverlayWindow:
    """The pill. Requires tkinter; import happens here so the client stays headless-safe."""

    def __init__(self, client: OverlayClient, position_file: Path) -> None:
        import tkinter as tk

        self._tk = tk
        self._client = client
        self._position_file = position_file
        self._events: queue.Queue[dict[str, Any]] = queue.Queue()
        self._press: tuple[int, int, int, int] | None = None
        self._dragged = False

        root = tk.Tk()
        self._root = root
        root.title("AdHush")
        root.overrideredirect(True)  # no title bar: small and unobtrusive
        root.attributes("-topmost", True)
        try:
            root.attributes("-alpha", 0.92)
        except tk.TclError:
            pass  # compositor without alpha; opaque is fine

        bg = "#101418"
        frame = tk.Frame(root, bg=bg, padx=10, pady=6, highlightthickness=1,
                         highlightbackground="#2a3542")
        frame.pack()
        self._dot = tk.Canvas(frame, width=14, height=14, bg=bg, highlightthickness=0)
        self._dot_id = self._dot.create_oval(2, 2, 12, 12, fill=GREY, outline="")
        self._dot.pack(side="left")
        self._label = tk.Label(frame, text="…", fg="#e8edf2", bg=bg,
                               font=("TkDefaultFont", 10, "bold"), padx=6)
        self._label.pack(side="left")
        for glyph, action, tip in (
            ("✗", self._reject, "not an ad"),
            ("✓", self._confirm, "is an ad"),
            ("■", self._stop_core, "stop AdHush"),
        ):
            button = tk.Label(frame, text=glyph, fg="#c9d3dd", bg=bg, padx=5, cursor="hand2")
            button.pack(side="left")
            self._wire_button(button, action)
            self._bind_drag(button)
        for widget in (root, frame, self._dot, self._label):
            self._bind_drag(widget)
        root.bind("<Button-3>", self._menu)

        position = load_position(position_file)
        root.update_idletasks()
        if position is None:
            x = root.winfo_screenwidth() - root.winfo_reqwidth() - 24
            y = root.winfo_screenheight() - root.winfo_reqheight() - 72
        else:
            x, y = position
        root.geometry(f"+{max(0, x)}+{max(0, y)}")
        root.after(100, self._drain)

    def _wire_button(self, button: Any, action: Callable[[], None]) -> None:
        def release(_event: Any) -> None:
            self._click(action)

        def enter(_event: Any) -> None:
            button.configure(fg="#ffffff")

        def leave(_event: Any) -> None:
            button.configure(fg="#c9d3dd")

        button.bind("<ButtonRelease-1>", release)
        button.bind("<Enter>", enter)
        button.bind("<Leave>", leave)

    # -- drag anywhere ---------------------------------------------------------

    def _bind_drag(self, widget: Any) -> None:
        widget.bind("<ButtonPress-1>", self._on_press, add="+")
        widget.bind("<B1-Motion>", self._on_motion, add="+")
        widget.bind("<ButtonRelease-1>", self._on_release, add="+")

    def _on_press(self, event: Any) -> None:
        self._press = (event.x_root, event.y_root, self._root.winfo_x(), self._root.winfo_y())
        self._dragged = False

    def _on_motion(self, event: Any) -> None:
        if self._press is None:
            return
        x0, y0, wx, wy = self._press
        dx, dy = event.x_root - x0, event.y_root - y0
        if abs(dx) > _DRAG_THRESHOLD_PX or abs(dy) > _DRAG_THRESHOLD_PX:
            self._dragged = True
            self._root.geometry(f"+{wx + dx}+{wy + dy}")

    def _on_release(self, _event: Any) -> None:
        if self._dragged:
            save_position(self._position_file, self._root.winfo_x(), self._root.winfo_y())
        self._press = None

    def _click(self, action: Callable[[], None]) -> None:
        if not self._dragged:  # a drag that ends on a button is not a click
            action()

    # -- actions ---------------------------------------------------------------

    def _in_background(self, call: Callable[[], Any]) -> None:
        def run() -> None:
            try:
                call()
            except (urllib.error.URLError, OSError, ValueError):
                self._events.put({"unreachable": True})

        threading.Thread(target=run, daemon=True).start()

    def _reject(self) -> None:
        self._in_background(self._client.reject_ad)

    def _confirm(self) -> None:
        self._in_background(self._client.confirm_ad)

    def _stop_core(self) -> None:
        def stop() -> None:
            self._client.shutdown_core()
            self._root.after(0, self._root.destroy)

        self._in_background(stop)

    def _menu(self, event: Any) -> None:
        tk = self._tk
        menu = tk.Menu(self._root, tearoff=0)
        menu.add_command(label="Not an ad", command=self._reject)
        menu.add_command(label="Is an ad", command=self._confirm)
        menu.add_separator()
        menu.add_command(label="Hide this window (core keeps running)", command=self._root.destroy)
        menu.add_command(label="Stop AdHush", command=self._stop_core)
        menu.tk_popup(event.x_root, event.y_root)

    # -- rendering -------------------------------------------------------------

    def push_status(self, status: dict[str, Any]) -> None:
        """Called from the client thread; marshalled onto the Tk thread."""
        self._events.put(status)

    def _drain(self) -> None:
        latest: dict[str, Any] | None = None
        try:
            while True:
                latest = self._events.get_nowait()
        except queue.Empty:
            pass
        if latest is not None:
            label, colour = state_style(latest)
            self._label.configure(text=label)
            self._dot.itemconfigure(self._dot_id, fill=colour)
        self._root.after(100, self._drain)

    def run(self) -> None:
        self._root.mainloop()


def main(
    base: str = "http://127.0.0.1:8675",
    token: str = "",
    position_file: Path | None = None,
) -> int:
    try:
        import tkinter  # noqa: F401
    except ImportError:
        print("adhush overlay: tkinter is not installed (Debian/Ubuntu: apt install python3-tk)")
        return 2
    client = OverlayClient(base, token)
    window = OverlayWindow(client, position_file or _DEFAULT_POSITION_FILE)
    client._on_status = window.push_status
    client.start()
    try:
        window.run()
    finally:
        client.stop()
    return 0


if __name__ == "__main__":  # `python -m adhush.ui.overlay` for a stdlib-only smoke test
    import argparse

    parser = argparse.ArgumentParser()
    parser.add_argument("--base", default="http://127.0.0.1:8675")
    parser.add_argument("--token", default="")
    parser.add_argument("--position-file", type=Path, default=None)
    parser.add_argument("--exit-after", type=float, default=0.0, help="seconds; smoke tests")
    ns = parser.parse_args()
    if ns.exit_after > 0:
        import os

        threading.Timer(ns.exit_after, os._exit, args=(0,)).start()
    raise SystemExit(main(ns.base, ns.token, ns.position_file))
