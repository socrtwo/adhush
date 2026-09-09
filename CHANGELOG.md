# Changelog

All notable changes to this project are documented here.
Format follows Keep a Changelog; versioning follows SemVer.

## [0.7.3] - 2026-09-09
### Added
- Android **room survey**: a 10-minute recording of what the detectors
  measure (dBFS, flatness, short-term LUFS, silence floor, confidences,
  ducked flag) — numbers, never audio — with a digest in the app and a
  *Share survey* button that hands the TSV to any app via FileProvider.
  `RoomSurvey` lives in the core so the digest is unit-tested.

### Verified
- LC-46LE830U over IP from the Android app: login accepted, `VOLM?` and
  `MUTE?` answer, mute/unmute and ducking confirmed `OK`. Documented in
  `docs/device-support.md` and the Android README.

## [0.7.2] - 2026-09-09
### Fixed
- Sharp IP login: fields are ended with **CR**, not CRLF — the LC-46LE830U
  took the stray LF as the password and refused every login. The Android
  client falls back to CRLF once if a set refuses CR; `network_ip` gains a
  `login_terminator` option (default `"\r"`). "User Name or Password
  mismatch" now counts as a refusal on both platforms, so the app says
  *rejected login* instead of *unreachable*.

## [0.7.1] - 2026-09-09
### Changed
- Android: the Sharp client keeps **one connection** open and reconnects when
  the set drops it, instead of a connection per command — the first run
  against an LC-46LE830U showed the set ignoring connections opened
  back-to-back. A silent reply is now "unconfirmed", not a rejection; only
  `ERR` rejects. *Test TV* logs every raw exchange, including the login
  prompts, and tests ducking with the configured normal volume when the set
  will not answer `VOLM?`.

## [0.7.0] - 2026-09-08
### Added
- **Android on-device app** (`platforms/android`, ADR 0007): a pure-Kotlin
  `core` — DSP (arbitrary-length FFT via Bluestein), the loudness detector
  ported constant-for-constant, a microphone silence detector with an
  adaptive room floor, fusion and the state machine ported verbatim, 12-bit
  chroma fingerprints with an **audio-primary** pair-key matcher and learner,
  a file-backed store, the Sharp AQUOS IP client with login handshake and a
  **ducking** controller (`VOLM`, persisted pre-duck volume, remote-wins), and
  the engine — plus a thin `app`: microphone foreground service, encrypted
  settings, notification actions, a *Not an ad* Quick Settings tile, and a
  *Test TV* button that settles whether the set answers `VOLM?`.
- Conformance harness: `tools/gen_conformance.py` writes fixtures from the
  Python core; the Kotlin tests reproduce the signal and match every block
  (worst LUFS delta 5e-10) and replay fusion/state decisions tick for tick.
- CI builds the debug APK on every change under `platforms/android` (verified:
  the workflow assembles and uploads `adhush-android-debug`) and the
  release workflow attaches it to each release.

## [0.6.0] - 2026-09-08
### Added
- **Always-on-top mini window** (`adhush overlay`, `src/adhush/ui/overlay.py`,
  ADR 0008): a small, borderless, translucent, draggable pill showing
  PROGRAM / SUSPECT / MUTED with ✗ not-an-ad, ✓ is-an-ad and ■ stop. Standard
  library only (tkinter + urllib), runs as its own process so it can never
  stall detection, remembers where you put it. Started automatically by
  `adhush run` when a display exists; `[ui] overlay` / `--no-overlay` turn it
  off.
- **Always running**: `adhush service install|uninstall|status` installs the
  core as a start-at-login service on Linux/ChromeOS (systemd user unit),
  macOS (launchd agent) and Windows (Task Scheduler logon task), in the
  user's session so the overlay has a display.
- **`shutdown` IPC command**: the one sanctioned way a UI stops the core.
  Additive to protocol v1.
- **The core serves the web front end** at `/` (`[ipc] web_root`), so any
  phone, tablet or PC on the network needs only the core's address. Fixed
  allow-list of files, no path traversal, static files public while the API
  stays token-gated.
- **Installable web app**: `platforms/web` gains a manifest, a service worker
  and icons, so the front end installs to the home screen on Android, iOS,
  ChromeOS and desktop browsers. A **Mini window** button opens a Document
  Picture-in-Picture window (Chromium 116+): a real always-on-top floating
  control on ChromeOS, Windows and macOS browsers; a popup fallback elsewhere.
- Tag-triggered release workflow builds the wheel, sdist and a web-front-end
  zip and publishes them as a GitHub Release.
- `docs/release.md`: what each platform gets in this release, including what
  it does not (no native Android/iOS binaries yet; see ADR 0007).
### Changed
- `adhush run` prints the served address rather than a file path.
- Overrides win in the status display: a forced mute reads MUTED before the
  state machine ticks.

## [0.5.0] - 2026-08-28
### Added
- Phase 5 (hardware passthrough box) implementation:
  - `relay_hdmi` controller: a GPIO-driven relay physically opening the
    intercepted audio path. Discrete, instant, TV-agnostic, with
    commanded-state readback; wired through normally-closed contacts and
    energized only to mute, so a crash, power loss, or shutdown always
    fails *unmuted*. Injectable pin driver (pigpio by default).
  - `passthrough-box` device profile and
    `config/adhush-passthrough.example.toml` (HDMI-UVC capture, full
    detector set, relay control, LAN-visible IPC with a token).
  - `docs/hardware-passthrough-box.md`: signal topology, parts list,
    fail-unmuted relay wiring, and the latency/delay-line design note.
  - `scripts/install-pi.sh`: real installer — system packages, pigpiod,
    venv, and an `adhush.service` systemd unit.
  - Probe support for `relay_hdmi`; every roadmap control backend is now
    implemented.

## [0.4.0] - 2026-08-28
### Added
- Phase 4 (platforms) implementation:
  - IPC: versioned JSON wire schema (`ipc/protocol.py`) and a stdlib-only
    localhost HTTP + Server-Sent-Events API (`ipc/api.py`, ADR 0006) with
    status, override (auto/mute/unmute), confirm/reject ad, live detector
    trace, optional bearer-token auth, and permissive CORS. `adhush run`
    starts it when `[ipc] enabled = true`.
  - Engine IPC surface: thread-safe status snapshots, controller override
    pinning, event listeners, and user feedback — confirm forces learning a
    segment; reject unmutes immediately, skips learning, and deletes the
    fingerprint behind a false match.
  - Capture: `screen` (x11grab / avfoundation / gdigrab), `camera`
    (v4l2 / avfoundation / dshow with adaptive screen-rectangle detection,
    glare-tolerant auto-crop), `microphone`, and `line_in`, all with
    format-prefixed device strings and pure, testable argv builders.
  - Control: `local_audio` host mute (pactl/amixer, osascript, nircmd) with
    state readback where the platform allows; probe support included.
  - Platform shells: `platforms/web/index.html` — a dependency-free static
    front end over the API (SSE live state, override and feedback buttons) —
    and concrete per-platform run instructions for Linux/Pi, Windows, macOS,
    ChromeOS, Android, and iOS (mobile = thin client over a networked core).

## [0.3.0] - 2026-08-28
### Added
- Phase 3 (breadth of control) implementation:
  - `cec`: mute toggle through cec-client (User Control Pressed/Released);
    toggle-only by CEC's nature, so pair with audio verification.
  - `ir_pigpio`: LIRC-free raw IR waveforms on a GPIO pin, encoding NEC,
    extended NEC, Samsung, Sharp (double inverted frame), Sony SIRC
    (12/15/20-bit), RC-5 bi-phase, and raw pulse/space arrays.
  - `ir_blaster_net`: Global Caché iTach `sendir` over TCP and Broadlink
    RM devices via the optional `broadlink` package.
  - `network_ip`: profile-driven TCP (e.g. Sony Simple IP with discrete
    mute and state readback) and HTTP (e.g. Roku ECP) control.
  - `control/probe.py` + `adhush probe` (ADR 0005): safe, side-effect-free
    discovery of which backends can drive the set, reported in the
    profile's preference order; `--active` sends a real mute/unmute pair.
  - `resolve_options`: profile-supplied backend settings (including the
    shared `[ir]` section) merged under `[control.<backend>]` overrides,
    keeping device specifics in profiles.
  - Profile library: `samsung-generic`, `lg-generic`, `sony-bravia-generic`
    (Simple IP discrete mute + readback), `vizio-generic`, `roku-tv-generic`
    (ECP), documented in docs/device-support.md.
- Config: `ControlConfig.sections` keeps every `[control.<backend>]` section
  so probing can resolve options for non-selected backends.

## [0.2.0] - 2026-08-28
### Added
- Phase 2 (vision and memory) implementation:
  - `logo_absence` detector: edge-template calibration via `adhush calibrate`
    (live or from a recording), Pearson-correlation presence scoring, absence
    runs voting AD, and a positive `program_present` signal.
  - `scene_cut` detector: shot-change rate mapped to confidence between
    configurable cuts-per-minute bounds.
  - Fingerprint subsystem: DCT perceptual hash (`video_phash`), chroma-bit
    audio fingerprint (`audio_chroma`), SQLite store with TTL pruning,
    vectorized Hamming matcher with consecutive-hit confirmation and audio
    corroboration, and a learner with duration averaging, duplicate
    detection, and slot snapping (15/30/45/60 s) until enough airings agree.
  - `fingerprint` detector: samples frames (flat frames gated out), keeps
    rolling hash/chroma buffers, and confirms known ads.
  - State machine promotion: a confirmed fingerprint hit jumps straight to
    AD with no dwell; inside the matched window only sustained positive
    program evidence (the logo back on screen) unmutes early, and the
    max-mute ceiling still wins.
  - Engine: promotion wiring, learned-duration windows, learning
    fusion-confirmed segments on unmute, and duration updates from each
    airing (an early unmute shortens the stored duration).
  - CLI: `calibrate` and `learn` implemented; `replay --config` now runs the
    full pipeline including the fingerprint store.
- Config: `[detect.logo_absence]` (ROI defaulting from the device profile,
  template path), `[detect.scene_cut]`, expanded `[fingerprint]` options,
  and `fusion.fp_unmute_dwell_ms`.

### Changed
- An uncalibrated `logo_absence` is excluded from fusion's enabled mass
  instead of diluting it with permanent zero votes.

## [0.1.0] - 2026-08-28
### Added
- Phase 1 (Raspberry Pi reference) implementation:
  - Event types, TOML config loading with device-profile inheritance,
    ring buffers, image ops, dwell timers, and slot snapping.
  - Capture: deterministic `file_replay` (`.npz` fixtures and ffmpeg-decoded
    media) and live `hdmi_uvc` (V4L2 + ALSA via ffmpeg).
  - Detectors: `black_frame`, `silence` (spectral-flatness aware), and
    `loudness` (K-weighted short-term level vs. frozen-while-elevated program
    baseline), each voting confidence with machine-readable reasons.
  - Weighted fusion with Schmitt-trigger hysteresis; PROGRAM → SUSPECT_AD →
    AD → RECOVERY state machine with asymmetric dwell and a hard max-mute
    ceiling.
  - Controllers: `rs232_sharp` (AQUOS discrete mute with state readback) and
    `ir_lirc` (discrete or toggle via irsend).
  - Engine driving offline replay and live capture, with mute-onset /
    unmute-onset precision–recall evaluation (ADR 0004).
  - CLI: `run`, `replay` (with labeled-ground-truth scoring), `doctor`,
    `ir-test`.
- Test suite: unit tests per module plus labeled `file_replay` integration
  tests reporting mute-onset and unmute-onset scores separately.

### Changed
- Default loudness window shortened to 1.5 s so unmute latency stays under
  ~2 s; a late unmute is the worse failure.

## [0.0.1] - 2026-08-28
### Added
- Initial repository scaffold: module layout, interfaces, docs, config schemas.
