# ADR 0009 — Teach mode: user-bracketed breaks learned as material

Status: Accepted (Android core; Python parity pending). 2026-09-12.

## Context

The Android app's two user buttons were designed for corrections: *Is an ad*
promoted the state machine into AD and *Not an ad* cancelled a mistaken duck.
In practice the first was useless for teaching: with no detector evidence to
hold it, the machine left AD after the 400 ms unmute dwell, and the learner
kept only the first 6 s of the segment, stamped with the segment's length.
The owner wanted to teach the app a whole commercial break by bracketing it
with two presses, and to have the spots recognised afterwards in any order,
alone, or as 15-second cut-downs. The first room survey also showed that in
a quiet living room the silence detector may never see a break boundary, so
user teaching is the primary way the store gets filled, not a fallback.

## Decision

- **User hold.** *Is an ad* sets a hold that keeps the machine in AD whatever
  the detectors say; only *Show's back*, *Not an ad*, the remote, or the
  240 s ceiling end it. The hold is implemented as a fingerprint-style hold
  with no programme evidence, so the existing machine is unchanged.
- **Material records.** *Show's back* (or the ceiling) learns everything heard
  since the hold began as one record of kind `material`: every chroma block,
  not the first window. Records of kind `ad` (fusion-learned single spots)
  keep their slot-snapped durations and duration-bounded holds.
- **Rolling matching.** A match on material does not predict an end. The
  detector keeps the duck while the live window still agrees with the record
  at the current alignment; when it stops agreeing it tries to re-anchor on
  any known material at any offset, and otherwise lets a grace of
  `material_grace_s` (5 s) run out. Different order, a lone spot, and a
  cut-down that shares audio all fall out of this. A cut-down with new audio
  must be taught once.
- **Corrections keep their meaning.** *Not an ad* during teaching cancels
  without learning. *Show's back* on an automatic duck that overran just
  restores, learning and forgetting nothing.

## Consequences

- The fingerprint buffer holds 300 s, enough for a ceiling-length break.
- Release after a taught break is grace + unmute dwell, about 5.5 s, unless
  the show's audio is itself in the store (it never is). The fusion path
  still releases sooner when the silence and loudness detectors see the
  boundary.
- Store lines gain a seventh field, the kind; older files load as `ad`.
- The Python core's `confirm_ad` has the same hold gap; it is out of scope
  here because its fingerprint model keys on video.
