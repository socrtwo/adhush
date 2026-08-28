# linux-pi front end (reference platform)

The core runs natively here; no separate front end is required — the CLI is
the interface, and the web page in `../web/` adds remote control.

```sh
adhush probe                 # pick a control path (RS-232 / IR / CEC / network)
adhush calibrate             # logo template, with the logo on screen
adhush run                   # HDMI-UVC capture + chosen controller
```

- **Capture**: `hdmi_uvc` (splitter → UVC dongle) or `line_in` for audio-only.
- **Control**: any backend; `ir_pigpio` needs `pigpiod`, `ir_lirc` needs LIRC,
  `rs232_sharp` needs pyserial (`pip install -e .[pi]`).
- **Remote UI**: set `[ipc] enabled = true`, then open `../web/index.html` on
  any device on the LAN (bind `host` to the Pi's address and set a `token`).

See `docs/hardware-pi4.md` for wiring.
