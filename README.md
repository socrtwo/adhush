# AdHush

Turns the television down during the commercials, and back up when the
show returns. Nothing is uploaded, nothing is recorded, and no capture
path is circumvented: it watches and listens the way a person in the room
does, decides, and presses the volume for you.

**Status 0.28.3: nineteen roadmap phases in, running in real living
rooms.** Two implementations share one design, and `docs/adr/` records
every decision behind both:

- **The Python core** (Windows, macOS, Linux, ChromeOS, Raspberry Pi)
  captures from an HDMI splitter and USB dongle, a transport stream from an
  HDHomeRun or DVB tuner, the screen, a camera at the set, a microphone or
  a line-in. Eighteen detectors vote — black frames and silence, loudness
  and the crest factor, the channel bug, scene cuts, aspect change, the
  rating box, ad-unit lengths, captured cutscenes, the stereo switch, the
  ATSC watermark, fingerprints of ads seen before, the break clock, the
  channel's break jingle and its re-cuts, the segment stinger, an XMLTV
  schedule, SCTE-35 cues and a shared crowd feed — and the set is driven over RS-232, IP, HDMI-CEC, infrared,
  a network blaster, the host's own mixer or a relay.
- **The Android app** is a phone by the TV, no cables: the same core
  ported to Kotlin with ten methods, among them offline speech and
  on-screen captions matched against learned scripts, a cloud and an
  on-device AI judge, the channel bug or a news ticker through the camera,
  and a jingle it learns by itself. It ducks Sharp, Samsung, LG, Sony,
  Roku TV, Vizio, Android TV and Google TV, Hisense, Philips and any DLNA
  set; a three-minute wizard finds the set, measures the room and the
  camera, and suggests the settings.

## Get it

Every release on the [releases page](https://github.com/socrtwo/adhush/releases)
carries a build for each platform. Pick yours:

| You have | Download | Then |
|---|---|---|
| **An Android phone** | `adhush-<ver>-android-debug.apk` | sideload it, press the wizard; `platforms/android/README.md` |
| **An iPhone or iPad** | nothing to install: open the core's address in Safari, Share → *Add to Home Screen* | the page is a PWA; it needs a core running somewhere on the network |
| **Windows 10/11** | `adhush-<ver>-windows-x64.zip` | unzip; `adhush.exe init`, edit `config\adhush.toml`, `adhush.exe doctor`, `adhush.exe run`; `adhush.exe service install` starts it at logon (silently, via `adhushw.exe`) |
| **A Mac (Apple silicon)** | `adhush-<ver>-macos-arm64.zip` | unzip; `./adhush init` … `./adhush service install`. Intel Mac: the wheel |
| **Linux (x64 or arm64)** | `adhush-<ver>-linux-x64.tar.gz` / `-linux-arm64.tar.gz` | untar; the same five commands. glibc 2.35+ (Ubuntu 22.04, Debian bookworm) |
| **A Raspberry Pi 4 / 5** | `adhush-<ver>-raspberry-pi.zip` | the arm64 binary, the listener config and the illustrated PDF guides in one folder, with a README |
| **A Chromebook** | the Linux binary for its chip, in the Linux container | open the served page in Chrome; its Mini window floats above everything |
| **Python 3.11+ anywhere** | `adhush-<ver>-py3-none-any.whl` | `pip install "adhush-<ver>-py3-none-any.whl[pi]"` |

The one thing every desktop and Pi needs besides the binary is **ffmpeg** on
PATH for live capture (`apt install ffmpeg`, `brew install ffmpeg`,
`winget install ffmpeg`); `adhush doctor` says when it is missing. The
binaries are unsigned: Windows SmartScreen and macOS Gatekeeper ask once.
Which platform gets what is in `docs/release.md`.

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
pip install -e .                                   # numpy only; add [pi] for pyserial
cp config/adhush.example.toml config/adhush.toml   # then edit
adhush calibrate                                   # learn the logo template (logo on screen)
adhush replay clip.mp4 --labels labels.json        # offline scoring
adhush learn clip.mp4 --labels ads.json            # seed the ad fingerprint store
adhush cutscene add --kind in --image brb.png      # a "we'll be right back" card the channel shows
adhush crowd serve                                 # the opt-in shared feed of break times, for the house
make lint type test                                # ruff, mypy --strict, pytest
```

`replay` accepts any media file ffmpeg can decode, or an `.npz` fixture, and
reports precision and recall separately for mute-onset and unmute-onset
against a JSON label file (`[{"start_ts": 16.0, "duration_s": 30.0}, ...]`).
A late unmute is the worse failure, and the tests hold it to a tighter
tolerance than a late mute.

## Keep it running, see it at a glance

`adhush run` with `[ipc] enabled` serves the web app and opens the
always-on-top mini window: ✗ ✓ ▶ ■, timed ducks of 30 s to 5 min with
`+30 s`, a countdown, and a full TV remote. Phones and tablets open
`http://<core-host>:8675/` and add it to the home screen.

## How it decides

Every detector is a plugin that returns a confidence vote with a
machine-readable reason, never touches a controller, and never reads
another detector. Fusion weighs the votes over the mass of the detectors
that are *voting*: a detector with nothing to say stays inert and neither
adds evidence nor dilutes it. No single default-weight detector can mute
the set alone; two that agree can. A few whose evidence identifies a break
outright — a learned jingle, a captured cutscene, a splice cue, a
fingerprint match, other devices in the same break — may act alone. A
state machine adds dwell and hysteresis, a ceiling learned from how long
this channel's breaks run, and *positive programme evidence* (the bug back,
the closing sting, the rating box, a title card, an in-network cue) that
ends a hold early. `docs/architecture.md` and
`docs/detection-strategies.md` have the whole of it.

### The detectors (Python core)

| Detector | Signal |
|---|---|
| `black_frame` | Black — or any uniform-colour — frame runs at pod boundaries |
| `silence` | Audio gaps at pod boundaries |
| `loudness` | Short-term LUFS above a rolling programme baseline; a crest-factor drop (a spot compressed flat) adds half a vote |
| `logo_absence` | The network bug vanishes from its corner; its return is programme evidence |
| `scene_cut` | Shot-change rate spike |
| `fingerprint` | Perceptual video and audio hashes of ads seen before; a match mutes for the learned duration |
| `aspect_change` | The active picture's shape leaves the programme's: pillarboxed or letterboxed spots (HDMI and screen paths) |
| `rating_bug` | The parental-rating box ("TV-14") flashed after every break: the show is back |
| `ad_units` | Separators on the 15/30/45/60/90/120-second ad-unit grid hold a mute through the middle of a pod |
| `cutscene` | Captured intro/outro frames: "we'll be right back" ducks, the title card unmutes |
| `stereo_width` | The mix switches between mono and stereo (HDMI, line-in, transport stream) |
| `watermark` | The ATSC A/335 / DVB-TA video watermark lost when a local spot replaces the picture (experimental) |
| `clock` | The break clock: which minutes of the hour the breaks land on, and how long they run |
| `jingle` | The channel's own sting into and out of every break, learned from three breaks; re-cuts a few semitones or a little slower count as the same family; remembers the hours it plays |
| `stinger` | The whoosh or hit on the cut into a segment: a short noisy burst over the bed, then the level moves |
| `schedule` | XMLTV programme boundaries: a grace window after a start; ad-free channels never mute |
| `scte35` | In-band splice cues from a transport stream (`ts_stream`): authoritative where the feed still carries them |
| `crowd` | Other AdHush devices on the same channel reporting a break (opt-in, hashed, `adhush crowd serve`) |
| `caption_gap` | Closed-caption stream discontinuity — planned, not yet built |

Everything new since 0.21.0 is inert until it has evidence, so the default
configuration enables it all without weakening the rest.

### The methods (Android app)

| # | Method | Uses |
|---|---|---|
| 1 | Quiet gaps | microphone; gaps on the ad-unit grid hold a duck through a pod |
| 2 | Loudness jumps | microphone, with the crest factor |
| 3 | Remembered breaks | audio fingerprints of taught breaks |
| 4 | Channel bug, or the news ticker | camera, whole TV in view; the rating box brings the show back |
| 5 | Spoken words | microphone and an offline recogniser, matched against learned scripts |
| 6 | On-screen captions | camera, the caption band read as text |
| 7 | Ask Claude | the words, judged over the API (opt-in, paid) |
| 8 | Local AI | the words, judged by a Qwen model on the phone in three sizes |
| 9 | Break clock | the minute of the hour and how long breaks run |
| 10 | Break jingle | the channel's sting and its re-cuts, learned by itself, with the hours it plays; the segment stinger rides on Loudness |

The phone also learns a channel's commercials from its live stream before
the first evening at the TV, reads a streaming player's "AD" badge, moves
its memory between phones, and carries a full remote.

## Control paths

**Python core:** `rs232_sharp`, `network_ip` (TCP APIs such as Sony
Simple IP, HTTP APIs such as Roku ECP), `cec`, `ir_lirc`, `ir_pigpio` (NEC,
extended NEC, Samsung, Sharp, SIRC, RC-5 and raw timings on a GPIO pin),
`ir_blaster_net` (Global Caché iTach, Broadlink), `local_audio` (the host's
mute, for screen and app mode) and `relay_hdmi` (the passthrough box's
relay, wired to fail unmuted). Device profiles under `config/profiles/`
(Sharp, Samsung, LG, Sony, Vizio, Roku TV, generic) are data, not code;
`adhush probe` reports which backends can drive your set. Discrete
mute-on and mute-off are preferred to a toggle, and ducking to a level is
preferred to muting, so the microphone keeps hearing the set.

**Android app:** the wizard's *Find my TV* asks every set on the Wi-Fi
who it is and proves each way in until one works:

| Set | How | Volume |
|---|---|---|
| Sharp | its control port, a serial cable, or infrared | exact |
| Sony Bravia | JSON-RPC with the pre-shared key from its menu | exact |
| LG webOS | allow AdHush on the set once | exact |
| Philips JointSpace | version 1 directly; version 6 with a PIN once | exact |
| Hisense VIDAA | the set's own broker; four digits once on newer sets | exact |
| Android TV / Google TV | a six-character code once | exact, from the set's reports |
| Any DLNA set | UPnP rendering control, no pairing | exact |
| Samsung | allow AdHush on the set once | stepped keys |
| Roku TV | no pairing | stepped keys |
| Vizio SmartCast | a PIN once | stepped keys |
| Anything with a remote | the phone's infrared blaster: Sharp, Samsung, LG/Vizio, Sony, Philips, Panasonic, Toshiba codes | stepped keys |

## Capture paths

- **HDMI passthrough** — a splitter or extractor feeding a USB UVC dongle.
- **Transport stream** — an HDHomeRun or DVB tuner over HTTP or UDP, with
  its SCTE-35 cues parsed in-process.
- **Camera and microphone** — a device camera pointed at the screen, no
  cabling; the phone's way.
- **Audio only** — a microphone or line-in; a reduced but useful set.
- **Screen capture** — for streaming apps on desktop, ChromeOS and the web.

## Design goals

1. **Detector plurality.** No single heuristic is reliable. Every detector
   is a plugin returning a confidence vote; fusion decides.
2. **Device breadth.** Control backends and device profiles are data, not
   code branches. A new make or model is a profile file, or a driver the
   wizard proves live.
3. **Small-target friendly.** The reference deployment is a Raspberry Pi 4
   with 2 GB of memory. Everything in the core loop is budgeted against it.
4. **Fail quiet, not loud.** An uncertain detector must never leave the set
   muted through the programme. Recovery is aggressive, a late unmute is the
   worse failure, and the remote always wins.
5. **Nothing leaves the house.** No audio or video is uploaded by default.
   The cloud judge sends text, only while its switch is on; the crowd feed
   sends a hashed channel and timestamps, only when opted in.

## Documentation

- `docs/architecture.md`, `docs/detection-strategies.md` — how the pieces fit.
- `docs/release.md` — the platform matrix and what each build can do.
- `docs/roadmap.md` — the eighteen phases and what is left; `docs/ideas-backlog.md` — what the wider world does that AdHush does not yet.
- `docs/adr/` — twenty-six architecture decision records, one per idea.
- `platforms/android/README.md` — the phone app, screen by screen.
- `docs/hardware-pi4.md`, `docs/hardware-passthrough-box.md` — the Pi and the passthrough box.
- `docs/build-guide-beginner.md`, `docs/build-guide-tv-listener.md`, `docs/build-guide-nespi4.md`, `docs/build-guide-microcontrollers.md` — illustrated builds, from a shelf listener to a relay box.
- `docs/print/` — the same guides as printable PDFs, plus the local-AI and stream-learning guides for the phone.
- `docs/device-support.md` — contributing a profile for a set that is not listed.

## Contributing

`CLAUDE.md` is the working agreement: implement in roadmap order, keep a
module inside its docstring, give every detector a labelled replay test,
and report mute-onset and unmute-onset separately. `make lint type test`
runs ruff, strict mypy and pytest; the Android core's tests run with
`./gradlew :core:test` under `platforms/android`, and CI runs both on
every push.

## License

MIT. See `LICENSE`.
