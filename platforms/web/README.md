# web front end

`index.html` is the complete front end: a dependency-free page over the
core's HTTP+SSE API (`src/adhush/ipc/api.py`, ADR 0006). **The core serves it**
— with `[ipc] enabled = true`, open `http://<core-host>:8675/` from any device
on the network. Nothing to copy, nothing to install.

- **Installable**: `manifest.webmanifest` + `sw.js` + icons make it a home-screen
  app on Android (Chrome: *Add to Home screen*), iOS (Safari: Share → *Add to
  Home Screen*), ChromeOS and desktop browsers. The service worker caches only
  the shell; `/status`, `/events`, `/command` are never cached.
- **Mini window**: the ▣ button opens a Document Picture-in-Picture window in
  Chromium 116+ — a real always-on-top floating pill with ✗ / ✓ / ■. Other
  browsers get a small popup (above this tab, not above other apps); the
  desktop overlay (`adhush overlay`) is the always-on-top answer there.
- **■ Stop AdHush** sends the `shutdown` command (ADR 0008).
- Opening the file directly (`file://`) still works for the controls; the
  install prompt and service worker need http(s).
- With a `token` configured the page polls `/status` (a browser `EventSource`
  cannot send headers); keep tokenless use on loopback only.
- In-browser *capture* (tab capture + WASM detectors) remains future work.
