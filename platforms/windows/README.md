# windows front end

Run the Python core directly; the web page is the UI.

```powershell
pip install -e .
adhush run --config config\adhush.toml
```

- **Capture**: `screen` (gdigrab of the desktop while a streaming app plays),
  `camera` (`device = "Integrated Camera"` via dshow), or `microphone`
  (`audio_device = "dshow:audio=Microphone (…)"`; audio-only detector set).
- **Control**: `local_audio` (mutes the PC; needs `nircmd` on PATH),
  `ir_blaster_net`, or `network_ip` for a smart TV.
- **UI**: enable `[ipc]` and open `..\web\index.html`.
