# ADR 0023 — What comskip, MythTV and the Auto-Cut VCRs knew

Status: Accepted. 2026-09-16.

## Context

The September 2026 search (`docs/ideas-backlog.md`) found five cues that
twenty years of recorded-TV commercial flagging rely on and AdHush did
not: the parental-rating box, ad-unit lengths, uniform separators that are
not black, captured intro/outro frames, and the stereo/mono switch. A
sixth, the ATSC 3.0 / DVB-TA video watermark, is newer and designed for
exactly the set-top-box-behind-HDMI case this project lives in. The owner
asked for all six.

The rules stay: a detector votes with a reason, never calls a controller,
never reads another detector; every one has a labelled replay test; the
Pi budget holds; a late unmute is the worse failure.

## Decisions

### `rating_bug` — the parental-rating box is programme evidence

US networks flash "TV-14" in the upper left at the start of a programme
and after every break; commercials never carry one. MythTV scores the
block; AdHush makes it **positive programme evidence**, the thing that
may end a hold early. No OCR: inside a small upper-left ROI the detector
takes the bounding box of bright pixels and asks whether it is box-shaped
(wider than tall, mostly filled since the text is dark, a sensible share
of the ROI), whether it *appeared* (the ROI had been box-free for
`appear_after_s`, so a bright corner that is always there is scenery)
and whether it stayed `min_present_s`. Then `program_present` holds for
`hold_s`. It votes 0 with weight while present and is inert otherwise;
it never votes for an ad. Python for the HDMI path; on the phone
`RatingBugDetector` takes the screen box the logo detector already finds
and looks in the screen's own corner.

`Detector.program_present` is now a base-class property, and the engines
(Python and Kotlin) take programme evidence from **any** detector that
has it: the logo, the closing jingle, the badge, the rating box, an
"out" cutscene, an in-network cue, other devices' break ends.

### `ad_units` — ad-unit length quantisation

Spots are sold in 15/30/45/60/90/120-second units, so inside a break the
separators between them fall on a fifteen-second grid. The detector may
not read other detectors, so it finds cheap separators of its own on the
shared frame (a short black run) and the audio block (a short silence;
on the phone, the rolling-floor silence test of method 1), keeps their
times, and votes while the recent gaps fit the grid: a half vote for one
fitted gap, a full vote for two in a row, held `hold_s` after the last
separator. Default weight — never alone — but it *holds* a mute through
the sag in the middle of a pod when the boundary signals have decayed and
loudness is only halfway sure. Inert without a fit; "Not an ad" clears
it. `wants_video` lets it take frames when there are any without
needing them, so the audio-only listener runs it too.

### Uniform separators in `black_frame`

A frame whose raw-pixel luma spread (a strided sample, so a busy dark
scene is not averaged flat) is at most `uniform_spread` counts like a
black frame: a white flash, a colour card, a grey slate — the separators
European networks and some streamers use instead of black, comskip's
`non_uniformity`. The reason string says `uniform_run` instead of
`black_run`. The synthetic ad frames gained a gradient so a fixture's
flat "ad" is no longer a card.

### `cutscene` — intro/outro template frames

Comskip keeps up to eight user-captured frames and correlates every frame
against them. AdHush keeps templates in `data/cutscenes/<name>.npz` (a
32×18 luma thumbnail, its perceptual hash, a kind), written by
`adhush cutscene add --kind in|out --image still.png` or `--from
fixture.npz --ts 12.3`. A frame matches when its phash is within
`max_hamming` bits *and* the thumbnails' normalised correlation clears
`min_correlation`, so a flat frame cannot match a flat template on hash
alone. An "in" match (a break starts: "we'll be right back") votes 1.0
for `hold_s` and carries the alone weight; an "out" match (the title card)
is programme evidence for `close_hold_s`. The jingle's visual twin.

### `stereo_width` — the channel-count switch

Mitsubishi's Auto-Cut recorders skipped ads on one cue: the programme was
mono and the spots stereo, and the flip was the boundary; comskip still
penalises a channel-count change. On the captured feed the equivalent is
the mix's stereo width, side/mid RMS, measured by the capture backend
*before* its mono downmix (`AudioEvent.width`; HDMI, line-in and the
transport stream ask ffmpeg for two channels when `capture.stereo` is
on; a room microphone stays mono, its width would be the room). The
detector keeps a slow baseline that freezes while switched, exactly as
loudness does, and votes 1.0 while the width has been on the other side
of `min_delta` for `confirm_s`; a switch that outlasts any break becomes
the new baseline. Inert without width and inert at the programme's own
width — the aspect-change posture.

### `watermark` — ATSC A/335 / DVB-TA presence (experimental)

The watermark lives in the luma of the top one or two lines: symbols
eight pixels wide across a 1920-pixel frame, high or low, a run-in
pattern first. It survives set-top boxes and HDMI because it is picture,
and A/336 and HbbTV-TA use it to tell a TV behind a box where the breaks
are. AdHush does not decode the messages (that needs the broadcaster's
recovery server); it uses **presence**. A network feed that carries the
mark loses it the moment a locally inserted spot replaces the picture.
The detector reads the top lines, decides whether they hold a
symbol-aligned two-level pattern (the run-in seen, or a bimodal line
whose steps sit on symbol boundaries), keeps a baseline of "present"
over ten minutes, and votes 1.0 while the mark has been absent for
`confirm_s` on a channel where it is normally present. Inert on a channel
with no mark — most cable channels today — which is why it is on by
default at no cost. Geometry, run-in and thresholds are configuration
because this was written from the spec's published shape and not
verified against a live ATSC 3.0 feed. `describe` shows the last bits so
an owner can see what a channel emits.

## Consequences

- Fifteen Python detectors, every new one inert until it has evidence,
  so the default `enabled` list carries them all without diluting the
  fusion normaliser. The replay tests pin the new names.
- `black_frame` reasons changed shape; nothing outside the tests parsed
  them.
- Line-in and HDMI audio now ask ffmpeg for two channels; a mono card
  gets a copied channel and a width of 0, harmless.
- On the phone: ad units ride on Quiet gaps and the rating box on the
  camera method; no new switches, no new count of methods. The two are
  in the status reasons, not the chips.
