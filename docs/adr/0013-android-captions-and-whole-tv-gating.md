# ADR 0013 — Android: captions as the sixth method; the camera may only doubt what it has seen

Status: Accepted. 2026-09-14.

## Context

A log from 0.13 showed the set ducked again seconds after every *Not an ad*
during a show. The camera's logo detector (ADR 0011) voted "absent" at
full confidence from the first frame: its template did not match the live
picture (the phone in a hand eight feet away puts the screen box a few
pixels from where calibration had it, which is most of a small logo), and
nothing required the detector to have *seen* the logo before missing it.
Its vote carries three default weights, so it ducked alone, and *Not an
ad* only reset the fusion hysteresis, so the same vote ducked again after
the dwell. The owner also asked for captions as a sixth way of spotting a
commercial, and for the app not to duck when the camera is not looking at
the whole TV.

## Decision

- **Sighting before absence.** `LogoAbsenceDetector` starts "unknown"
  (score 0) with `requireSighting`; it may vote absent only after one frame
  whose raw correlation clears the presence threshold. Until then it is
  inert: no vote, no share of the normaliser. `Detector.voting` is the
  generic form of the inert state; the engine filters on it.
- **The user's word reaches the detectors.** `Detector.userSaysProgramme`
  is called on *Not an ad* and *Show's back*. The logo detector drops its
  absence and demands a fresh sighting; the transcript detector drops its
  held script. The engine also opens a quiet period (`NOT_AD_QUIET_S`,
  60 s) during which the decision is forced to "programme": the evidence is
  still shown, nothing acts on it.
- **A search window.** The ROI is slid over ±6 normalised pixels (stride 2)
  and the best correlation counts; the box therefore follows the bug in a
  hand-held frame instead of assuming calibration's pixel.
- **Whole TV or nothing.** `Vision.screenComplete`: a screen box that
  touches the frame edge (2 px margin) or whose aspect is outside 1.15–2.6
  is a partial or false screen; the detector is inert with reason
  `partial_screen`, and the finder does not learn from such frames.
- **Captions are a `TranscriptDetector` named "captions".** `CaptionSource`
  cuts the lower 35 % of the found (whole) screen out of each frame, scales
  it so a caption row is about 20 px tall, and runs ML Kit's bundled Latin
  text recogniser once a second. Only words new since the previous reading
  are fed (captions scroll: the new line's head is the old line's tail).
  The words carry media time and enter the same three-hour history,
  `RepeatLearner` and `TranscriptMatcher` as speech, in a second detector
  instance that shares the script store; both carry the logo weight.
- **Methods are switches.** `Assembly.engine` takes `silence`, `loudness`,
  `fingerprints` flags and the optional logo, transcript and captions
  detectors, and refuses an empty set; the app refuses to start with no
  method on or a blank IP address.

## Consequences

- A template that never matches can no longer duck the set; it shows
  "looking for the bug" in the status line instead, which is the cue to
  redo the setup.
- Captions cannot arrive by cable: Sharp's RS-232C and IP protocols carry
  commands and one-word replies only, and infrared is one-way. Reading them
  off the picture is the only path from outside the set and needs the
  whole TV in view; at eight feet it needs the camera zoomed.
- The Python core does not gain captions or the inert-vote generalisation
  in this release; the Kotlin core is ahead on the camera-and-phone paths
  that only exist there.
