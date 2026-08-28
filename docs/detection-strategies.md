# Detection strategies

## Logo absence
Broadcast networks keep a persistent bug, usually lower right, that disappears
during national ad pods. Strong on broadcast, useless on many cable networks
that keep the bug through ads, and useless on most streaming. Weight per
profile. ROI is calibrated once via `adhush calibrate`.

## Loudness
Ads are mixed hot. Track short-term LUFS against a rolling program baseline and
vote on sustained positive delta. Modern loudness regulation and the set's own
DRC weaken this; treat it as corroborating, not decisive.

## Black frame
Pod boundaries usually carry a black frame run. Cheap, precise, low recall —
excellent as a boundary refiner once another detector has raised suspicion.

## Silence
Same role as black frame, on the audio side. Must be distinguished from quiet
dialogue by duration and spectral flatness.

## Scene cut rate
Ads cut far faster than program content. A rolling shot-length estimate is a
good soft signal, especially where logo detection fails.

## Aspect change
Letterbox to full-frame transitions mark many pod boundaries.

## Caption gap
Discontinuity in the CC stream is a reliable corroborator where captions exist.

## Fingerprint matching (repeat-ad recognition)
1. When a segment is confirmed as an ad, hash frames from its first seconds:
   downscale, crop letterboxing, compute a perceptual hash per sampled frame.
   Compute a parallel audio fingerprint over the same window.
2. Store `ad_id`, the hash set, and the observed duration in the SQLite store.
3. On every subsequent frame, query the matcher. A hit within the Hamming
   threshold on video plus audio corroboration promotes straight to AD.
4. Mute for the stored duration, snapped to the nearest 15/30/45/60s slot.
5. Keep detectors live during the muted window. Any strong PROGRAM signal
   unmutes early and shortens the stored duration for that ad.

Snapping to slots is a prior, not a guarantee — regional insertions and
15+15 pairs break it. The learned duration always wins over the snap when the
sample count for an ad is high enough.

## Fusion
Weights live in the device profile. Default posture: no single detector may
trigger a mute alone except a high-confidence fingerprint hit.
