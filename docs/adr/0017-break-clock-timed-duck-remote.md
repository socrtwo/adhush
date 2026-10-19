# ADR 0017 — The break clock, timed manual ducks, and a remote with everything on it

Status: Accepted. 2026-09-15.

## Context

Three requests from the owner. Do commercials on a news channel run at
predictable minutes, and can that be a method? Can the set be turned down
by hand for 30, 60, 90 or 120 seconds? Can a full TV remote live inside
AdHush, with every AdHush control on the same screen?

Cable news is scheduled to a *format clock*: each hour's segments and
breaks sit at planned minutes, and the same programme keeps the same clock
for months. But the clock differs by show and daypart, is not published,
drifts with live news, and breaks are not all the same length (roughly two
to four minutes; the top-of-hour pod is the longest, and a network break
often runs into a local one). So a fixed table would be wrong within a
week, while a learned one is right most of the time.

## Decision

- **Method 9, the break clock** (`ClockDetector`, Kotlin and Python). Sixty
  counters, one per minute of the hour: how many watched hours had a break
  in that minute, and how many hours that minute was watched at all. Every
  break that ends through the normal paths (fusion, a fingerprint match,
  teach mode) marks the minutes it covered, if its length is plausible
  (15 s to 5 min); "Not an ad" and timed ducks teach nothing. The vote is
  the fraction of watched hours that were a break, scaled so 60 % reads
  as certainty, and the detector is **inert** (`voting = false`) until the
  minute has been watched in three distinct hours — so learning never
  dilutes fusion. Default weight (0.15): alone it can never reach the mute
  threshold; with loudness or a gap half sure, it tips the balance. It
  needs wall-clock time; a replay has none, so it stays inert there. The
  counters live in `clock.tsv` (phone) or `[detect.clock] file`.
- **Timed manual duck.** `duckFor(seconds)` / `duck_for` turns the set down
  now — from any state, including the recovery pause after a duck that
  just ended (`userMute` on the state machine) — and back up when the
  time runs out, whatever the detectors say meanwhile; "Show's back" ends
  it early; pressed while already ducked, it extends. Nothing is learned
  from it. Android: four buttons on Home and on the remote; Python: the
  `duck_for` IPC command and four buttons on the web page; the status
  carries `timed_s`.
- **The remote.** Sharp's control port accepts every key of the handset as
  `RCKY nn` (codes from the LE-series manual, the same over serial and IP).
  `RemoteKey` (Kotlin) and `control/remote_keys.py` (Python) carry the
  table; `SharpIpClient.press`, `MuteController.send_key`, the `remote`
  IPC command and a `commands.remote_key` template in the Sharp profile
  drive it. Android gets `RemoteActivity`: the TV keys in a grid plus
  Start, Stop, Is an ad, Show's back, Not an ad and the timed ducks. While
  the service runs the keys go through its connection (the Sharp allows one);
  otherwise the screen opens its own and closes it on leaving. The web page
  gets the same grid. Infrared control knows only volume and mute, and says
  so.

## Consequences

- A third light-weight audio-independent vote exists. It is honest about
  what it knows: the status line shows ":42 a break in 5 of 6 hours" or
  ":17 learning (1 of 3 hours)".
- Controllers gain an optional `send_key`; every existing backend keeps
  working (the default raises a clear ControlError).
- The Python `IMPLEMENTED_DETECTORS` set includes `clock`; replay tests
  that pin the default detector set list it.
