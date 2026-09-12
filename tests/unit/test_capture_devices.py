"""Device enumeration for `adhush doctor`, and hdmi_uvc's audio spec parsing."""

from __future__ import annotations

import subprocess
from pathlib import Path

from adhush.capture import hdmi_uvc
from adhush.capture.devices import (
    alsa_pcm_node,
    list_sound_cards,
    list_video_devices,
    parse_asound_cards,
)
from adhush.config import CaptureConfig

PROC_ASOUND_CARDS = """\
 0 [Headphones     ]: bcm2835_Headpho - bcm2835 Headphones
                      bcm2835 Headphones
 1 [MS2109         ]: USB-Audio - MS2109
                      MacroSilicon MS2109 at usb-0000:01:00.0-1.3, high speed
"""


class TestParseAsoundCards:
    def test_two_lines_per_card(self) -> None:
        cards = parse_asound_cards(PROC_ASOUND_CARDS)
        assert [(c.index, c.id) for c in cards] == [(0, "Headphones"), (1, "MS2109")]
        assert cards[1].driver == "USB-Audio - MS2109"
        assert cards[1].description.startswith("MacroSilicon MS2109")

    def test_usb_flag_finds_the_stick(self) -> None:
        cards = parse_asound_cards(PROC_ASOUND_CARDS)
        assert [c.is_usb for c in cards] == [False, True]

    def test_config_strings(self) -> None:
        stick = parse_asound_cards(PROC_ASOUND_CARDS)[1]
        assert stick.alsa_by_index() == "alsa:hw:1,0"
        assert stick.alsa_by_name() == "alsa:hw:CARD=MS2109,DEV=0"

    def test_trailing_card_without_description(self) -> None:
        cards = parse_asound_cards(" 2 [Loopback       ]: Loopback - Loopback\n")
        assert cards == [cards[0]] and cards[0].index == 2 and cards[0].description == ""

    def test_missing_file_is_empty(self, tmp_path: Path) -> None:
        assert list_sound_cards(tmp_path / "nope") == []


class TestNodes:
    def test_video_devices_sorted_numerically(self, tmp_path: Path) -> None:
        for name in ("video10", "video0", "video2"):
            (tmp_path / name).touch()
        assert [p.name for p in list_video_devices(tmp_path)] == ["video0", "video2", "video10"]

    def test_hw_spec_maps_to_capture_pcm(self, tmp_path: Path) -> None:
        assert alsa_pcm_node("alsa:hw:1,0", tmp_path) == tmp_path / "snd" / "pcmC1D0c"
        assert alsa_pcm_node("hw:2", tmp_path) == tmp_path / "snd" / "pcmC2D0c"
        assert alsa_pcm_node("alsa:plughw:1,1", tmp_path) == tmp_path / "snd" / "pcmC1D1c"

    def test_named_and_default_specs_are_left_to_ffmpeg(self, tmp_path: Path) -> None:
        assert alsa_pcm_node("default", tmp_path) is None
        assert alsa_pcm_node("alsa:hw:CARD=MS2109,DEV=0", tmp_path) is None
        assert alsa_pcm_node("pulse:tv.monitor", tmp_path) is None


class TestHdmiUvcAudioSpec:
    """The passthrough example config says alsa:hw:1,0; ffmpeg must see hw:1,0."""

    def test_alsa_prefix_is_stripped_for_ffmpeg(self, monkeypatch) -> None:
        argvs: list[list[str]] = []

        class FakeProc:
            stdout = None

            def terminate(self) -> None: ...

            def wait(self) -> None: ...

        def fake_popen(argv, **_: object) -> FakeProc:
            argvs.append(list(argv))
            return FakeProc()

        monkeypatch.setattr(hdmi_uvc.shutil, "which", lambda _: "/usr/bin/ffmpeg")
        monkeypatch.setattr(subprocess, "Popen", fake_popen)
        src = hdmi_uvc.HdmiUvcSource(CaptureConfig(backend="hdmi_uvc", audio_device="alsa:hw:1,0"))
        src.open()
        src.close()
        audio = argvs[1]
        assert audio[audio.index("-f") + 1] == "alsa"
        assert audio[audio.index("-i") + 1] == "hw:1,0"
