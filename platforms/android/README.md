# android front end

Pattern: **networked core** (ADR 0002). The core runs on a Pi or PC on the
same LAN; the phone is a thin client.

- **Thin client today**: open `../web/index.html` (or serve it) in Chrome and
  point it at the core's `[ipc]` address; set a `token` on non-loopback binds.
- **Phone as sensor** (future): camera + mic capture on-device requires an
  embedded Python runtime or a native capture layer streaming to the core;
  tracked for a later phase. Termux users can already run the audio-only core
  (`capture.backend = "microphone"`) on-device.
- **Control**: the core drives the TV (IR blaster / network / CEC). Phones
  with a built-in IR emitter need a native shell — future work.
