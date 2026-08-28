# Phase 5 hardware: the inline HDMI passthrough box

A Raspberry Pi 4 unit that sits between any HDMI source (cable box, antenna
tuner, streaming stick) and any TV or amplifier, watches the program, and
physically interrupts the audio path during commercials. No TV cooperation —
no IR codes, no serial port, no network API — and a mute that cannot
desynchronize because the box *is* the audio path.

## Signal topology

    source ──HDMI──▶ splitter ──HDMI──────────────────────▶ TV (video + HDMI audio muted at the TV? no —
                        │                                     feed the TV video-only or keep TV volume at 0)
                        ├─HDMI──▶ audio extractor ─analog/optical─▶ relay ──▶ amp / soundbar / TV line-in
                        └─HDMI──▶ UVC capture dongle ──USB 3.0──▶ Pi

    Pi GPIO 23 ──▶ transistor ──▶ relay coil (module board)

The room hears audio through the extractor→relay path, so opening the relay
silences the room regardless of what the TV thinks its volume is. Run the
TV's own speakers at zero (or use a TV with external audio) so the relay path
is the only audible one.

## Parts

| Part | Notes |
|---|---|
| Raspberry Pi 4, 2 GB+ | the reference budget (docs/hardware-pi4.md) |
| 1×2 HDMI splitter | powered; must pass the source's resolution |
| HDMI audio extractor | HDMI in → HDMI out + analog (3.5 mm/RCA) or TOSLINK |
| USB 3.0 HDMI-to-UVC dongle | 720p30 capture is enough |
| Relay module, 3.3 V logic | opto-isolated board; **audio through the NC (normally closed) contacts** |
| Analog: any relay works | Optical: use a relay-driven TOSLINK switch instead of bare contacts |

Content protection caveat: the splitter/extractor path only works where the
HDMI link is capturable. AdHush does not defeat content protection
(CLAUDE.md); for protected sources this box cannot see video — fall back to
the audio-only detector set from the extractor's second output, or don't use
the box.

## Wiring the relay for fail-unmuted

Wire the audio through the relay's **normally closed** contacts and drive the
coil from GPIO 23 (via the module's transistor input). De-energized = audio
passes. `relay_hdmi` energizes the coil only while muting, so a crash, a Pi
power loss, or service shutdown always leaves the room *with* audio. A stuck
mute is the failure AdHush must never produce.

Both stereo channels switch together: use a 2-channel module (one channel per
audio leg) driven from the same GPIO, and keep audio ground common (do not
switch ground).

## Software

```sh
sudo scripts/install-pi.sh              # deps, pigpiod, venv, systemd unit
cp config/adhush-passthrough.example.toml config/adhush.toml
adhush probe                            # should report relay_hdmi usable
adhush run
```

The box profile is `config/profiles/passthrough-box.example.toml`
(`control.backend = "relay_hdmi"`, capture `hdmi_uvc`). Enable `[ipc]` and
open `platforms/web/index.html` from a phone for status, manual override, and
the is-an-ad / not-an-ad feedback buttons.

## Latency note

The relay cuts audio the instant fusion decides; the decision itself trails
the ad boundary by the usual ~1–2 s (dwell plus detector windows). There is
no audio delay line in the box: inserting one (buffering the extractor's
output on the Pi and re-emitting it a few seconds late so mutes can be
retroactive) would let the box mute *before* the boundary reaches the room,
at the cost of lip-sync against the on-screen video. Deliberately out of
scope until someone wants a listening-delay mode; the hooks (audio via the
Pi) exist.
