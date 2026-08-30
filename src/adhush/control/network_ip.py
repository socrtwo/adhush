"""IP control for sets exposing a TCP/HTTP/WebSocket API.

Entirely profile-driven so device specifics stay in TOML, not Python. Two
transports:

- ``tcp`` — raw command strings over a short-lived connection (e.g. Sony
  BRAVIA Simple IP on port 20060, or Sharp AQUOS IP control). A command may
  declare an optional reply check, and a ``state`` command with
  ``expect_on``/``expect_off`` substrings gives real mute readback. Sets that
  guard the port with credentials (``login_id`` / ``login_password``) get the
  handshake answered on every connection, since each command opens its own.
- ``http`` — one request per command: method, path, optional body and
  headers (e.g. Roku ECP ``POST /keypress/VolumeMute``).

Discrete mute is claimed when distinct on/off commands exist. Transports are
injectable so command framing is testable without a device. WebSocket APIs
(LG webOS pairing and the like) are out of scope until a dependency is
warranted; their sets usually also answer one of the simpler paths or IR.
"""

from __future__ import annotations

import socket
import urllib.error
import urllib.request
from collections.abc import Callable
from typing import Any, Protocol

from adhush.control.base import ControlError, MuteController

# tcp: payload bytes -> reply bytes (possibly empty).
TcpExchange = Callable[[bytes], bytes]
# http: (method, url, body, headers) -> (status, reply body)
HttpExchange = Callable[[str, str, bytes | None, dict[str, str]], tuple[int, bytes]]


# Credentials are answered per connection because each command opens its own;
# that also sidesteps the idle-disconnect timers these sets apply.
_LOGIN_TERMINATOR = b"\r\n"
# Substrings a set sends when it refuses the credentials.
_LOGIN_REJECTED = ("login incorrect", "denied", "invalid")


class Stream(Protocol):
    """The slice of a socket the login handshake needs; fakeable in tests."""

    def sendall(self, data: bytes) -> None: ...

    def recv(self, bufsize: int) -> bytes: ...

    def settimeout(self, timeout: float | None) -> None: ...


def perform_login(
    stream: Stream,
    login_id: str,
    password: str,
    *,
    terminator: bytes = _LOGIN_TERMINATOR,
    timeout_s: float = 2.0,
) -> bytes:
    """Answer a set's ``Login:``/``Password:`` prompts right after connecting.

    Sharp AQUOS IP control wants the credentials "as soon as you connect to
    the TV" (LC-xxLE830U manual, *Communication conditions for IP*). Firmware
    varies in whether it prompts at all and how it acknowledges, so every read
    here is best-effort: a missing prompt is normal and not an error, while an
    explicit refusal raises. Returns whatever the set said after the password.
    """
    stream.settimeout(timeout_s)

    def read() -> bytes:
        try:
            return stream.recv(4096)
        except (TimeoutError, OSError):
            return b""  # silent firmware is fine; send anyway

    read()  # "Login:"
    stream.sendall(login_id.encode("ascii") + terminator)
    read()  # "Password:"
    stream.sendall(password.encode("ascii") + terminator)
    ack = read()
    text = ack.decode("ascii", errors="replace").strip().lower()
    if any(marker in text for marker in _LOGIN_REJECTED):
        raise ControlError(f"tv rejected IP control login for '{login_id}'")
    return ack


def _tcp_exchange(
    host: str,
    port: int,
    timeout_s: float,
    login: tuple[str, str] | None = None,
) -> TcpExchange:
    def exchange(payload: bytes) -> bytes:
        try:
            with socket.create_connection((host, port), timeout=timeout_s) as sock:
                sock.settimeout(timeout_s)
                if login is not None:
                    perform_login(sock, *login, timeout_s=timeout_s)
                sock.sendall(payload)
                try:
                    return sock.recv(4096)
                except TimeoutError:
                    return b""  # some sets simply don't answer
        except OSError as exc:
            raise ControlError(f"tv {host}:{port} unreachable: {exc}") from exc

    return exchange


def _http_exchange(timeout_s: float) -> HttpExchange:
    def exchange(
        method: str, url: str, body: bytes | None, headers: dict[str, str]
    ) -> tuple[int, bytes]:
        request = urllib.request.Request(url, data=body, headers=headers, method=method)
        try:
            with urllib.request.urlopen(request, timeout=timeout_s) as response:
                return int(response.status), response.read()
        except urllib.error.HTTPError as exc:
            return int(exc.code), exc.read()
        except (urllib.error.URLError, OSError) as exc:
            raise ControlError(f"tv http request failed: {exc}") from exc

    return exchange


class NetworkIpController(MuteController):
    def __init__(
        self,
        options: dict[str, Any],
        tcp: TcpExchange | None = None,
        http: HttpExchange | None = None,
    ) -> None:
        self._transport = str(options.get("transport", "tcp")).lower()
        if self._transport not in ("tcp", "http"):
            raise ControlError(f"unknown network_ip transport: {self._transport}")
        self._commands: dict[str, dict[str, Any]] = {
            name: dict(cmd) for name, cmd in options.get("commands", {}).items()
        }
        if not (
            ("mute_on" in self._commands and "mute_off" in self._commands)
            or "mute_toggle" in self._commands
        ):
            raise ControlError(
                "network_ip needs commands.mute_on+mute_off, or commands.mute_toggle"
            )
        host = str(options.get("host", ""))
        timeout_s = float(options.get("timeout_s", 2.0))
        self._base_url = ""
        self._tcp: TcpExchange | None = None
        self._http: HttpExchange | None = None
        login_id = str(options.get("login_id", ""))
        login_password = str(options.get("login_password", ""))
        login = (login_id, login_password) if login_id or login_password else None
        if self._transport == "tcp":
            if tcp is not None:
                self._tcp = tcp
            else:
                if not host:
                    raise ControlError("network_ip.host is required")
                self._tcp = _tcp_exchange(
                    host, int(options.get("port", 0) or 0), timeout_s, login
                )
        else:
            scheme = str(options.get("scheme", "http"))
            port = int(options.get("port", 8060))
            self._base_url = f"{scheme}://{host}:{port}"
            if http is not None:
                self._http = http
            else:
                if not host:
                    raise ControlError("network_ip.host is required")
                self._http = _http_exchange(timeout_s)

    def _run(self, name: str) -> bytes:
        command = self._commands.get(name)
        if command is None:
            raise ControlError(f"network_ip command '{name}' not configured")
        if self._transport == "tcp":
            assert self._tcp is not None
            reply = self._tcp(str(command["send"]).encode("ascii"))
            expect = command.get("expect")
            if expect is not None and str(expect) not in reply.decode(
                "ascii", errors="replace"
            ):
                raise ControlError(f"tv rejected {name}: {reply!r}")
            return reply
        assert self._http is not None
        url = command.get("url") or self._base_url + str(command.get("path", "/"))
        body_text = command.get("body")
        body = str(body_text).encode() if body_text is not None else None
        method = str(command.get("method", "POST"))
        headers = {str(k): str(v) for k, v in command.get("headers", {}).items()}
        status, reply = self._http(method, str(url), body, headers)
        if status >= 400:
            raise ControlError(f"tv rejected {name}: HTTP {status}")
        return reply

    def mute(self) -> None:
        self._run("mute_on" if "mute_on" in self._commands else "mute_toggle")

    def unmute(self) -> None:
        self._run("mute_off" if "mute_off" in self._commands else "mute_toggle")

    def state(self) -> bool | None:
        command = self._commands.get("state")
        if command is None:
            return None
        try:
            reply = self._run("state").decode("ascii", errors="replace")
        except ControlError:
            return None
        expect_on = str(command.get("expect_on", ""))
        expect_off = str(command.get("expect_off", ""))
        if expect_on and expect_on in reply:
            return True
        if expect_off and expect_off in reply:
            return False
        return None

    def supports_discrete(self) -> bool:
        return "mute_on" in self._commands and "mute_off" in self._commands
