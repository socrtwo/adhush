# ADR 0021 — Aspect-ratio change on the HDMI path, and the crest factor in loudness

Status: Accepted. 2026-09-16.

## Context

The 0.21.0 review of peer projects left two cheap cues on the table, and
the owner asked for them in this order:

1. **Aspect-ratio change** (comskip's `aspect ratio` detect method, in use on
   recordings for twenty years). A channel keeps one picture shape for
   hours; many commercials arrive in another — a 4:3 spot pillarboxed, a
   film clip letterboxed inside a 16:9 spot. The scaffold has carried an
   empty `detect/aspect_change.py` since Phase 1.
2. **Crest factor** (beepscore's analysis of ad audio). Commercials are
   mastered with heavy compression: their peak-to-RMS ratio sits several dB
   under the programme's even when their loudness does not. The loudness
   detector only looked at level.

Constraints from CLAUDE.md hold: detectors vote with a reason string and
never call a controller; the Kotlin and Python cores implement the same
ideas; the Pi budget is 60 % of a core for all video detectors together.

## Decision

### `aspect_change` (Python only: HDMI and screen capture)

On every fourth of a second the detector takes the shared luma frame,
averages it down to about 180 rows, and counts dark, flat rows from the
top and bottom and columns from the sides (`bars`: mean ≤ 24, standard
deviation ≤ 8 — a dark, busy scene is not a bar). What is left is the
active picture; its shape is `frame_aspect × (active_w / w) / (active_h / h)`.
A frame with less than a quarter of its height or width active is a black
frame, `black_frame`'s business, and teaches nothing.

A new shape must hold for `confirm_s` (1 s) before it counts. Confirmed
shapes, rounded to 0.1, feed a rolling mode over `baseline_s` (10 min);
the mode is the programme's shape once `min_baseline_s` (30 s) has been
seen. While the picture holds another shape the detector votes 1.0 with
`aspect_changed from=… to=… bars=… age_s=…`, and the baseline **freezes**,
as the loudness baseline freezes while elevated: a three-minute break in
4:3 cannot outvote the programme. A change that outlasts any break
(`max_change_s`, 6 min) is the programme changing shape — a film started —
and the history restarts from it.

It is **inert unless changed** (`voting` is false otherwise). The
programme's own shape is not evidence of anything — most spots keep it —
so a standing 0 vote would only dilute the detectors that do see something
through fusion's present-mass normaliser. Like the jingle, it speaks only
when it has something to say. It carries the default weight and never
mutes alone; a news channel cutting to archive footage changes shape too.
What it adds is a vote that is *sustained* for the length of the spot,
which the transient boundary signals lack.

It is not ported to Kotlin: the phone's camera path has its own crop and
the phone never sees an HDMI signal.

### Crest factor inside `loudness` (Python and Kotlin)

Every audio block's crest factor, 20·log10(peak / RMS), joins the same
short-term window as its K-weighted mean square, and the window's mean
crest is tracked against a slow baseline taken and updated exactly when the
loudness baseline is — so it freezes while elevated, skips the duck-settle
window and ignores the start-up ramp for the same reasons. A drop of
`crest_drop_db` (4 dB) × 1.4 below the baseline is a full crest vote, and
the crest vote adds `CREST_SHARE` = **half** a vote to the loudness
confidence, capped at 1.0. Half, never whole: a music bed inside the
programme is compressed too, and must not mute by itself; a spot mixed no
hotter than the show still shows, and a hot spot saturates sooner. Setting
`crest_drop_db = 0` turns the cue off. The reason string gains
`crest_db=… crest_drop_db=…`.

The Kotlin port is line for line, with the conformance fixture regenerated
(the fixture's steady tones have a constant crest, so its values did not
move).

## Consequences

- Seven video-capable detectors in the Python core; `aspect_change` is in
  the default `enabled` set and the HDMI/passthrough example configs, not
  in the audio-only listener's. The synthetic fixtures gained an `ad_43`
  kind (an ad with black pillars) so the detector has labelled ground truth.
- The loudness detector's reason string is longer; nothing that parses it
  exists outside the tests.
- Cost: one 180-row luma reduction four times a second, and one pass over
  each audio block for its peak. Both are far inside the Pi budget.
