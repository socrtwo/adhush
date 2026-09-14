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
NESPi 4 case: a webcam and a microphone on a shelf, the Sharp turned down over
Wi-Fi, nothing wired to the TV and nothing leaving the case. Same fonts and
page style; the case diagram is shared with the NESPi 4 box guide.

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
