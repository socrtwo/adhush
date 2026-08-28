# AdHush

Detects television commercials and mutes them automatically.

AdHush watches a video and/or audio stream, runs several independent commercial
detectors over it, fuses their votes, and issues a mute command to the display
through whatever control path that display supports — infrared, HDMI-CEC,
RS-232, network API, or the host's own audio mixer.

**Status: Phases 1–4 implemented.** Working today: capture from HDMI-UVC,
screen grab, camera-at-the-screen (with screen auto-crop), microphone,
line-in, or file replay; the `black_frame`, `silence`, `loudness`,
`logo_absence` (calibrated via `adhush calibrate`), `scene_cut`, and
`fingerprint` detectors; vote fusion with hysteresis and the ad state machine
with fingerprint promotion; seven control backends (`rs232_sharp`, `ir_lirc`,
`ir_pigpio`, `cec`, `ir_blaster_net`, `network_ip`, `local_audio`) with
`adhush probe` to discover which can drive your set; a device-profile library
(Sharp reference, Samsung, LG, Sony BRAVIA, Vizio, Roku TV); a localhost
HTTP+SSE API with a dependency-free web front end (`platforms/web`); and the
full CLI. Repeat ads are recognized from their first seconds and muted for
their learned duration. The inline HDMI passthrough box is Phase 5 — see
`docs/roadmap.md`.

## Quick start

```sh
pip install -e .                      # numpy only; add [pi] for pyserial
cp config/adhush.example.toml config/adhush.toml   # then edit
adhush doctor                         # verify environment and config
adhush probe                          # discover which control paths work
adhush calibrate                      # learn the logo template (logo on screen)
adhush run                            # live detection + mute control
adhush replay clip.mp4 --labels labels.json        # offline scoring
adhush learn clip.mp4 --labels ads.json            # seed the ad fingerprint store
```

`replay` accepts any media file ffmpeg can decode, or an `.npz` fixture, and
reports precision/recall separately for mute-onset and unmute-onset against a
JSON label file (`[{"start_ts": 16.0, "duration_s": 30.0}, ...]`).

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

`rs232_sharp`, `network_ip` (TCP APIs like Sony Simple IP, HTTP APIs like
Roku ECP), `cec`, `ir_lirc`, `ir_pigpio` (raw NEC / extended NEC / Samsung /
Sharp / SIRC / RC-5 / raw-timing waveforms on a GPIO pin), `ir_blaster_net`
(Global Caché iTach, Broadlink), `local_audio` (host mute for screen/app
mode); `relay_hdmi` comes with the Phase 5 passthrough box. `adhush probe`
reports which backends can drive your set, in the profile's preference order.

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
