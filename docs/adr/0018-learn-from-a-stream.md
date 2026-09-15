# ADR 0018 — Learn the commercials from a stream; a memory that moves between phones

Status: Accepted. 2026-09-15.

## Context

The owner bought a month of MS NOW's live stream and asked whether its
commercials could be sampled as examples, in the background, for any
channel that streams live with commercials, with no commands to type.

A channel's live stream carries the network's own national spots as the
same recordings cable plays; the local avails are filled differently
(dynamic ad insertion on the stream, the cable company's local ads on the
set). The stream runs tens of seconds behind cable, so it can teach but
cannot be the live sensor. Two places could listen: a PC running the
Python core with a loopback audio device, or the phone hearing its own
playback. The Python core's fingerprint matcher is video-first (frame
hashes from the camera, audio only to corroborate), so a memory made from
sound alone means nothing to it today; the phone's matcher is audio-only,
and the phone is where the owner's ducking happens.

## Decision

- **Stream learning on the phone.** A `StreamSource` captures the phone's
  own playback through Android's `AudioPlaybackCapture` (Android 10+, the
  "record or cast" consent from `MediaProjectionManager`), in the same
  48 kHz mono float blocks as the microphone. The service runs the usual
  engine on it with a `VirtualController` (no TV), fingerprints forced on,
  speech and the AI judges as configured, the camera and the break clock
  off (the delay would shift every minute). Every break the engine ends
  is learned exactly as at the TV; ✓ / ▶ on the notification teach by
  hand. The notification reads `STREAM LEARNING · m:ss listened · +n
  breaks · +m scripts`. Thirty seconds of digital silence means the player
  blocks capture, and the status says so. The foreground service declares
  the `mediaProjection` type for this mode only.
- **One memory.** Stream learning writes to the same `ads.tsv`,
  `scripts.tsv` and (never, see above) `clock.tsv` the TV mode reads, so
  nothing is exported or imported between the two modes.
- **A memory that moves.** `Memory.export` zips the three files as
  `adhush-memory.zip` for sharing; `Memory.import` merges a zip into this
  phone's files: breaks whose block sequence is already known and scripts
  whose words are already known are skipped, the clock counters are
  summed. Import needs the service stopped, because stores are read at
  start.
- **Not the Pi.** The Python listener gains nothing until it has an
  audio-only matcher like the phone's (`AudioMatcher`); that port is the
  next candidate on the roadmap, and this ADR does not pretend the zip is
  useful there.

## Consequences

- The phone can arrive at the TV already knowing the channel's national
  spots, learned from clean audio; local spots are still learned at the TV.
- Playback capture depends on the player allowing it: Chrome does,
  some apps do not. The guide says to use the web player.
- The Moto's memory can move to the OnePlus and back.
