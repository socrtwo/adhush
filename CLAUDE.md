# Working agreement for AI coding sessions

## Ground rules
- This repo is a scaffold. Implement modules in the order given in
  `docs/roadmap.md`; do not scatter partial work across all layers at once.
- Never widen a module's responsibility beyond its docstring. If a change needs
  a new responsibility, add a module and an ADR under `docs/adr/`.
- Every detector implements `detect/base.py:Detector` and returns a confidence
  vote with a machine-readable reason string. No detector calls a controller.
- Every controller implements `control/base.py:MuteController`. Controllers
  never inspect frames.
- Device-specific behavior belongs in `config/profiles/*.toml`, not in Python.

## Performance budget (Raspberry Pi 4, 2 GB)
- Sustained: 720p30 decode + all enabled detectors under 60% of one core each
  for video, one for audio. Resident set under 700 MB.
- Downscale before hashing. Detectors operate on a shared decoded frame; no
  detector re-decodes.
- Ring buffers sized in `util/ringbuffer.py` from config, never hardcoded.

## Testing
- Every detector needs a `file_replay` fixture test with labeled ground truth.
- Report precision/recall separately for mute-onset and unmute-onset. A late
  unmute is a worse failure than a late mute.

## Out of scope
- No circumvention of content protection on any capture path.
- No upload of captured audio or video off-device by default.
