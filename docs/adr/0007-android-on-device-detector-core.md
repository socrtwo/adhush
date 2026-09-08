# 7. Android runs an on-device detector core, not only a thin shell

Date: 2026-09-08

## Status

Accepted (design). Implementation not started.

## Context

ADR 0002 chose a Python core with thin platform shells: one implementation of
the detection logic, and per-platform front ends that talk to it over the
IPC surface (ADR 0006). `platforms/android/README.md` follows that — the phone
is a browser pointed at a core running on a Pi or PC.

That is the right call for most devices, but it cannot satisfy the case the
Sharp LC-46LE830U makes possible. That set accepts the AQUOS command set over
TCP with a discrete `MUTE` and an absolute `VOLM` (0–60), both verified in the
operation manual (pp. 58–59). A phone on the same Wi-Fi can therefore hear the
TV on its microphone and command the TV directly — no Pi, no capture hardware,
no IR. Requiring a networked Python core would mean requiring the very hardware
this configuration exists to avoid.

Embedding a Python runtime in the app was considered and rejected: it is tens
of megabytes, awkward with `AudioRecord`, and numpy on Android is not a path
worth maintaining for three detectors.

## Decision

Android gets a **second implementation** of the detector, fusion, and state
machine layers, in Kotlin, running fully on-device. The thin-client mode stays
supported and remains preferred wherever a passthrough box or IR is in play.

To keep two implementations from diverging:

1. **The Python core remains the reference.** Kotlin modules mirror the Python
   module names 1:1 so review can diff them by eye.
2. **Tuned constants are ported verbatim**, not re-derived. They encode work
   already validated against labelled fixtures.
3. **Conformance is tested, not assumed**: identical fixtures feed both
   implementations, per-block votes must agree within tolerance, and the
   Kotlin pipeline must meet the same mute-onset and unmute-onset
   precision/recall thresholds the Python suite asserts.
4. **Contracts hold across both.** Detectors vote and controllers act
   (ADR 0003); device specifics stay in profile data, not in Kotlin.

Two behaviours are genuinely new rather than ported, and are owned by the
Android core:

- **Ducking instead of muting.** A microphone hears the room *after* the TV's
  volume control, so muting blinds the sensor. The app sets a low absolute
  volume with `VOLM` instead, keeping the detectors running through the break.
  This introduces a stuck-duck failure mode, handled by persisting the
  pre-duck volume before ducking and restoring it on every exit path.
- **An adaptive silence floor.** The absolute `dbfs_threshold` of −50 dBFS is
  meaningless in a room; the microphone detector tracks a rolling noise floor
  instead and keeps the level-invariant spectral flatness test unchanged.

## Consequences

- Two implementations to keep in step. Mitigated by the conformance tests, but
  it is a real, permanent maintenance cost and the reason this ADR exists.
- Android accuracy is structurally lower: three audio detectors against the
  box's six. Documented for users rather than papered over.
- The audio fingerprint path needs an audio-primary index, since the existing
  matcher keys on video pHash and treats chroma as corroboration only. The
  store schema already carries `audio_blocks` and needs only an added index.
- If a second platform later wants an on-device core (iOS is the obvious
  candidate), this ADR is the precedent — and the conformance-fixture
  machinery is the reusable part.
