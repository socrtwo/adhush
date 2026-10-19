# ADR 0016 — What we took from admuffs: duck compensation and the mute paradox

Status: Accepted. 2026-09-15.

## Context

The owner asked whether [admuffs](https://github.com/ablanquart/admuffs)
(a C++ Raspberry Pi commercial muter with an IR pHAT) had methods worth
borrowing. Most of its design is already here in another shape: a shared
audio bus (our shared decoded frame), a loudness source with a baseline
that will not follow an ad (ours freezes and has a `max_elevated_s`
escape), a weighted fusion with hysteresis (our Schmitt trigger and dwell
timers), a failsafe ceiling (`max_mute_s`) and a reset after it (our
RECOVERY state), a JSON IR database (our profiles), and a web remote (our
web page and IPC). Its ACRCloud fingerprinting is a paid cloud service
where ours learns locally.

One thing it names and handles that we did not: **the mute paradox**. A
room microphone hears the set get quieter the moment we duck it. The
loudness detector then reads "quieter than the baseline" as *programme
resumed*, the machine unmutes into the commercial, the mic hears the ad
again, and the set is ducked once more — the "repeated ducking" the owner
saw in September logs. admuffs answers it three ways: measure the actual
attenuation after a volume drop and compensate the loudness source; discard
audio "programme" verdicts while muted with a room mic; and never outlive
the failsafe.

## Decision

- **Detectors are told about ducks.** `Detector.audio_ducked(ts, ducked)`
  (Python) / `audioDucked` (Kotlin) is a new optional hook. The engine calls
  it after every successful mute/unmute, override or user action — only
  when the audio is a microphone in the room (Python: `capture.backend` is
  `camera` or `microphone`; the phone always is). A line tap hears the
  broadcast whatever the set does, so it is never told.
- **Loudness compensates.** On a duck the detector freezes its vote for one
  window plus a second (so the pre-duck ad is not "gone" before the room
  has settled), remembers the pre-duck short-term level, then measures the
  post-duck level and adds the difference (clamped to 30 dB) to every later
  reading. The ducked ad is judged on the original scale; a ducked
  programme reads as programme, so the unmute is signal-driven as before.
  The baseline is not updated by a window straddling a volume change.
- **Buried means inert, not stuck.** If the post-duck level sits within
  6 dB of the silence gate, or the settle window is spectrally flat noise
  (the silence detector's own room-versus-broadcast test), the ducked set is
  below the room and no offset can recover it. The detector goes inert
  (`voting = False`, reason `ducked_buried`) until the volume is back, and
  the other methods — camera, fingerprints, scripts, judges — decide the
  unmute, with `max_mute_s` as the ceiling. This is admuffs's "discard
  programme verdicts" rule, applied per detector and only when it is true.
- **Silence is inert while ducked.** A ducked set heard through a room mic
  cannot show a real gap: every quiet moment is our own doing.
- **Not taken.** Volume *normalisation* (levelling ads instead of ducking)
  is a different product; a controller that reads audio levels would break
  ADR 0003. Recording IR codes from the real remote is useful for the
  smaller Sharp and is a candidate for `adhush probe`. ACRCloud is not
  needed: our fingerprints are local and free.

## Consequences

- A room-mic setup with loudness as its main audio method no longer
  oscillates after a duck. The Python listener and the phone behave the
  same; both have replay tests for the compensated and the buried case.
- The owner's room (loud fans, duck level 4, "inaudible") is the buried
  case: loudness will report `ducked_buried` and the camera bug, the
  fingerprints and the AI judges carry the unmute. A duck level of 8–10
  keeps the set audible to the mic and lets loudness time the unmute.
- Reason strings gain `duck_settling`, `duck_offset_db=` and
  `ducked_buried`, so a log shows which path a duck took.
