# macos front end

Run the Python core here; two UIs come with it.

```
pip install adhush-0.6.0-py3-none-any.whl     # or: pip install -e .
adhush run --config config/adhush.toml         # opens the mini window
adhush service install                         # keep it running: launchd agent
```

- **Mini window**: `adhush run` starts `adhush overlay` — a small, borderless,
  always-on-top, draggable pill (PROGRAM / SUSPECT / MUTED, ✗ ✓ ■). Standard
  library only; tkinter ships with python.org Python. `[ui] overlay = false` or `--no-overlay` disables it.
- **Web app**: with `[ipc] enabled = true` the core serves it at
  `http://127.0.0.1:8675/` — and the ▣ button there opens a Chrome/Edge
  Picture-in-Picture mini window too.
- **Capture**: `screen` (avfoundation; grant Screen Recording), `camera`, or `microphone` (`audio_device = "avfoundation::0"`).
- **Control**: `local_audio` (osascript), `ir_blaster_net`, or `network_ip`.
- **Stopping**: ■ on either UI sends `shutdown`; the service does not restart a
  clean exit. `adhush service uninstall` removes the login entry.
