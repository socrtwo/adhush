# ADR 0020 — Break jingles, the ad badge, learned break lengths, and a countdown you can extend

Status: Accepted. 2026-09-15.

## Context

After the review of seven peer projects (AdMuteTV, Mx5-MuteTimer, DTC,
MuteAd, muteButton, AdVent, AutoAdMuter) the owner asked for the four ideas
worth keeping:

1. **The channel's break jingle** (AdVent). Many channels play the same
   short sting — a whoosh, a three-note ident — going into every break and
   often coming out of it. Hearing it is the earliest evidence there is,
   earlier than a loudness jump or a logo going away.
2. **The ad badge** (badge OCR). A live stream in a browser labels its
   commercials on screen: "Ad", "Ad 1 of 3", "AD 0:15", "Your video will
   resume". During stream learning (ADR 0018) the phone can read that text
   and use it as ground truth.
3. **How long breaks run** (DTC). Breaks on one channel cluster around a
   few lengths. Knowing them puts a ceiling on a mute and tells the owner
   roughly how long is left.
4. **A countdown you can extend** (Mx5-MuteTimer). A manual duck shows the
   seconds left and has a `+30 s` button. And since real breaks run to five
   minutes, the manual buttons must go past 120 s.

Constraints from CLAUDE.md hold: detectors return a confidence vote with a
reason string and never call a controller; the Kotlin core and the Python
core implement the same ideas; device-specific behaviour stays out of
Python.

## Decision

### Method 10: break jingles (`Jingle.kt`, `detect/jingle.py`)

The detector keeps 400 s of chroma-bit blocks (one per 0.5 s, the same
`chromaBits` the fingerprints use, so no new DSP). When a break ends — the
engine's `learnBreak(startTs, endTs)` on every unmute the user did not
override — it cuts two candidates: the *opener*, blocks from 4 s before the
break started to 2 s after, and the *closer*, 2 s before it ended to 4 s
after. A candidate is compared with every known candidate of the same kind
by sliding a 3-s core across it and taking the best aligned bit agreement
(`sameSting`, threshold 0.72; 0.5 is chance). A match increments `hits`;
otherwise the candidate is stored, up to 80. At `hits ≥ 3` — three
distinct breaks opened by the same sound — the candidate is **promoted**
and starts voting.

Live, every new block slides the promoted openers over the last 3 s. A hit
votes 1.0 with reason `jingle_open id=… agree=… age_s=…` for `holdS`
20 s; a promoted closer heard while ducked sets `programPresent` for 10 s,
which the engine treats like the channel bug coming back. An opener carries
`LOGO_WEIGHT`, so it may duck alone: that is the point of it. The detector
is **inert** (`voting = false`) until it has a promoted opener — a learner
with nothing to say must not dilute the normaliser the other detectors
share (`fusion.py`). **Not an ad** counts a `falseHit` against the opener
that fired; when false hits reach hits, the jingle is demoted to a
candidate again. Storage is `jingles.tsv` (`j\tid\tkind\thits\tfalse\tcreated\tb,b,b`),
and it travels with the memory export (ADR 0018).

A channel with no sting simply never promotes anything; the method costs
one chroma frame per half second.

### The ad badge (`Badge.kt`, app `BadgeReader.kt`)

Stream learning only. The phone's MediaProjection already exists for
playback capture; `BadgeReader` adds a 640×360 virtual display, stacks the
four corner strips of each frame and runs ML Kit text recognition once a
second. The core's `BadgeDetector.observeText(ts, text)` matches a
whole-word `Ad`/`Advertisement`, `Ad 1 of 3`, `AD 0:15`, or the resume
phrases. A badge seen votes 1.0 (LOGO_WEIGHT) for 3 s after the last
sighting; scans continuing with no badge for more than 2 s is
`programPresent`. No scans for 10 s makes it inert — a player without a
badge must not vote either way. The frames never leave the phone and are
never stored; only the text is looked at, then dropped.

### Learned break lengths (`Clock.kt`, `detect/clock.py`)

The break clock (ADR 0017) now also keeps a histogram of break lengths in
30-s bins up to 6 min, from the same confirmed breaks that feed the
minute-of-hour counts (`clock.tsv` v2, `d\t<bin>\t<count>` lines; v1 files
still load). With at least five samples:

- `ceilingS(hardMax) = clamp(p90 + 30 s, 90 s, hardMax)` becomes the state
  machine's mute ceiling at every MUTE. A channel whose breaks run
  2–3 min stops being held for the configured 5-min maximum when the
  detectors lose the thread.
- `remainingS(elapsed) = p75 − elapsed` is what the status line shows as
  "about m:ss left" during an automatic duck. It is a guess and is
  labelled as one.

The hard maximum in config is still the ceiling until the histogram has
learned something, and always the upper bound.

### Manual ducks: 30 s to 300 s, and `+30 s`

The manual buttons go 30/60/90/120 and 150/180/210/240/270/300 s
everywhere (phone Home, the phone remote, the web page, the IPC `duck_for`
command). `Engine.extendDuck(now, s)` adds to a running timed duck, capped
at 10 min; the same call on a duck the detectors started converts it into a
timed one, so `+30 s` also means "hold this longer than you think". The
status line counts down `m:ss left`, the Android notification carries a
`+30 s` action, and the IPC command is `{"type":"duck_for","seconds":30,"extend":true}`.
A timed duck learns nothing (`Source.TIMED`), as before.

### Where the remote is

Unchanged from ADR 0017, made easier to find: on the phone, Home →
*Manual duck and the remote* → **Open the remote control**, and now the
remote icon in the toolbar of every screen. On the web page it is the
Remote card below the controls.

## Consequences

- Ten methods on the phone; the Python core gains `jingle` in
  `PHASE3_DETECTORS` and the listener example enables it.
- Replay tests and the CLI flow tests point the jingle and clock stores at
  temp files; a fixture with identical program audio before every break
  would otherwise "learn" the programme as a sting. `data/clock.tsv` and
  `data/jingles.tsv` are ignored by git.
- The badge reader is a second consumer of the projection; both stop with
  stream learning. It is an Android-only method and is not counted in the
  ten, since it never runs at the TV.
- Break lengths change unmute behaviour only by lowering a ceiling. They
  never end a mute early on their own; a late unmute is still the worse
  failure and the detectors still decide.
