# ios front end

Pattern: **networked core** (ADR 0002). iOS has no Python runtime and no IR
hardware; in this release the phone is a remote control and status display.

- Run the core on a Pi or PC with `[ipc] enabled = true` and a `token`, bound
  to the LAN address.
- In Safari open `http://<core-host>:8675/`, then Share → **Add to Home
  Screen**. It launches full-screen with live PROGRAM / MUTED, ✓ ✗, and ■.
- With a token set the page polls `/status` every 2 s (Safari's `EventSource`
  cannot send the header). Safari has no document Picture-in-Picture, so there
  is no floating mini window on iOS.
- On-device capture and control would need a native app. The Android design
  (`docs/android-app-design.md`, ADR 0007) is the template an iOS core would
  follow; none exists yet.
