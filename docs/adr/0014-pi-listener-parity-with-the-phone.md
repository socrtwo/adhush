# ADR 0014 — The Pi listener learns what the phone learned

Status: Accepted. 2026-09-14.

## Context

The Android app went through a week of living-room testing (ADR 0009 to
0013) that the Python core never had: a hand-held camera, a Sharp that
allows one control connection, a set that must be turned *down* rather
than muted so the microphone keeps hearing it, a "Not an ad" that has to
mean something, and a logo detector that must not call the bug gone before
it has ever seen it. The owner asked for the Raspberry Pi version "in that
light", plus a beginner's build guide for a Pi on a shelf with a webcam and
a microphone, controlling the same Sharp over Wi-Fi.

## Decision

Port the rules, not the code, into the Python core (0.15.0):

- **Inert votes.** `Detector.voting` (default True) lets a detector cast no
  vote; `Fusion.combine` normalises over the detectors present this tick
  (`max(present_mass / 2, 0.30)`), so an inert detector neither adds
  evidence nor dilutes it. With every detector voting the numbers are
  unchanged, so the Kotlin conformance fixture still holds.
- **The user's word reaches the detectors.** `Detector.user_says_program`;
  the logo detector drops its absence and (with `require_sighting`) demands
  a fresh sighting. `reject_ad` also opens `fusion.not_ad_quiet_s` (60 s)
  in which the decision is forced to program.
- **Logo detector.** `require_sighting` (score starts at 0; absence counts
  only after one raw correlation clears the threshold), `search_px` (the
  ROI slid over ±px of a 320-wide screen, best correlation wins, box
  arithmetic identical to `extract_roi`), `stale_s` (no frame for this long
  = inert with reason `no_screen`). `describe()` feeds the status line.
- **Whole screen or nothing.** `capture.camera.screen_complete`: a screen
  box touching the frame edge or outside 1.15–2.6 aspect is a partial or
  false screen; such frames are dropped (not cropped) and the box is looked
  for again half a second later, so the logo detector goes stale rather
  than absent.
- **Teach mode.** `confirm_ad` outside AD promotes to AD with a user hold
  (`fp_hold` semantics, no program evidence); the new `show_back` command
  cancels it and learns the bracketed segment through the existing learner;
  outside teach mode it stands down without learning. Status carries
  `teaching`, `quiet_s` and `camera`; the web page has ▶ Show's back.
- **Network control.** `PersistentTcp` keeps one connection, logs in once,
  drains late replies, reconnects on EOF and resends once, and names a set
  that closes before the login prompt as *busy* (one control connection)
  rather than a wrong password. `duck_level` with the profile's
  `volume_set` / `volume_query` commands turns the set down and back,
  remembering the pre-duck volume in `duck_state_file`; `recover_on_start`
  repairs a run that died ducked. The Sharp profile gains the VOLM commands.
- **Config.** `config/adhush-listener.example.toml` is the shelf setup;
  `docs/build-guide-tv-listener.md` is the guide.

## Consequences

- The Pi listener behaves like the phone on the four methods it shares
  (quiet gaps, loudness, remembered breaks, channel bug). Speech and
  captions stay phone-only for now; both are portable (Vosk and Tesseract
  exist for Python) and are the obvious next port.
- `confirm_ad` changed meaning outside AD: it used to be a no-op, now it
  mutes. Old clients that sent it only while muted see no difference.
- Per-command connections are gone from `network_ip`; sets that preferred
  them (none known) would need a `persistent = false` option, not added
  until one appears.
