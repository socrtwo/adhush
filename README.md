# AdHush

Detects television commercials and mutes them automatically.

AdHush watches a video and/or audio stream, runs several independent commercial
detectors over it, fuses their votes, and issues a mute command to the display
through whatever control path that display supports — infrared, HDMI-CEC,
RS-232, network API, or the host's own audio mixer.

**Status: scaffold.** This repository currently contains structure, interfaces,
documentation, and configuration schemas only. No detection or control logic is
implemented yet.

## Design goals

1. **Detector plurality.** No single heuristic is reliable. Every detector is a
   plugin returning a confidence vote; fusion decides.
2. **Device breadth.** Control backends and device profiles are data, not code
   branches. Adding a new make/model should mean adding a profile file.
3. **Small-target friendly.** The reference deployment is a Raspberry Pi 4 with
   2 GB RAM. Everything in the core loop is budgeted against that.
4. **Fail quiet, not loud.** An uncertain detector must never leave the set
   muted through program content. Recovery is aggressive.

## Detectors

| Detector | Signal |
|---|---|
| `logo_absence` | Network bug vanishes from a configured ROI (typically lower right) |
| `loudness` | Short-term LUFS jumps above rolling program baseline |
| `black_frame` | Black/near-black runs at pod boundaries |
| `silence` | Audio gaps at pod boundaries |
| `scene_cut` | Shot-change rate spike |
| `aspect_change` | Letterbox/pillarbox transition |
| `caption_gap` | Closed-caption stream discontinuity |
| `fingerprint` | Perceptual video + audio hash match against previously seen ads |

The fingerprint path is what makes repeat ads instant: the first seconds of an
ad are hashed, stored with the observed duration, and matched on later airings.
A confirmed match mutes for the learned duration, snapped to the nearest
15/30/45/60-second slot, with detector-driven early unmute as a safety net.

## Control backends

`ir_lirc`, `ir_pigpio`, `ir_blaster_net`, `cec`, `rs232_sharp`, `network_ip`,
`local_audio`, `relay_hdmi`.

Discrete mute-on / mute-off is strongly preferred over toggle. Toggle-only
devices desynchronize; profiles must declare which they support so fusion can
compensate with state verification where available.

## Capture paths

- **HDMI passthrough** — splitter/extractor feeding a USB UVC capture dongle.
- **Camera + microphone** — device camera pointed at the screen, no cabling.
- **Audio only** — microphone or line-in; a reduced but useful detector set.
- **Screen capture** — for streaming apps on desktop, ChromeOS, and Web.

## Platform roadmap

Linux/Raspberry Pi (reference) -> Windows -> macOS -> Linux desktop ->
ChromeOS -> Android -> iOS -> Web. See `docs/roadmap.md`.

## Reference hardware

Raspberry Pi 4, 2 GB. See `docs/hardware-pi4.md` for the wiring, IR LED driver
circuit, and capture-dongle notes.

## License

MIT. See `LICENSE`.
