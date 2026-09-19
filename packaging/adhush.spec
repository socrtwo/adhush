# PyInstaller spec for the one-file AdHush core binary (ADR 0019).
# Built by .github/workflows/release.yml on every platform runner:
#   pyinstaller packaging/adhush.spec
# The binary carries the web front end and the device profiles as data, so it
# runs from any directory; ffmpeg is the one runtime dependency it expects on
# PATH for live capture (doctor says so when it is missing).
import sys

from PyInstaller.utils.hooks import collect_submodules

block_cipher = None

a = Analysis(
    ["../src/adhush/__main__.py"],
    pathex=["../src"],
    binaries=[],
    datas=[
        ("../platforms/web", "platforms/web"),
        ("../config/profiles", "config/profiles"),
        ("../config/adhush.example.toml", "config"),
        ("../config/adhush-listener.example.toml", "config"),
    ],
    hiddenimports=collect_submodules("adhush") + ["serial", "serial.tools.list_ports"],
    hookspath=[],
    runtime_hooks=[],
    excludes=["pytest", "mypy", "ruff", "matplotlib", "scipy", "PIL"],
    noarchive=False,
)
pyz = PYZ(a.pure, a.zipped_data, cipher=block_cipher)
exe = EXE(
    pyz,
    a.scripts,
    a.binaries,
    a.datas,
    [],
    name="adhush",
    debug=False,
    strip=False,
    upx=False,
    console=True,
    disable_windowed_traceback=False,
)
if sys.platform == "win32":
    # A second, windowless build for the logon task: no console flashes up at login.
    exew = EXE(
        pyz,
        a.scripts,
        a.binaries,
        a.datas,
        [],
        name="adhushw",
        debug=False,
        strip=False,
        upx=False,
        console=False,
        disable_windowed_traceback=False,
    )
