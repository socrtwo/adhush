# ADR 0022 — The set-up wizard, and a wording audit

Status: Accepted. 2026-09-16.

## Context

The phone app has ten methods, three connections, a duck level, a camera
zoom, two AI judges in three sizes and a room that is different in every
house. The right settings depend on numbers nobody knows without measuring:
how far the show sits above the fans, whether the camera can see the whole
set from where the phone lives, how much memory the phone has. The Room
survey (ten minutes) measures the first of those but leaves the reading to
the owner. The owner asked for an optional wizard that asks a few
questions, tests the microphone and the camera, suggests a configuration,
applies it, and lands on the page where it can be changed.

The same request asked for a review of the app's text: one release had
said "six" or "seven" methods when there were eight or nine.

## Decision

`SetupWizardActivity`, seven steps, offered once on first run (*Not now*
keeps the defaults) and always available from the Home page:

1. **Welcome** — the show on at normal volume, the phone where it will live.
2. **Your TV** — network, serial or infrared; for the network, address and
   login, and *Ask the TV its volume* opens a `SocketTransport` and sends
   `VOLM?`. The answer becomes the Normal volume.
3. **Your room** — where the phone sits (facing the set, or not), the
   channel, whether it is a news channel with a ticker, whether captions
   are shown.
4. **Listen: the show** — fifteen seconds through `MicSource`: dBFS,
   spectral flatness and crest factor per block.
5. **Listen: the room** — ten seconds with the set muted: the room's own
   level and flatness.
6. **The camera** — eight seconds through `CameraSource` with the preview:
   how often `Vision.findScreen` finds a lit screen, whether
   `screenComplete` holds, and how wide the set is in the picture.
7. **Suggested set-up** — every decision with its reason, then *Apply and
   review* writes `Settings` and opens the Methods page through
   `MainActivity.EXTRA_PAGE`, where every switch is editable. A running
   `MainActivity` reloads its fields in `onNewIntent`.

The rules behind the suggestion (`makePlan`), all from measurements the
app already understands:

- **Contrast** = show median dBFS − room median dBFS. ≥ 20 dB: quiet gaps
  and loudness on, duck to 4. 10–20: both on, duck to 6. 6–10: quiet gaps
  off (the room fills the gaps), duck to 9 so loudness can still hear the
  ducked set and time the unmute (ADR 0016). < 6: quiet gaps off, duck to
  10, and a follow-up to move the phone.
- **Steady room noise** (room median > −50 dBFS and flatness ≥ 0.2) is
  named, since it is what buries a ducked set.
- **Camera** on when a screen was found in at least half the looks and
  complete in 70 % of those; ticker instead of bug on a news channel;
  zoom so the set fills about 80 % of the picture, capped at 4× and the
  lens's range. Captions on when they are shown and the set is at least
  35 % of the picture wide (or zoomed). A missing template becomes the
  follow-up "Camera setup → Watch 45 s".
- **Speech** on when its model is installed and contrast ≥ 10 dB.
- **Local AI** size from `ActivityManager.totalMem`: ≥ 11 GB large,
  ≥ 5.5 GB medium, else small; on only when installed and a words method
  is on. **Claude** on as a tie-breaker when a key is stored and a words
  method is on.
- Remembered breaks, the break clock and jingles are always on: they cost
  nothing and learn by themselves.

The wizard keeps numbers only. No audio, no picture is stored; the camera
preview is shown and dropped. The service is stopped while it runs, as
Camera setup does, because two things cannot hold the microphone or the
camera.

### The wording audit

Every place that counts the methods now says ten: the Help "Choosing
methods" topic (it said eight and omitted the clock and the jingle from the
strong/weak lists), the Help file's header comment (six), the Android
README's screens section (six), and the local-AI print guide (eight, HTML
and PDF regenerated). The Python README says seven detectors plus the
clock and the jingle, which is the Python count. Method numbering in the
app (1–10) matches the Android README table.

## Consequences

- A first run starts in the wizard; nothing changes for an existing
  install until the owner opens it.
- The wizard's thresholds are the ADR 0016 posture written down as
  numbers; if the room survey's verdicts change, these should follow.
- The wizard does not replace Camera setup: it decides *whether* the
  camera is worth switching on and points at Camera setup for the template.
