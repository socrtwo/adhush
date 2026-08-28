# Roadmap

## Phase 0 — scaffold (done)
Structure, interfaces, docs, config schema, CI.

## Phase 1 — Raspberry Pi reference (done)
`file_replay` + `hdmi_uvc` capture, `black_frame`, `silence`, `loudness`,
`fusion`, `state`, `ir_lirc` and `rs232_sharp` controllers, CLI `run`/`doctor`.

## Phase 2 — vision and memory (done)
`logo_absence` with `calibrate`, `scene_cut`, `video_phash`, `audio_chroma`,
`store`, `matcher`, `learner`.

## Phase 3 — breadth of control (done)
`cec`, `ir_pigpio`, `ir_blaster_net`, `network_ip`, `probe`, profile library.

## Phase 4 — platforms (done: core surface + desktop/web; mobile = thin client)
Shared core exposed over `ipc/api.py`; thin front ends per platform.
On-device mobile capture and in-browser WASM detectors remain future work;
phones and tablets currently use the web front end against a networked core.

| Platform | Capture | Control | Notes |
|---|---|---|---|
| Linux / Pi | HDMI UVC, line-in | IR, CEC, RS-232, network | reference |
| Windows | screen, camera, capture card | net IR blaster, local audio | |
| macOS | screen, camera | net IR blaster, local audio | |
| Linux desktop | screen, camera | net IR blaster, local audio | |
| ChromeOS | screen, camera | net IR blaster, local audio | PWA or Linux container |
| Android | camera, mic, on-device IR where present | IR, net blaster, local audio | |
| iOS | camera, mic | net blaster, local audio | no IR hardware |
| Web | mic, camera, tab capture | net blaster, local audio | WASM detectors |

## Phase 5 — hardware passthrough box (done)
Pi-based inline HDMI unit with audio interception, no TV cooperation needed.
`relay_hdmi` control, the `passthrough-box` profile, wiring in
`docs/hardware-passthrough-box.md`, and `scripts/install-pi.sh` for the
systemd deployment.

## Beyond the roadmap
Candidates, in no particular order: on-device mobile capture and in-browser
WASM detectors (the Phase 4 remainder), an audio delay line for retroactive
mutes on the passthrough box, `metrics.py` (Prometheus text endpoint), RC-6 /
Kaseikyo IR encoders, an LG webOS websocket backend, and profile
contributions per docs/device-support.md.
