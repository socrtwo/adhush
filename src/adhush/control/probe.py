"""Control-path discovery: which mute backends can plausibly drive this device.

Runs cheap, side-effect-free availability checks for every backend the device
profile lists (binaries on PATH, importable drivers, device nodes, TCP
reachability, code/command completeness) and reports them in the profile's
preference order with whether each offers discrete mute. Nothing here ever
sends a mute — the CLI's ``--active`` flag does that separately through the
real controller, so a probe is always safe to run mid-program.

The environment (PATH lookup, filesystem, socket connect, import check) is
injectable for tests. See docs/adr/0005-probe-module-owns-control-discovery.md.
"""

from __future__ import annotations

import importlib.util
import shutil
import socket
from collections.abc import Callable
from dataclasses import dataclass
from pathlib import Path
from typing import Any

from adhush.config import Config
from adhush.control import IMPLEMENTED_BACKENDS, resolve_options
from adhush.control.base import ControlError
from adhush.control.ir_pigpio import encode_code

Which = Callable[[str], str | None]
PathExists = Callable[[str], bool]
# Returns None when the TCP endpoint accepted a connection, else the error.
Connect = Callable[[str, int, float], str | None]
CanImport = Callable[[str], bool]


@dataclass(frozen=True, slots=True)
class ProbeResult:
    backend: str
    available: bool
    discrete: bool | None  # None = unknown until connected
    detail: str


def _try_connect(host: str, port: int, timeout_s: float) -> str | None:
    try:
        with socket.create_connection((host, port), timeout=timeout_s):
            return None
    except OSError as exc:
        return str(exc)


def _can_import(module: str) -> bool:
    try:
        return importlib.util.find_spec(module) is not None
    except (ImportError, ValueError):
        return False


def _ir_codes_check(options: dict[str, Any], *, encode: bool) -> tuple[bool, str, bool]:
    """Validate configured IR codes; returns (ok, detail, discrete)."""
    codes = options.get("codes", {})
    on, off, toggle = codes.get("mute_on"), codes.get("mute_off"), codes.get("mute_toggle")
    if not ((on and off) or toggle):
        return False, "no mute codes configured (profile [ir.codes])", False
    if encode:
        try:
            for code in (c for c in (on, off, toggle) if c):
                encode_code(dict(code), str(options.get("protocol", "nec")))
        except (ControlError, KeyError, TypeError, ValueError) as exc:
            return False, f"code invalid: {exc}", False
    return True, "codes present", bool(on and off)


def probe_backends(
    config: Config,
    *,
    which: Which = shutil.which,
    path_exists: PathExists | None = None,
    connect: Connect = _try_connect,
    can_import: CanImport = _can_import,
    timeout_s: float = 2.0,
) -> list[ProbeResult]:
    """Probe every backend the profile lists, in its preference order."""
    exists = path_exists if path_exists is not None else lambda p: Path(p).exists()
    backends = list(config.profile.control_backends) or list(IMPLEMENTED_BACKENDS)
    results = []
    for backend in backends:
        options = resolve_options(config.control, config.profile, backend)
        results.append(
            _probe_one(backend, options, which, exists, connect, can_import, timeout_s)
        )
    return results


def _probe_one(
    backend: str,
    options: dict[str, Any],
    which: Which,
    exists: PathExists,
    connect: Connect,
    can_import: CanImport,
    timeout_s: float,
) -> ProbeResult:
    if backend == "rs232_sharp":
        serial_port = str(options.get("port", "/dev/ttyUSB0"))
        if not can_import("serial"):
            return ProbeResult(backend, False, True, "pyserial not installed")
        if not exists(serial_port):
            return ProbeResult(backend, False, True, f"serial port {serial_port} not present")
        return ProbeResult(backend, True, True, f"serial port {serial_port} present")

    if backend == "ir_lirc":
        if which("irsend") is None:
            return ProbeResult(backend, False, None, "irsend not on PATH (install lirc)")
        remote = str(options.get("remote", ""))
        if not remote:
            return ProbeResult(backend, False, None, "no LIRC remote configured")
        discrete = bool(options.get("mute_on")) and bool(options.get("mute_off"))
        return ProbeResult(backend, True, discrete, f"irsend + remote '{remote}'")

    if backend == "ir_pigpio":
        ok, detail, discrete = _ir_codes_check(options, encode=True)
        if not ok:
            return ProbeResult(backend, False, discrete, detail)
        if not can_import("pigpio"):
            return ProbeResult(backend, False, discrete, "pigpio package not installed")
        return ProbeResult(backend, True, discrete, detail)

    if backend == "cec":
        if which("cec-client") is None:
            return ProbeResult(backend, False, False, "cec-client not on PATH (cec-utils)")
        return ProbeResult(backend, True, False, "cec-client present (toggle only)")

    if backend == "ir_blaster_net":
        ok, detail, discrete = _ir_codes_check(options, encode=False)
        if not ok:
            return ProbeResult(backend, False, discrete, detail)
        host = str(options.get("host", ""))
        if not host:
            return ProbeResult(backend, False, discrete, "no blaster host configured")
        kind = str(options.get("kind", "itach"))
        if kind == "broadlink":
            if not can_import("broadlink"):
                return ProbeResult(backend, False, discrete, "broadlink package not installed")
            return ProbeResult(backend, True, discrete, f"broadlink at {host}")
        error = connect(host, int(options.get("port", 4998)), timeout_s)
        if error is not None:
            return ProbeResult(backend, False, discrete, f"{host} unreachable: {error}")
        return ProbeResult(backend, True, discrete, f"itach at {host} reachable")

    if backend == "network_ip":
        commands = options.get("commands", {})
        discrete = "mute_on" in commands and "mute_off" in commands
        if not (discrete or "mute_toggle" in commands):
            return ProbeResult(backend, False, discrete, "no mute commands configured")
        host = str(options.get("host", ""))
        if not host:
            return ProbeResult(backend, False, discrete, "no host configured")
        port = int(options.get("port", 8060 if options.get("transport") == "http" else 0))
        if port <= 0:
            return ProbeResult(backend, False, discrete, "no port configured")
        error = connect(host, port, timeout_s)
        if error is not None:
            return ProbeResult(backend, False, discrete, f"{host}:{port} unreachable: {error}")
        return ProbeResult(backend, True, discrete, f"{host}:{port} reachable")

    return ProbeResult(backend, False, None, "not implemented yet (see roadmap)")
