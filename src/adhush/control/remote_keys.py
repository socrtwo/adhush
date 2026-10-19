"""Remote-control keys a control backend can press on the user's behalf.

Names are backend-neutral; the Sharp AQUOS mapping is the ``RCKY`` code from
the 2010–2011 LE-series operation manual (identical over the network and the
serial cable). A backend that cannot press keys raises ControlError.
"""

from __future__ import annotations

SHARP_RCKY: dict[str, int] = {
    "power": 12, "input": 36, "display": 13, "sleep": 24,
    "0": 0, "1": 1, "2": 2, "3": 3, "4": 4, "5": 5, "6": 6, "7": 7, "8": 8, "9": 9,
    "dot": 10, "ent": 11,
    "ch_up": 34, "ch_down": 35, "flashback": 30, "fav": 47,
    "vol_up": 33, "vol_down": 32, "mute": 31,
    "up": 41, "down": 42, "left": 43, "right": 44, "enter": 40,
    "menu": 38, "smart": 39, "return": 45, "exit": 46,
    "cc": 27, "audio": 49, "av_mode": 28, "view_mode": 29, "freeze": 54,
    "rew": 15, "play": 16, "ff": 17, "pause": 18, "stop": 20,
    "red": 50, "green": 51, "blue": 52, "yellow": 53, "netflix": 59,
}

KEY_NAMES: tuple[str, ...] = tuple(SHARP_RCKY)
