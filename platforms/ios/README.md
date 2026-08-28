# ios front end

Pattern: **networked core** (ADR 0002). iOS has no Python runtime or IR
hardware; the device is a remote control and status display.

- **Thin client today**: open `../web/index.html` in Safari (serve it from
  any LAN host or the files app) pointed at the core's `[ipc]` address with
  a `token`. Add to Home Screen for an app-like feel.
- **Capture**: on another device (Pi/PC); on-device camera/mic capture would
  need a native app streaming to the core — future work.
- **Control**: through the core (network TV APIs, IR blaster, CEC).
