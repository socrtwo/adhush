# android front end

Two supported modes.

## Standalone, on-device (designed; see `docs/android-app-design.md`)

For a TV that accepts network control — the Sharp LC-46LE830U is the reference —
the phone does the whole job with no hardware: microphone capture, the
detector/fusion/state-machine stack ported to Kotlin, and AQUOS commands
straight to the set over Wi-Fi. It **ducks with `VOLM` rather than muting**, so
the mic keeps hearing the TV through the break. ADR 0007 records the decision to
run a second, on-device implementation of the core for this.

Accuracy is structurally below the passthrough box (three audio detectors
versus six audio+video). It is the convenience build.

## Thin client (works today)

Pattern: **networked core** (ADR 0002). The core runs on a Pi or PC on the same
LAN; the phone is a thin client. Preferred whenever a passthrough box or IR is
in play, and the only option for a TV without network control.

- Open `../web/index.html` (or serve it) in Chrome and point it at the core's
  `[ipc]` address; set a `token` on non-loopback binds.
- **Control**: the core drives the TV (IR blaster / network / CEC). Phones with
  a built-in IR emitter need a native shell — future work.
- Termux users can already run the audio-only core on-device today
  (`capture.backend = "microphone"`), which is the crude version of what the
  standalone app does properly.
