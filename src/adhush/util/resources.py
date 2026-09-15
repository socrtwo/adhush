"""Where the files that ship with AdHush live, in a checkout and in a binary.

The one-file binaries (ADR 0019) carry the web front end, the device
profiles and the example configs inside the executable; PyInstaller unpacks
them under ``sys._MEIPASS`` at start. A source checkout keeps them at the
repository root. ``bundled()`` returns whichever applies, so the rest of the
code never asks which it is running as.
"""

from __future__ import annotations

import sys
from pathlib import Path


def frozen() -> bool:
    """True inside a PyInstaller one-file binary."""
    return bool(getattr(sys, "frozen", False)) and hasattr(sys, "_MEIPASS")


def repo_root() -> Path:
    """The checkout root (src/adhush/util/resources.py -> three parents up)."""
    return Path(__file__).resolve().parents[3]


def bundled(relative: str) -> Path:
    """A shipped file or directory by its repository-relative path."""
    base = Path(str(getattr(sys, "_MEIPASS", ""))) if frozen() else repo_root()
    return base / relative


def self_command() -> list[str]:
    """How to start another AdHush process: the binary itself, or ``python -m adhush``."""
    if frozen():
        return [sys.executable]
    return [sys.executable, "-m", "adhush"]
