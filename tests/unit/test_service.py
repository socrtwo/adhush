"""Start-at-login installers: generated text and platform dispatch, no real tools."""

from __future__ import annotations

import plistlib
import subprocess
from pathlib import Path

import pytest

from adhush.service import (
    ServiceError,
    core_command,
    install,
    launchd_plist,
    schtasks_create,
    status,
    systemd_unit,
    uninstall,
)


class FakeRun:
    def __init__(self, rc: int = 0) -> None:
        self.calls: list[list[str]] = []
        self.rc = rc

    def __call__(self, argv):  # type: ignore[no-untyped-def]
        self.calls.append(list(argv))
        return subprocess.CompletedProcess(list(argv), self.rc, b"active\n", b"")


def test_core_command_uses_this_interpreter_and_the_overlay_flag() -> None:
    cmd = core_command(Path("/x/config/adhush.toml"), overlay=True, python="/opt/py/bin/python3")
    assert cmd[:4] == ["/opt/py/bin/python3", "-m", "adhush", "run"]
    assert cmd[-1] == "--overlay"
    assert core_command(Path("c.toml"), overlay=False, python="p")[-1] == "--no-overlay"


def test_systemd_unit_text() -> None:
    unit = systemd_unit(["/usr/bin/python3", "-m", "adhush", "run"], Path("/home/u/adhush"))
    assert "ExecStart=/usr/bin/python3 -m adhush run" in unit
    assert "WorkingDirectory=/home/u/adhush" in unit
    assert "WantedBy=default.target" in unit
    assert "Restart=on-failure" in unit


def test_launchd_plist_is_valid_and_keeps_alive() -> None:
    raw = launchd_plist(["/usr/bin/python3", "-m", "adhush", "run"], Path("/Users/u/adhush"))
    data = plistlib.loads(raw)
    assert data["Label"] == "io.adhush.core"
    assert data["ProgramArguments"][1:] == ["-m", "adhush", "run"]
    assert data["RunAtLoad"] is True and data["KeepAlive"] == {"SuccessfulExit": False}


def test_schtasks_quotes_paths_with_spaces() -> None:
    argv = schtasks_create([r"C:\Program Files\Py\pythonw.exe", "-m", "adhush", "run"])
    assert argv[:3] == ["schtasks", "/Create", "/SC"]
    tr = argv[argv.index("/TR") + 1]
    assert tr.startswith('"C:\\Program Files') and tr.endswith("run")


def test_linux_install_writes_unit_and_enables(tmp_path: Path) -> None:
    run = FakeRun()
    config = tmp_path / "repo" / "config" / "adhush.toml"
    config.parent.mkdir(parents=True)
    config.write_text("")
    message = install(config, overlay=True, platform="linux", home=tmp_path, run=run, python="py")
    unit = tmp_path / ".config" / "systemd" / "user" / "adhush.service"
    assert unit.is_file()
    assert f"WorkingDirectory={tmp_path / 'repo'}" in unit.read_text()
    assert ["systemctl", "--user", "enable", "--now", "adhush"] in run.calls
    assert "enable-linger" in message
    assert "active" in status(platform="linux", home=tmp_path, run=run)
    uninstall(platform="linux", home=tmp_path, run=run)
    assert not unit.exists()


def test_darwin_install_writes_agent(tmp_path: Path) -> None:
    run = FakeRun()
    config = tmp_path / "adhush.toml"
    config.write_text("")
    install(config, overlay=False, platform="darwin", home=tmp_path, run=run, python="py")
    plist = tmp_path / "Library" / "LaunchAgents" / "io.adhush.core.plist"
    assert plist.is_file()
    assert any(call[:2] == ["launchctl", "bootstrap"] for call in run.calls)


def test_windows_install_creates_logon_task(tmp_path: Path) -> None:
    run = FakeRun()
    config = tmp_path / "adhush.toml"
    config.write_text("")
    install(config, overlay=True, platform="win32", home=tmp_path, run=run, python="py")
    assert run.calls[0][:2] == ["schtasks", "/Create"]
    assert ["schtasks", "/Run", "/TN", "AdHush"] in run.calls


def test_tool_failure_is_a_service_error(tmp_path: Path) -> None:
    config = tmp_path / "adhush.toml"
    config.write_text("")
    with pytest.raises(ServiceError, match="systemctl"):
        install(config, platform="linux", home=tmp_path, run=FakeRun(rc=1), python="py")
    with pytest.raises(ServiceError, match="platform"):
        install(config, platform="plan9", home=tmp_path, run=FakeRun(), python="py")
