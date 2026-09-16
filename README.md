# AdHush

Detects television commercials and mutes them automatically.

AdHush watches a video and/or audio stream, runs several independent commercial
detectors over it, fuses their votes, and issues a mute command to the display
through whatever control path that display supports — infrared, HDMI-CEC,
RS-232, network API, or the host's own audio mixer.

**Status: twelve roadmap phases in, running in real living rooms.** The
Python core (Windows, macOS, Linux, ChromeOS, Raspberry Pi) captures from
HDMI-UVC, screen grab, a camera at the screen, a microphone or line-in, runs
seven detectors plus a learned break clock and the channel's break jingle, fuses their votes, and drives the
set over RS-232, IP, HDMI-CEC, infrared, a network blaster, the host's own
mixer or a relay. The Android app (a phone by the TV, no cables) has ten
methods including offline speech, on-device and cloud AI judges, the channel
bug or a news ticker through the camera, and can learn a channel's
commercials from its live stream before the first evening at the TV. Both
share the same ideas; `docs/adr/` records every decision.

## Get it

Every release on the [releases page](https://github.com/socrtwo/adhush/releases)
carries a build for each platform. Pick yours:

| You have | Download | Then |
|---|---|---|
| **An Android phone** | `adhush-<ver>-android-debug.apk` | sideload it; `platforms/android/README.md` |
| **An iPhone or iPad** | nothing to install: open the core's address in Safari, Share → *Add to Home Screen* | the page is a PWA; it needs a core running somewhere on the network |
| **Windows 10/11** | `adhush-<ver>-windows-x64.zip` | unzip; `adhush.exe init`, edit `config\adhush.toml`, `adhush.exe doctor`, `adhush.exe run`; `adhush.exe service install` starts it at logon (silently, via `adhushw.exe`) |
| **A Mac (Apple silicon)** | `adhush-<ver>-macos-arm64.zip` | unzip; `./adhush init` … `./adhush service install`. Intel Mac: the wheel |
| **Linux (x64 or arm64)** | `adhush-<ver>-linux-x64.tar.gz` / `-linux-arm64.tar.gz` | untar; the same five commands. glibc 2.35+ (Ubuntu 22.04, Debian bookworm) |
| **A Raspberry Pi 4 / 5** | `adhush-<ver>-raspberry-pi.zip` | the arm64 binary, the listener config and the illustrated PDF guides in one folder, with a README |
| **A Chromebook** | the Linux binary for its chip, in the Linux container | open the served page in Chrome; its Mini window floats above everything |
| **Python 3.11+ anywhere** | `adhush-<ver>-py3-none-any.whl` | `pip install adhush-<ver>-py3-none-any.whl[pi]` |

The one thing every desktop and Pi needs besides the binary is **ffmpeg** on
PATH for live capture (`apt install ffmpeg`, `brew install ffmpeg`,
`winget install ffmpeg`); `adhush doctor` says when it is missing. The
binaries are unsigned: Windows SmartScreen and macOS Gatekeeper ask once.

## Quick start

From a binary, in any folder you like:

```sh
adhush init            # writes config/adhush.toml and the profile library (--listener for the shelf build)
adhush doctor          # what is missing, if anything
adhush probe --active  # which control paths can drive your set (the sound should dip)
adhush run             # live detection + control; serves the web app at :8675
adhush service install # start at login, restart on failure
```

From a checkout:

```sh
pip install -e .                      # numpy only; add [pi] for pyserial
cp config/adhush.example.toml config/adhush.toml   # then edit
adhush calibrate                      # learn the logo template (logo on screen)
adhush replay clip.mp4 --labels labels.json        # offline scoring
adhush learn clip.mp4 --labels ads.json            # seed the ad fingerprint store
```

`replay` accepts any media file ffmpeg can decode, or an `.npz` fixture, and
reports precision/recall separately for mute-onset and unmute-onset against a
JSON label file (`[{"start_ts": 16.0, "duration_s": 30.0}, ...]`).

## Keep it running, see it at a glance

`adhush run` with `[ipc] enabled` serves the web app and opens the
always-on-top mini window (✗ ✓ ▶ ■, timed ducks of 30 s to 5 min with
`+30 s`, and a full TV remote).
Phones and tablets open `http://<core-host>:8675/` and add it to the home
screen. Which platform gets what is in `docs/release.md`.

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
| `loudness` | Short-term LUFS jumps above rolling program baseline; a crest-factor drop (a spot compressed flat) adds half a vote |
| `black_frame` | Black/near-black runs at pod boundaries |
| `silence` | Audio gaps at pod boundaries |
| `scene_cut` | Shot-change rate spike |
| `aspect_change` | Letterbox/pillarbox transition: the active picture's shape leaves the programme's (HDMI and screen paths) |
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
mode), `relay_hdmi` (the passthrough box's GPIO relay physically opening the
audio path — wired to fail unmuted). `adhush probe` reports which backends
can drive your set, in the profile's preference order.

Discrete mute-on / mute-off is strongly preferred over toggle. Toggle-only
devices desynchronize; profiles must declare which they support so fusion can
compensate with state verification where available.

## Capture paths

- **HDMI passthrough** — splitter/extractor feeding a USB UVC capture dongle.
- **Camera + microphone** — device camera pointed at the screen, no cabling.
- **Audio only** — microphone or line-in; a reduced but useful detector set.
- **Screen capture** — for streaming apps on desktop, ChromeOS, and Web.

## Android app

`platforms/android` is the phone by the TV: the detector core ported to
Kotlin, ten methods (quiet gaps, loudness, remembered breaks, the channel
bug or a news ticker through the camera, offline speech and on-screen
captions matched against learned scripts, Claude and an on-device language
model as judges, a learned break clock that also learns how long breaks
run, and the channel's break jingle), ducking a Sharp over Wi-Fi, a
USB serial cable or infrared. It teaches itself from a channel's live
stream, moves its memory between phones, carries a full remote, and a
three-minute set-up wizard measures the room and the camera and suggests
the settings. See
`platforms/android/README.md` and the guides in `docs/print/`.

## Platform roadmap

All eight platforms ship from one tag (ADR 0019). What is left is under
"Beyond the roadmap" in `docs/roadmap.md`.

## Reference hardware

Raspberry Pi 4, 2 GB. See `docs/hardware-pi4.md` for the wiring, IR LED driver
circuit, and capture-dongle notes.

## Building the passthrough box

- `docs/hardware-passthrough-box.md` — topology, parts, fail-unmuted wiring.
- `docs/build-guide-tv-listener.md` — the no-hardware version: a Pi with a
  webcam and microphone on a shelf, turning a Sharp down over Wi-Fi (0.15.0)
- `docs/build-guide-beginner.md` — step-by-step build a 12-year-old can
  follow: no soldering, low voltage only, ~$120–160 in parts.
- `docs/build-guide-microcontrollers.md` — using an Arduino, ESP32/ESP8266,
  or Pico as the mute actuator (with firmware sketches and matching config),
  plus the minimum requirements for the box.

## License

MIT. See `LICENSE`.
