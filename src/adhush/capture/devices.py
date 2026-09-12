"""Enumerate the local capture devices that ``adhush doctor`` reports.

Reads what the kernel already exposes — ``/proc/asound/cards`` for ALSA sound
cards and ``/dev/video*`` for V4L2 nodes — so a first-time builder can find
the capture stick's card number without learning ``arecord``. Pure parsing
lives here and is injectable for tests; the CLI does the printing.
"""

from __future__ import annotations

import re
from dataclasses import dataclass
from pathlib import Path

_CARD_LINE = re.compile(r"^\s*(\d+)\s+\[(\S+)\s*\]:\s*(.*?)\s*$")
_HW_SPEC = re.compile(r"^(?:plug)?hw:(\d+)(?:,(\d+))?$")


@dataclass(frozen=True, slots=True)
class SoundCard:
    index: int
    id: str  # the short ALSA id, e.g. "MS2109"; stable across reboots
    driver: str  # e.g. "USB-Audio - MS2109"
    description: str  # the kernel's long name, e.g. "MacroSilicon MS2109 at usb-..."

    @property
    def is_usb(self) -> bool:
        return "usb" in self.driver.lower() or "usb" in self.description.lower()

    def alsa_by_index(self, device: int = 0) -> str:
        return f"alsa:hw:{self.index},{device}"

    def alsa_by_name(self, device: int = 0) -> str:
        """The card-id form, which survives USB devices re-enumerating."""
        return f"alsa:hw:CARD={self.id},DEV={device}"


def parse_asound_cards(text: str) -> list[SoundCard]:
    """Parse the two-lines-per-card layout of ``/proc/asound/cards``."""
    cards: list[SoundCard] = []
    pending: tuple[int, str, str] | None = None
    for line in text.splitlines():
        match = _CARD_LINE.match(line)
        if match:
            if pending is not None:
                cards.append(SoundCard(*pending, description=""))
            pending = (int(match.group(1)), match.group(2), match.group(3))
        elif pending is not None and line.strip():
            cards.append(SoundCard(*pending, description=line.strip()))
            pending = None
    if pending is not None:
        cards.append(SoundCard(*pending, description=""))
    return cards


def list_sound_cards(path: Path = Path("/proc/asound/cards")) -> list[SoundCard]:
    try:
        return parse_asound_cards(path.read_text())
    except OSError:
        return []  # not Linux, or no ALSA


def list_video_devices(dev: Path = Path("/dev")) -> list[Path]:
    return sorted(dev.glob("video*"), key=lambda p: (len(p.name), p.name))


def alsa_pcm_node(audio_device: str, dev: Path = Path("/dev")) -> Path | None:
    """The capture PCM node an ``alsa:hw:N[,M]`` spec opens, or None if unknown.

    Only numeric ``hw``/``plughw`` specs map to a node; ``default``, ``CARD=``
    names and other formats are left to ffmpeg to resolve.
    """
    spec = audio_device.removeprefix("alsa:")
    match = _HW_SPEC.match(spec)
    if match is None:
        return None
    card, device = int(match.group(1)), int(match.group(2) or 0)
    return dev / "snd" / f"pcmC{card}D{device}c"
