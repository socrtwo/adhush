# chromeos front end

Two good options, and they combine.

**Core in the Linux container (Crostini).** Same as Linux:

```
pip install adhush-0.6.0-py3-none-any.whl
adhush run                     # [ipc] enabled; serves the web app
adhush service install         # systemd --user inside the container
```

- **Capture**: `screen` (x11grab inside the container; Android-app windows may
  be excluded by the compositor — `camera` / `microphone` are the fallback).
- **Control**: `local_audio` (pactl via cros-pulse), `ir_blaster_net`, `network_ip`.

**The floating window: use Chrome's, not Tk's.** Open the served page
(`http://localhost:8675/`, or `penguin.linux.test:8675`) in Chrome and press
▣ **Mini window**: Document Picture-in-Picture gives a real always-on-top pill
above every ChromeOS window. The Tk overlay also runs, but inside the
container's own window layer. Install the page as an app from Chrome's menu
for a launcher icon.
