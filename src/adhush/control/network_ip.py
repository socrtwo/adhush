"""IP control for sets exposing a TCP/HTTP/WebSocket API.

Entirely profile-driven so device specifics stay in TOML, not Python. Two
transports:

- ``tcp`` — raw command strings over one connection kept open across
  commands and reopened when the set drops it (e.g. Sony BRAVIA Simple IP on
  port 20060, or Sharp AQUOS IP control). A command may declare an optional
  reply check, and a ``state`` command with ``expect_on``/``expect_off``
  substrings gives real mute readback. Sets that guard the port with
  credentials (``login_id`` / ``login_password``) get the handshake answered
  once per connection. The Sharp allows one control connection at a time and
  ignored per-command connections on real firmware (phone test, 2026-09-09),
  which is why the connection persists.

**Ducking.** With ``duck_level`` set (and ``volume_set`` / ``volume_query``
commands in the profile), ``mute()`` turns the set *down* to that level and
``unmute()`` puts the volume back where it was — so a microphone keeps
hearing the set through the break, which is how the listener knows when the
show is back (ADR 0014). The pre-duck volume is written to
``duck_state_file`` before ducking so a crash never leaves the set quiet.
- ``http`` — one request per command: method, path, optional body and
  headers (e.g. Roku ECP ``POST /keypress/VolumeMute``).

Discrete mute is claimed when distinct on/off commands exist. Transports are
injectable so command framing is testable without a device. WebSocket APIs
(LG webOS pairing and the like) are out of scope until a dependency is
warranted; their sets usually also answer one of the simpler paths or IR.
"""

from __future__ import annotations

import logging
import socket
import urllib.error
import urllib.request
from collections.abc import Callable
from pathlib import Path
from typing import Any, Protocol

from adhush.control.base import ControlError, MuteController
from adhush.control.remote_keys import SHARP_RCKY

log = logging.getLogger(__name__)

# tcp: payload bytes -> reply bytes (possibly empty).
TcpExchange = Callable[[bytes], bytes]
# http: (method, url, body, headers) -> (status, reply body)
HttpExchange = Callable[[str, str, bytes | None, dict[str, str]], tuple[int, bytes]]


# Credentials are answered per connection because each command opens its own;
# that also sidesteps the idle-disconnect timers these sets apply.
# The LC-46LE830U ends each login field at CR; with CRLF the stray LF became the
# password and the set answered "User Name or Password mismatch" (phone test,
# 2026-09-09). Override per set with the ``login_terminator`` option.
_LOGIN_TERMINATOR = b"\r"
# Substrings a set sends when it refuses the credentials.
_LOGIN_REJECTED = ("login incorrect", "denied", "invalid", "mismatch")
BUSY_MESSAGE = (
    "the set hung up before asking for a login: it allows one control connection at a "
    "time — another AdHush, another phone, or another app is probably connected"
)
# A half-open connection looks like a silent set; after this many silent
# replies in a row the connection is reopened rather than trusted further.
_SILENT_LIMIT = 3


class _Closed(OSError):
    """The set closed the connection (recv returned nothing)."""


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
            data = stream.recv(4096)
        except (TimeoutError, OSError):
            return b""  # silent firmware is fine; send anyway
        if data == b"":
            raise _Closed("the set closed the connection")
        return data

    # A set that closes the line before it has even asked who is calling is
    # not refusing the credentials: someone else holds its one connection.
    try:
        read()  # "Login:"
    except _Closed as exc:
        raise ControlError(BUSY_MESSAGE) from exc
    stream.sendall(login_id.encode("ascii") + terminator)
    try:
        prompt = read()  # "Password:"
        _refuse_if_rejected(prompt, login_id)
        stream.sendall(password.encode("ascii") + terminator)
        ack = read()
    except _Closed as exc:
        # The set hangs up on refused credentials, sometimes before its words arrive.
        raise ControlError(
            f"tv rejected IP control login for '{login_id}': hung up during the handshake"
        ) from exc
    _refuse_if_rejected(ack, login_id)
    return ack


def _refuse_if_rejected(reply: bytes, login_id: str) -> None:
    text = reply.decode("ascii", errors="replace").strip().lower()
    if any(marker in text for marker in _LOGIN_REJECTED):
        raise ControlError(f"tv rejected IP control login for '{login_id}': {text}")


class PersistentTcp:
    """One connection kept open across commands; reopened when the set drops it.

    Exchanges are serialised. A late reply to an earlier command is drained
    before the next one is sent, so replies never shift by one message.
    """

    def __init__(
        self,
        host: str,
        port: int,
        timeout_s: float,
        login: tuple[str, str] | None = None,
        login_terminator: bytes = _LOGIN_TERMINATOR,
    ) -> None:
        self._host, self._port, self._timeout = host, port, timeout_s
        self._login, self._terminator = login, login_terminator
        self._sock: socket.socket | None = None
        self._silent = 0
        self.connections = 0

    def _open(self) -> socket.socket:
        sock = socket.create_connection((self._host, self._port), timeout=self._timeout)
        sock.settimeout(self._timeout)
        self.connections += 1
        try:
            if self._login is not None:
                perform_login(
                    sock, *self._login, terminator=self._terminator, timeout_s=self._timeout
                )
                # Read until the line is quiet: a late prompt would otherwise be
                # taken for the first command's reply.
                sock.settimeout(0.3)
                try:
                    while sock.recv(4096):
                        pass
                except (TimeoutError, OSError):
                    pass
                sock.settimeout(self._timeout)
        except BaseException:
            sock.close()
            raise
        return sock

    def drop(self) -> None:
        if self._sock is not None:
            try:
                self._sock.close()
            except OSError:
                pass
        self._sock = None

    close = drop

    def __call__(self, payload: bytes) -> bytes:
        for attempt in (1, 2):
            try:
                if self._sock is None:
                    self._sock = self._open()
                sock = self._sock
                sock.setblocking(False)
                try:  # drain a late reply to an earlier command
                    while True:
                        if sock.recv(4096) == b"":
                            raise _Closed("the set closed the connection")
                except BlockingIOError:
                    pass
                sock.settimeout(self._timeout)
                sock.sendall(payload)
                try:
                    reply = sock.recv(4096)
                except TimeoutError:
                    reply = b""  # some sets simply don't answer
                else:
                    if reply == b"":
                        raise _Closed("the set closed the connection")  # hung up: reconnect and resend
                if reply == b"":
                    self._silent += 1
                    if self._silent >= _SILENT_LIMIT:
                        self._silent = 0
                        self.drop()
                else:
                    self._silent = 0
                return reply
            except ControlError:
                self.drop()
                raise
            except OSError as exc:
                self.drop()
                if attempt == 2:
                    raise ControlError(
                        f"tv {self._host}:{self._port} unreachable: {exc}"
                    ) from exc
        return b""  # unreachable: the loop returns or raises


def _tcp_exchange(
    host: str,
    port: int,
    timeout_s: float,
    login: tuple[str, str] | None = None,
    login_terminator: bytes = _LOGIN_TERMINATOR,
) -> TcpExchange:
    return PersistentTcp(host, port, timeout_s, login, login_terminator)


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
        duck = options.get("duck_level")
        self._duck_level: int | None = None if duck is None else int(duck)
        self._normal_volume = int(options.get("normal_volume", 20))
        self._duck_state_file = str(options.get("duck_state_file", ""))
        self._pre_duck: int | None = None
        self._ducked = False
        if self._duck_level is not None and "volume_set" not in self._commands:
            raise ControlError("network_ip duck_level needs a commands.volume_set")
        if self._duck_level is None and not (
            ("mute_on" in self._commands and "mute_off" in self._commands)
            or "mute_toggle" in self._commands
        ):
            raise ControlError(
                "network_ip needs commands.mute_on+mute_off, commands.mute_toggle, "
                "or duck_level with commands.volume_set"
            )
        host = str(options.get("host", ""))
        timeout_s = float(options.get("timeout_s", 2.0))
        self._base_url = ""
        self._tcp: TcpExchange | None = None
        self._http: HttpExchange | None = None
        login_id = str(options.get("login_id", ""))
        login_password = str(options.get("login_password", ""))
        login = (login_id, login_password) if login_id or login_password else None
        login_terminator = str(options.get("login_terminator", "\r")).encode("ascii")
        if self._transport == "tcp":
            if tcp is not None:
                self._tcp = tcp
            else:
                if not host:
                    raise ControlError("network_ip.host is required")
                self._tcp = _tcp_exchange(
                    host,
                    int(options.get("port", 0) or 0),
                    timeout_s,
                    login,
                    login_terminator,
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

    def _run(self, name: str, **fields: Any) -> bytes:
        command = self._commands.get(name)
        if command is None:
            raise ControlError(f"network_ip command '{name}' not configured")
        if self._transport == "tcp":
            assert self._tcp is not None
            send = str(command["send"])
            if fields:
                send = send.format(**fields)
            reply = self._tcp(send.encode("ascii"))
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

    def send_key(self, key: str) -> None:
        code = SHARP_RCKY.get(key)
        if code is None:
            raise ControlError(f"unknown remote key '{key}'")
        if "remote_key" not in self._commands:
            raise ControlError("network_ip remote keys need a commands.remote_key in the profile")
        self._run("remote_key", code=code)

    # -- ducking ---------------------------------------------------------------

    def query_volume(self) -> int | None:
        """The set's present volume, or None when it does not answer."""
        if "volume_query" not in self._commands:
            return None
        try:
            reply = self._run("volume_query").decode("ascii", errors="replace")
        except ControlError:
            return None
        digits = "".join(ch for ch in reply if ch.isdigit())
        return int(digits) if digits else None

    def _set_volume(self, level: int) -> None:
        self._run("volume_set", level=max(0, min(99, level)))

    def _remember(self, volume: int | None) -> None:
        self._pre_duck = volume
        if not self._duck_state_file:
            return
        path = Path(self._duck_state_file)
        try:
            if volume is None:
                path.unlink(missing_ok=True)
            else:
                path.parent.mkdir(parents=True, exist_ok=True)
                path.write_text(str(volume))
        except OSError:
            log.warning("could not write %s", path)

    def recover_on_start(self) -> bool:
        """A previous run died ducked: put the volume back. True when it did."""
        if not self._duck_state_file:
            return False
        try:
            saved = int(Path(self._duck_state_file).read_text().strip())
        except (OSError, ValueError):
            return False
        self._set_volume(saved)
        self._remember(None)
        return True

    @property
    def ducked(self) -> bool:
        return self._ducked

    def mute(self) -> None:
        if self._duck_level is None:
            self._run("mute_on" if "mute_on" in self._commands else "mute_toggle")
            return
        if self._ducked:
            return
        now = self.query_volume()
        if now is not None and now != self._duck_level:
            self._remember(now)
        elif self._pre_duck is None:
            self._remember(self._normal_volume)
        self._set_volume(self._duck_level)
        self._ducked = True

    def unmute(self) -> None:
        if self._duck_level is None:
            self._run("mute_off" if "mute_off" in self._commands else "mute_toggle")
            return
        back = self._pre_duck if self._pre_duck is not None else self._normal_volume
        self._set_volume(back)
        self._ducked = False
        self._remember(None)

    def state(self) -> bool | None:
        if self._duck_level is not None:
            now = self.query_volume()
            return self._ducked if now is None else now <= self._duck_level
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
        return self._duck_level is not None or (
            "mute_on" in self._commands and "mute_off" in self._commands
        )

    def close(self) -> None:
        if self._ducked:
            try:
                self.unmute()
            except ControlError:
                log.warning("could not restore the volume on close")
        if isinstance(self._tcp, PersistentTcp):
            self._tcp.close()
