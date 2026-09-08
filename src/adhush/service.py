"""Keep the core running: install it as a start-at-login service on this machine.

Pure text generators for each platform's native mechanism (a systemd user
unit, a launchd agent, a Task Scheduler logon task) plus thin installers that
write the file and call the platform tool. The generators are the tested
part; the installers take an injectable ``run`` so tests never touch the
real systemctl/launchctl/schtasks.

This is the desktop counterpart of ``scripts/install-pi.sh``'s system unit:
that one runs headless at boot; these run in the user's session so the
overlay has a display to draw on.
"""

from __future__ import annotations

import getpass
import os
import plistlib
import subprocess
import sys
from collections.abc import Callable, Sequence
from pathlib import Path

SERVICE_NAME = "adhush"
LAUNCHD_LABEL = "io.adhush.core"
SCHTASKS_NAME = "AdHush"

Run = Callable[[Sequence[str]], subprocess.CompletedProcess[bytes]]


class ServiceError(RuntimeError):
    """Installation could not be completed on this platform."""


def core_command(config: Path, *, overlay: bool, python: str | None = None) -> list[str]:
    """The argv the service runs. Windows gets pythonw so no console window appears."""
    exe = python or sys.executable
    if sys.platform == "win32" and exe.lower().endswith("python.exe"):
        exe = exe[: -len("python.exe")] + "pythonw.exe"
    return [exe, "-m", "adhush", "run", "--config", str(config),
            "--overlay" if overlay else "--no-overlay"]


def _quote(arg: str) -> str:
    return f'"{arg}"' if " " in arg else arg


# -- generators ------------------------------------------------------------------


def systemd_unit(command: Sequence[str], workdir: Path) -> str:
    return "\n".join(
        [
            "[Unit]",
            "Description=AdHush commercial mute (user session)",
            "After=graphical-session.target network-online.target",
            "",
            "[Service]",
            "Type=simple",
            f"WorkingDirectory={workdir}",
            f"ExecStart={' '.join(_quote(a) for a in command)}",
            "Restart=on-failure",
            "RestartSec=5",
            "# The overlay needs the session's display; systemd imports it for",
            "# graphical sessions. Headless boxes use scripts/install-pi.sh instead.",
            "",
            "[Install]",
            "WantedBy=default.target",
            "",
        ]
    )


def launchd_plist(command: Sequence[str], workdir: Path, label: str = LAUNCHD_LABEL) -> bytes:
    payload = {
        "Label": label,
        "ProgramArguments": list(command),
        "WorkingDirectory": str(workdir),
        "RunAtLoad": True,
        "KeepAlive": {"SuccessfulExit": False},
        "ProcessType": "Interactive",
        "StandardOutPath": str(Path.home() / "Library" / "Logs" / "adhush.log"),
        "StandardErrorPath": str(Path.home() / "Library" / "Logs" / "adhush.log"),
    }
    return plistlib.dumps(payload)


def schtasks_create(command: Sequence[str], task: str = SCHTASKS_NAME) -> list[str]:
    tr = " ".join(_quote(a) for a in command)
    return ["schtasks", "/Create", "/SC", "ONLOGON", "/TN", task, "/TR", tr,
            "/RL", "LIMITED", "/F"]


# -- installers ------------------------------------------------------------------


def _run(argv: Sequence[str]) -> subprocess.CompletedProcess[bytes]:
    return subprocess.run(list(argv), capture_output=True, check=False)


def _check(result: subprocess.CompletedProcess[bytes], what: str) -> None:
    if result.returncode != 0:
        detail = (result.stderr or result.stdout or b"").decode(errors="replace").strip()
        raise ServiceError(f"{what} failed: {detail or result.returncode}")


def _unit_path(home: Path) -> Path:
    return home / ".config" / "systemd" / "user" / f"{SERVICE_NAME}.service"


def _plist_path(home: Path) -> Path:
    return home / "Library" / "LaunchAgents" / f"{LAUNCHD_LABEL}.plist"


def install(
    config: Path,
    *,
    overlay: bool = True,
    platform: str = sys.platform,
    home: Path | None = None,
    run: Run = _run,
    python: str | None = None,
) -> str:
    """Install and start the login service. Returns a human-readable summary."""
    home = home or Path.home()
    workdir = config.parent.parent if config.parent.name == "config" else config.parent
    command = core_command(config, overlay=overlay, python=python)

    if platform.startswith("linux"):
        path = _unit_path(home)
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_text(systemd_unit(command, workdir))
        _check(run(["systemctl", "--user", "daemon-reload"]), "systemctl daemon-reload")
        _check(run(["systemctl", "--user", "enable", "--now", SERVICE_NAME]), "systemctl enable")
        return (
            f"installed {path}; running now and at every login.\n"
            f"To also run while logged out: loginctl enable-linger {getpass.getuser()}"
        )
    if platform == "darwin":
        path = _plist_path(home)
        path.parent.mkdir(parents=True, exist_ok=True)
        path.write_bytes(launchd_plist(command, workdir))
        domain = f"gui/{os.getuid()}"
        result = run(["launchctl", "bootstrap", domain, str(path)])
        if result.returncode != 0:  # already loaded, or an older launchctl
            run(["launchctl", "bootout", domain, str(path)])
            _check(run(["launchctl", "bootstrap", domain, str(path)]), "launchctl bootstrap")
        return f"installed {path}; running now and at every login."
    if platform == "win32":
        _check(run(schtasks_create(command)), "schtasks /Create")
        run(["schtasks", "/Run", "/TN", SCHTASKS_NAME])
        return f"installed Task Scheduler task '{SCHTASKS_NAME}'; running now and at every logon."
    raise ServiceError(f"no service installer for platform {platform!r}")


def uninstall(
    *, platform: str = sys.platform, home: Path | None = None, run: Run = _run
) -> str:
    home = home or Path.home()
    if platform.startswith("linux"):
        run(["systemctl", "--user", "disable", "--now", SERVICE_NAME])
        path = _unit_path(home)
        path.unlink(missing_ok=True)
        run(["systemctl", "--user", "daemon-reload"])
        return f"removed {path}"
    if platform == "darwin":
        path = _plist_path(home)
        run(["launchctl", "bootout", f"gui/{os.getuid()}", str(path)])
        path.unlink(missing_ok=True)
        return f"removed {path}"
    if platform == "win32":
        _check(run(["schtasks", "/Delete", "/TN", SCHTASKS_NAME, "/F"]), "schtasks /Delete")
        return f"removed task '{SCHTASKS_NAME}'"
    raise ServiceError(f"no service installer for platform {platform!r}")


def status(*, platform: str = sys.platform, home: Path | None = None, run: Run = _run) -> str:
    home = home or Path.home()
    if platform.startswith("linux"):
        result = run(["systemctl", "--user", "is-active", SERVICE_NAME])
        state = (result.stdout or b"unknown").decode(errors="replace").strip()
        return f"{SERVICE_NAME}: {state} ({_unit_path(home)})"
    if platform == "darwin":
        installed = _plist_path(home).exists()
        return f"{LAUNCHD_LABEL}: {'installed' if installed else 'not installed'}"
    if platform == "win32":
        result = run(["schtasks", "/Query", "/TN", SCHTASKS_NAME])
        return f"{SCHTASKS_NAME}: {'installed' if result.returncode == 0 else 'not installed'}"
    raise ServiceError(f"no service installer for platform {platform!r}")
