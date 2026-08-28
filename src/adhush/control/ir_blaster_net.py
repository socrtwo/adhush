"""Networked IR blasters (Broadlink RM4, Global Cache iTach) for non-Pi platforms.

Two kinds:

- ``itach`` — Global Caché's plain-TCP ASCII protocol (port 4998). Codes are
  complete ``sendir`` command strings captured with iLearn; the device
  answers ``completeir`` on success. The TCP exchange is injectable, so
  framing is testable without hardware.
- ``broadlink`` — RM3/RM4 family through the ``broadlink`` package (imported
  lazily; the protocol is encrypted and not worth reimplementing). Codes are
  base64 IR packets captured with the device's learn mode.

Both are open-loop like any IR path: discrete mute only when distinct on/off
codes exist, and audio verification is the closed loop.
"""

from __future__ import annotations

import base64
import socket
from collections.abc import Callable
from typing import Any

from adhush.control.base import ControlError, MuteController

# Sends one framed command, returns the raw reply (may be empty).
TcpExchange = Callable[[bytes], bytes]

_ITACH_PORT = 4998
_ITACH_TERMINATOR = b"\r"


def _tcp_exchange(host: str, port: int, timeout_s: float) -> TcpExchange:
    def exchange(payload: bytes) -> bytes:
        try:
            with socket.create_connection((host, port), timeout=timeout_s) as sock:
                sock.sendall(payload)
                sock.settimeout(timeout_s)
                return sock.recv(4096)
        except OSError as exc:
            raise ControlError(f"ir blaster {host}:{port} unreachable: {exc}") from exc

    return exchange


class _ItachBackend:
    def __init__(self, options: dict[str, Any], exchange: TcpExchange | None) -> None:
        host = str(options.get("host", ""))
        if not host and exchange is None:
            raise ControlError("ir_blaster_net.host is required for itach")
        port = int(options.get("port", _ITACH_PORT))
        timeout_s = float(options.get("timeout_s", 2.0))
        self._exchange = exchange if exchange is not None else _tcp_exchange(
            host, port, timeout_s
        )

    def send(self, code: str) -> None:
        if not code.startswith("sendir"):
            raise ControlError("itach codes must be full 'sendir,...' strings (iLearn)")
        reply = self._exchange(code.encode("ascii") + _ITACH_TERMINATOR)
        text = reply.decode("ascii", errors="replace").strip()
        if not text.startswith("completeir"):
            raise ControlError(f"itach rejected sendir: {text or 'no reply'}")


class _BroadlinkBackend:
    def __init__(self, options: dict[str, Any]) -> None:
        try:
            import broadlink  # type: ignore[import-not-found]
        except ImportError as exc:
            raise ControlError(
                "broadlink blasters require the 'broadlink' package"
            ) from exc
        host = str(options.get("host", ""))
        if not host:
            raise ControlError("ir_blaster_net.host is required for broadlink")
        self._device = broadlink.hello(host, timeout=int(options.get("timeout_s", 3)))
        self._device.auth()

    def send(self, code: str) -> None:
        self._device.send_data(base64.b64decode(code))


class IrBlasterNetController(MuteController):
    def __init__(
        self, options: dict[str, Any], exchange: TcpExchange | None = None
    ) -> None:
        codes = options.get("codes", {})
        self._code_on = str(codes.get("mute_on", ""))
        self._code_off = str(codes.get("mute_off", ""))
        self._code_toggle = str(codes.get("mute_toggle", ""))
        if not ((self._code_on and self._code_off) or self._code_toggle):
            raise ControlError(
                "ir_blaster_net needs codes.mute_on+mute_off, or codes.mute_toggle"
            )
        kind = str(options.get("kind", "itach")).lower()
        if kind == "itach":
            self._backend: _ItachBackend | _BroadlinkBackend = _ItachBackend(
                options, exchange
            )
        elif kind == "broadlink":
            self._backend = _BroadlinkBackend(options)
        else:
            raise ControlError(f"unknown ir_blaster_net kind: {kind}")

    def mute(self) -> None:
        self._backend.send(self._code_on if self._code_on else self._code_toggle)

    def unmute(self) -> None:
        self._backend.send(self._code_off if self._code_off else self._code_toggle)

    def supports_discrete(self) -> bool:
        return bool(self._code_on and self._code_off)
