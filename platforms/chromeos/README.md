# chromeos front end

Runs inside the Linux container (Crostini), which sees the desktop through
the same paths as Linux.

```sh
pip install -e .
adhush run
```

- **Capture**: `screen` (x11grab inside the container; Android-app windows
  may be excluded by the compositor — `camera`/`microphone` are the
  fallback), `camera`, or `microphone`.
- **Control**: `local_audio` (pactl via cros-pulse), `ir_blaster_net`,
  `network_ip`.
- **UI**: the browser is native here — enable `[ipc]` and open
  `../web/index.html`, or install it as a kiosk tab.
