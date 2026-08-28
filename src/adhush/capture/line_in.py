"""Analog/optical audio capture from TV audio-out or receiver tap.

Electrically it's just another audio input, so this reuses the microphone
source's ffmpeg plumbing; what differs is intent and configuration — a wired
tap gives a clean, room-noise-free signal, so profiles can run tighter
silence thresholds than a microphone in the room would allow. The device
string names the capture card input (e.g. ``alsa:hw:1,0``).
"""

from __future__ import annotations

from adhush.capture.microphone import MicrophoneSource


class LineInSource(MicrophoneSource):
    """Wired audio tap; identical stream contract to MicrophoneSource."""
