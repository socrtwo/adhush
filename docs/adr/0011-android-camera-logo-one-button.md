# ADR 0011 — Android: the back camera watches for the network bug, set up with one button

Status: Accepted. 2026-09-12.

## Context

The owner's phone already sits with its back camera facing the TV. The
passthrough box has had a logo-absence detector since Phase 2, but it needs a
region drawn by hand and a calibration run; ADR 0007 left video out of the
phone app for simplicity. The owner asked for the detector on the phone with
no configuration beyond a button, and asked why a database of network logos
could not replace calibration.

## Decision

- **Frames.** CameraX ImageAnalysis, back camera, two luma frames a second
  at 640 × 480, stamped with the microphone's media time so every detector
  shares one clock. The lit screen is found in each frame by the same
  bright-rectangle rule as `capture/camera.py`; the screen is normalised to
  320 × 180 so the phone may sit anywhere the whole screen is in view.
- **One button.** `LogoFinder` watches 45 s of programme and keeps, per pixel
  of the normalised screen, how often a strong edge sits there. Content
  moves; the bug's edges do not. The corner with the most persistently edged
  pixels is the bug's, a tight box around them is the ROI, and the mean edge
  map inside it is the template — what `adhush calibrate` builds, without a
  hand-drawn box. Nothing persistent enough means "no logo found" and no
  change.
- **Detector.** `LogoAbsenceDetector` ports `detect/logo_absence.py`:
  Pearson correlation of the ROI's edge map with the template, smoothed;
  below 0.4 counts as absent; absence ramps the vote over 1.5 s; presence
  zeroes it at once. With no screen in view it is inert — it casts no vote
  and leaves the fusion normaliser alone.
- **Weight.** Three default weights: absence alone mutes after the dwell,
  and presence vetoes an audio-only duck. Presence also ends a fingerprint
  hold early (programme evidence); a teach-mode hold ignores it.
- **No logo database.** What is on air is not the brand's logo file: it is a
  small, translucent, often monochrome variant that differs per show and
  changes with rebrands (MSNBC became MS NOW in 2025), seen through this
  camera's optics, glare and moiré. A template learned through the same
  camera in the same room is both more accurate and zero-maintenance. A
  database could later *name* the channel; it should not decide anything.

## Consequences

- A camera foreground service type joins the microphone one; the app asks
  for the camera permission only when the box is ticked.
- Battery and warmth rise modestly; two small frames a second is far below
  video recording.
- Anything that hides the bug during a show — a full-screen graphic, a
  sponsor card — reads as an ad; *Not an ad* is the correction. Network
  promos have no bug and are muted, which is usually wanted.
- The phone must stay put; if it is moved the screen is re-found every 5 s,
  but the calibration assumes the same orientation.
