# AdHush for Android — design

Status: design, not yet implemented. Decision record: `docs/adr/0007-android-on-device-detector-core.md`.

## Summary

An Android app that mutes commercials on a **Sharp LC-46LE830U** with no
hardware at all: the phone's **microphone** hears the TV across the room, the
same detector/fusion/state-machine logic the Python core uses decides when a
commercial is playing, and the phone **sends a volume command to the TV over
Wi-Fi**. No HDMI splitter, no capture stick, no relay, no Pi, no IR line of
sight. Install, type the TV's IP, done.

This is possible because of two verified properties of this particular set
(both from the LC-40/46/52/60LE830U operation manual):

| Fact | Where |
|---|---|
| IP control over TCP, enabled in MENU > Initial Setup > Internet Setup > Network Setup > IP Control Setup, with a user-set port and optional login ID / password | p. 58 |
| Command framing: 4 ASCII command chars + 4 space-padded parameter chars + CR; answers `OK` or `ERR` | p. 58 |
| `MUTE` — 0 toggle, **1 = on, 2 = off** (discrete, not a toggle) | p. 59 |
| **`VOLM` — VOLUME — parameter "Volume (0–60)"** (absolute volume) | p. 59 command table |
| The set drops an idle connection after 3 minutes | p. 58 |

`VOLM` is what makes the microphone approach work at all. See below.

## Architecture: standalone, not a thin client

`platforms/android/README.md` currently describes Android as a thin client to
a networked Python core (ADR 0002). That stays supported and is still the
right answer for a TV that needs IR or a passthrough box. But for *this* TV
the phone can do the whole job, so the recommended mode is standalone:

```
  ┌─────────────────────────── Android phone ───────────────────────────┐
  │  mic ──► AudioRecord ──► AudioBlock(100 ms, 48 kHz mono float)      │
  │                              │                                      │
  │                              ├──► SilenceDetector    ┐              │
  │                              ├──► LoudnessDetector   ├──► Fusion    │
  │                              └──► AudioFingerprinter ┘      │       │
  │                                                              ▼      │
  │                                                     AdStateMachine  │
  │                                                              │      │
  │                                       SharpIpClient ◄─────────┘      │
  └──────────────────────────────────────┬──────────────────────────────┘
                                         │ TCP, one connection per command
                                         ▼
                          Sharp LC-46LE830U  (VOLM / MUTE)
```

Modes considered and not chosen:

- **Thin client to a Pi core** — already works today via `platforms/web`. Keep
  it; it is strictly better when a passthrough box is present. It just does
  not meet "no hardware".
- **Phone as sensor streaming to a Pi core** — adds a Pi *and* network latency
  to reach the same decision. Worst of both.

## The central decision: duck, don't mute

A microphone-based detector has a problem the passthrough box does not. The
box taps audio *before* the relay, so it keeps hearing the programme while
muted. A phone hears the room *after* the TV's own volume control — so the
instant the app mutes the TV, its only sensor goes deaf, and it cannot tell
when the commercial break ends.

**Solution: `VOLM` to a low level instead of `MUTE 1`.** Ducking to volume 3–5
(of 60) is inaudible from the sofa but leaves the phone plenty of signal, so
the detectors keep running through the break and the state machine can unmute
promptly at the pod boundary. Restore with `VOLM <normal>`.

Consequences:

- **Learn the normal volume.** At startup, try `VOLM?`. The manual notes a `?`
  parameter returns the present value for some commands; if this set answers
  `ERR`, fall back to a value the user sets in the app. Either way, poll
  `VOLM?` every ~5 s while in `PROGRAM` — that tracks the user turning the
  volume up with the remote, and doubles as a liveness check on the TV.
- **The remote always wins.** If a `VOLM?` poll while ducked returns something
  other than the level we set, the user has intervened: cancel the duck, adopt
  their new level as normal, and enter `RECOVERY`. This is a real advantage
  over the relay build, where a stuck mute left the user no recourse.
- **`MUTE 1`/`MUTE 2` remains the fallback** if `VOLM` misbehaves on this
  firmware. In that mode the app is blind while muted, so it must lean on
  fingerprint-learned durations (the existing `fp_hold` path) and a
  conservative `max_mute_s` of one ad slot (30 s), then unmute and re-listen:
  the detectors re-trigger within ~1 s if the break is still running, so the
  worst case is a ~1 s blip of commercial every 30 s. Honest degradation, and
  a reason to prefer ducking.

## Fail-safe posture

The relay build's rule was *fail unmuted*. The equivalent here: **the TV must
never be left ducked.** A crashed app that leaves the set at volume 4 is this
design's stuck mute. Required:

1. **Persist the intent, not just the state.** The moment the app ducks, write
   `{duckedFrom: N, at: t}` to disk. On any start-up, if that record exists,
   restore volume `N` first and clear it — before anything else.
2. **Restore on every exit path**: service `onDestroy`, task removal,
   `Thread.setDefaultUncaughtExceptionHandler`, and a `ProcessLifecycleOwner`
   hook.
3. **`max_mute_s` is a hard ceiling** enforced by a wall-clock timer, not by
   the detector loop — a hung audio thread must not hold the duck.
4. **Watchdog on the TV link.** If a command fails or the poll times out
   twice while ducked, attempt the restore, then stop and notify.
5. **The notification always carries a one-tap "Restore volume"**, plus a
   Quick Settings tile, so the user's recovery path never requires opening the
   app.

## Module layout: 1:1 with the Python core

The Python core stays the reference implementation. The Kotlin modules mirror
it name-for-name so the two can be diffed by eye during review, and so the
conformance tests below can be written against shared fixtures.

| Kotlin | Mirrors | Notes |
|---|---|---|
| `capture/MicSource.kt` | `capture/microphone.py` | `AudioRecord` → `AudioBlock` |
| `detect/Detector.kt` | `detect/base.py` | same vote contract |
| `detect/SilenceDetector.kt` | `detect/silence.py` | **adaptive floor** — see below |
| `detect/LoudnessDetector.kt` | `detect/loudness.py` | port verbatim |
| `detect/Fusion.kt` | `detect/fusion.py` | port verbatim |
| `AdStateMachine.kt` | `state.py` | port verbatim |
| `fingerprint/Chroma.kt` | `fingerprint/audio_chroma.py` | port verbatim |
| `fingerprint/AudioMatcher.kt` | *new* | audio-primary; see below |
| `fingerprint/Store.kt` | `fingerprint/store.py` | same SQLite schema |
| `control/SharpIpClient.kt` | `control/network_ip.py` | + `VOLM` ducking |

Detectors vote, controllers act, and no detector touches the client — ADR 0003
holds unchanged.

### Constants to port exactly

These come from the Python and must not be re-guessed; they encode tuning work
already validated against labelled fixtures.

```
Fusion:      mute_confidence 0.72   unmute_confidence 0.45
             default weight 0.15    normalizer max(enabled_mass/2, 0.30)
State:       mute_dwell 900 ms      unmute_dwell 400 ms
             fp_unmute_dwell 3000 ms   max_mute_s 240   RECOVERY 2.0 s
Loudness:    window_s 1.5   delta_lufs 2.5   baseline_s 120
             HP 38 Hz, shelf 1500 Hz, shelf gain 1.505, silence gate −55 LUFS
             full confidence at 1.4 × delta threshold
Silence:     min_run_ms 400   flatness_min 0.2   decay 2.5 s
Chroma:      12 bits, A4 440 Hz, band 60–3800 Hz, median threshold
Fingerprint: sample_interval_s 0.5   window_s 6.0   confirm_hits 3
             slot_snap 15/30/45/60 s
Audio:       48 kHz mono, 100 ms blocks
```

The fusion normalizer deserves a note: its floor is `2 × 0.15 = 0.30`,
described in `fusion.py` as ensuring "even a two-detector (audio-only)
configuration needs corroboration to mute". The audio-only case was
anticipated. With three detectors (silence, loudness, fingerprint) no single
one can mute alone, which is the property that keeps false positives rare.

## What has to change for a microphone

### 1. Silence needs an adaptive floor

`SilenceConfig.dbfs_threshold = -50.0` assumes a line-level signal where a pod
boundary is near-digital silence. A room never gets there: mic self-noise, the
fridge, and traffic sit far above −50 dBFS, so an absolute threshold either
never fires or fires constantly depending on mic gain.

Replace it with a **relative** test: track the 10th percentile of block dBFS
over a rolling 60 s as the room floor, and call a block quiet when it is within
`quiet_margin_db` (default 6 dB) of that floor. **Keep the spectral flatness
test unchanged** — flatness ≥ 0.2 is what separates a genuine gap (broadband
room noise) from quiet dialogue (structured), and it is level-invariant, so it
survives the move to a microphone untouched. This is the one detector that
needs new logic rather than a port; it gets its own config type and its own
fixture tests.

### 2. Fingerprinting must become audio-primary

Today the fingerprint path is keyed on video pHash, with `audio_chroma` used
only for corroboration (`FingerprintConfig.audio_corroboration`). With no
video there is nothing to key on, and a bare 12-bit chroma signature is far too
coarse to identify an ad by itself.

Spec for `AudioMatcher`:

- Maintain a rolling window of 12-bit chroma blocks at 100 ms.
- Build **pair keys**: `(chroma[t] << 12) | chroma[t + Δ]` for Δ ∈ {2, 4, 8}
  blocks — 24-bit keys, three per block. Selective enough that a few hundred
  learned ads produce few collisions.
- Look candidates up in an inverted index, then **verify with the existing
  `agreement()`** over the aligned overlap: require ≥ 0.72 agreement across
  ≥ 3 s, then `confirm_hits = 3` consecutive confirming samples, exactly as
  the video path does.
- Reuse `slot_snap_s` and the learned-duration logic verbatim; a confirmed
  match promotes straight to `AD` via the state machine's existing `promote`
  argument, which is the one sanctioned way a single detector mutes.

The store needs no redesign: `store.py` already has an `audio_blocks(ad_id,
offset_s, bits)` table. It needs one added index on the pair key.

**The 0.72 verify threshold is a starting guess, not a validated number.** Room
audio is noisier than a line tap, and this threshold trades false promotions
against missed repeats. It must be calibrated against real recordings from the
user's own room before the fingerprint detector is enabled by default.

Fortunate detail: `audio_chroma` already bands 60–3800 Hz, which sits inside
what a phone mic reproduces well. No change needed.

### 3. Three detectors instead of six

No video means no `black_frame`, `logo_absence`, `scene_cut`, or video pHash.
Accuracy will be materially below the passthrough box, which sees clean HDMI
and votes on six signals. Set expectations in the app's own onboarding: this
trades accuracy for needing nothing but a phone.

## Android platform specifics

### Audio capture — the AGC trap

The loudness detector measures a **2.5 LUFS delta against a slow baseline**.
Android's voice-oriented audio sources apply automatic gain control, which
normalises exactly the level differences the detector exists to find. Getting
this wrong silently disables the most useful detector.

```kotlin
val source = if (AudioManager.PROPERTY_SUPPORT_AUDIO_SOURCE_UNPROCESSED
        .let { audioManager.getProperty(it) } == "true")
    MediaRecorder.AudioSource.UNPROCESSED     // API 24+, no AGC/NS/AEC
else
    MediaRecorder.AudioSource.CAMCORDER       // next best: minimal processing

AudioRecord.Builder()
    .setAudioSource(source)
    .setAudioFormat(AudioFormat.Builder()
        .setEncoding(AudioFormat.ENCODING_PCM_FLOAT)   // matches float32 [-1,1]
        .setSampleRate(48_000)
        .setChannelMask(AudioFormat.CHANNEL_IN_MONO)
        .build())
    .build()
```

Never `VOICE_RECOGNITION` or `VOICE_COMMUNICATION`. Read in 4800-sample
(100 ms) blocks to match `audio_block_ms`.

### Staying alive

- **Foreground service**, `foregroundServiceType="microphone"`, with
  `FOREGROUND_SERVICE` + `FOREGROUND_SERVICE_MICROPHONE` permissions
  (the type and permission are mandatory from Android 14).
- `RECORD_AUDIO` runtime permission; the service must be started from a
  visible activity, since Android 12+ forbids starting a mic foreground
  service from the background.
- Persistent notification showing `PROGRAM` / `DUCKED`, with actions:
  **Not an ad**, **Is an ad**, **Restore volume**, **Stop**.
- A **Quick Settings tile** for "Not an ad" — the fastest possible correction
  when the app mutes the user's actual programme.
- Ask for battery-optimisation exemption, and explain why in-app rather than
  just firing the system dialog.

### Talking to the TV

**One TCP connection, kept open and reopened whenever the set drops it.** The
design first copied `network_ip.py`'s connection-per-command shape; the first
run on a phone (2026-09-09) showed the LC-46LE830U answering the first
connection and ignoring the ones opened moments later, so the app now holds
one connection, answers the login handshake once on it exactly as
`perform_login` does — read the prompt best-effort, send id + CRLF, read, send
password + CRLF, treat only an explicit refusal as an error — and reconnects
on end-of-stream, a socket error, or three silent replies in a row. The
5-second `VOLM?` poll keeps it inside the 3-minute idle disconnect anyway.
A silent reply is *unconfirmed*, not a rejection: only `ERR` is.

```kotlin
suspend fun send(cmd: String, param: String): String =
    Socket().use { sock ->
        sock.connect(InetSocketAddress(host, port), TIMEOUT_MS)
        sock.soTimeout = TIMEOUT_MS
        login?.let { performLogin(sock, it) }
        sock.getOutputStream().write("$cmd${param.padEnd(4)}\r".toByteArray(US_ASCII))
        sock.getInputStream().readSome()          // "OK", "ERR", or a value
    }
```

Finding the TV: ask for host and port, because the port is whatever the user
set in the TV's IP Control menu. Offer a subnet sweep on that port as a
convenience, not as the only path.

### Credentials

The IP-control login ID and password are the user's own and must be stored in
`EncryptedSharedPreferences` (Jetpack Security) or a Keystore-wrapped store —
never in plain `SharedPreferences`, never in logs, and never in a crash
report. Redact them from any diagnostic export.

## Latency budget

| Stage | Cost |
|---|---|
| Loudness window drain | up to 1.5 s (dominates unmute) |
| Mute dwell | 900 ms |
| Unmute dwell | 400 ms |
| Fusion + detectors per block | « 10 ms |
| LAN command round trip | ~20–80 ms |
| TV acts on the command | ~100 ms, unmeasured |

Expect roughly **1–2 s to duck** after a pod boundary and **0.5–1.5 s to
restore**. A confirmed fingerprint match bypasses the mute dwell entirely and
ducks in well under a second — the payoff for having heard the ad before.

## Battery

Continuous mic plus a 4096-point real FFT every 100 ms is small arithmetic but
prevents deep sleep. Budget on the order of a few percent per hour; measure
before claiming a number. Reduce it by running chroma only every 0.5 s (per
`sample_interval_s`) rather than every block, keeping everything on one
thread, and offering an "idle when the room is quiet" mode that drops to a
1 Hz level check until it hears the TV again.

## Testing and conformance

CLAUDE.md requires every detector to have a labelled-fixture test reporting
mute-onset and unmute-onset precision/recall separately. The port must meet
the same bar, and there is a way to make that rigorous rather than aspirational:

1. **Shared fixtures.** The Python core already replays `.npz` fixtures with
   labels and scores them via `engine.evaluate_onsets`. Ship the same fixture
   files as Android instrumentation-test assets.
2. **Vote-level conformance.** Feed identical audio blocks to the Kotlin and
   Python detectors and assert per-block vote agreement within a tight
   tolerance. This catches port errors — an FFT normalisation slip, a dropped
   Parseval factor in the K-weighting — that end-to-end tests hide.
3. **Onset scoring on device.** Run the full Kotlin pipeline over the fixture
   and assert the same precision/recall thresholds the Python suite asserts.
4. **Room fixtures.** Record the TV through a phone mic in the user's actual
   room, label it, and add it as a fixture. This is the only way to tune the
   adaptive silence floor and the chroma verify threshold honestly; both are
   guesses until it exists.
5. **A fake TV.** A loopback socket that speaks the AQUOS framing lets the
   client, the login handshake, and the whole duck/restore lifecycle be tested
   without the set — the same injectable-transport trick `network_ip.py` uses.

## Privacy

CLAUDE.md: no upload of captured audio or video off-device by default. This
design keeps that. Audio is processed in RAM and discarded; only 12-bit chroma
signatures and durations are persisted, and they are not reconstructible into
speech. Nothing leaves the phone except AQUOS commands to the TV on the local
network. No analytics on audio. If a diagnostic export is ever added it must
be opt-in, per-incident, and redact credentials.

## When to prefer the passthrough box

Say this plainly in the app:

| | Android app | Passthrough box |
|---|---|---|
| Hardware | none | ~$120–160 |
| Detectors | 3 (audio) | 6 (audio + video) |
| Works while phone is elsewhere | no | yes |
| Works on any TV | no — needs IP control | yes |
| Accuracy | lower | reference |

The app is the convenient build. The box is the accurate one. They share a
fingerprint store format, so ads learned on one could seed the other — a
plausible later feature, not part of this design.

## Open questions

- Does this set answer `VOLM?` with the current volume, or `ERR`? The command
  table marks query support per command in columns that did not survive text
  extraction. Decides whether volume tracking is automatic or user-configured.
  **Test first; the whole ducking design degrades gracefully either way.**
- What ducked volume is genuinely inaudible yet still detectable in this room?
  Needs the room fixtures.
- Does the TV's own audio processing (surround, DRC) compress the loudness
  delta enough to matter once it reaches a mic? Measurable with fixture 4.
