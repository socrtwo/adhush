# macos front end

Run the Python core directly; the web page is the UI.

```sh
pip install -e .
adhush run
```

- **Capture**: `screen` (avfoundation screen device; grant Screen Recording
  permission; `device` = the screen's avfoundation index), `camera`
  (`device` = camera index), or `microphone`
  (`audio_device = "avfoundation::0"`).
- **Control**: `local_audio` (osascript system mute, with readback),
  `ir_blaster_net`, or `network_ip`.
- **UI**: enable `[ipc]` and open `../web/index.html`.
