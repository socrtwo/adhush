# Print-ready guides

`beginner-guide-5v.html` is the source for `AdHush-beginner-guide-5V.pdf` — the
illustrated, one-diagram-per-step edition of `docs/build-guide-beginner.md`,
wired for a **5 V** relay module (the kind shops actually stock) off the Pi's
5 V pin. Every diagram is inline SVG and the fonts are embedded, so the file is
self-contained and needs no network to render.

`beginner-guide-nespi4.html` / `AdHush-beginner-guide-NESPi4.pdf` is the same
guide re-cut for a Pi 4 B inside a Retroflag NESPi 4 case (`docs/build-guide-nespi4.md`):
an extra case-assembly step, relay power from a USB-A breakout because the case's
plug covers header pins 1–8, and one wire leaving the case.

`beginner-guide-listener-nespi4.html` / `AdHush-beginner-guide-listener-NESPi4.pdf`
is the **TV listener** (`docs/build-guide-tv-listener.md`) for a Pi 4 in the
NESPi 4 case: a webcam and a microphone on a shelf, the Sharp turned down
through its RS-232C socket by a USB null-modem cable (Wi-Fi as the
alternative), an optional SSD in the cartridge, nothing leaving the case but
that one cable. Same fonts and
page style; the case diagram is shared with the NESPi 4 box guide.

`beginner-guide-local-ai-oneplus.html` / `AdHush-guide-local-AI-OnePlus.pdf`
is the **Local AI** guide for the Android app (method 8) at about a
15-year-old's reading level: the three sizes of Qwen the app can download
(0.5B, 1.5B, Qwen 3 4B), what each needs, how to pick, download, test and
switch on a OnePlus Ace 6 Ultra, and what to do when it misbehaves.

`beginner-guide-stream-learning.html` / `AdHush-guide-stream-learning.pdf`
teaches the phone from a channel's live stream (Android 10+ playback
capture): what a stream can and cannot teach, starting it, teaching by hand,
moving the memory to another phone, and which streams work.

Regenerate any PDF with any headless Chromium:

```
chromium --headless --no-pdf-header-footer \
  --print-to-pdf=AdHush-beginner-guide-5V.pdf \
  file://$PWD/beginner-guide-5v.html
```

Page geometry (US Letter, margins, page breaks) lives in the HTML's `@page`
rule, so the PDF is reproducible from the source alone. Keep the wiring here in
step with `docs/build-guide-beginner.md` and
`config/adhush-passthrough.example.toml`.
