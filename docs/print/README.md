# Print-ready guides

`beginner-guide-5v.html` is the source for `AdHush-beginner-guide-5V.pdf` — the
illustrated, one-diagram-per-step edition of `docs/build-guide-beginner.md`,
wired for a **5 V** relay module (the kind shops actually stock) off the Pi's
5 V pin. Every diagram is inline SVG and the fonts are embedded, so the file is
self-contained and needs no network to render.

Regenerate the PDF with any headless Chromium:

```
chromium --headless --no-pdf-header-footer \
  --print-to-pdf=AdHush-beginner-guide-5V.pdf \
  file://$PWD/beginner-guide-5v.html
```

Page geometry (US Letter, margins, page breaks) lives in the HTML's `@page`
rule, so the PDF is reproducible from the source alone. Keep the wiring here in
step with `docs/build-guide-beginner.md` and
`config/adhush-passthrough.example.toml`.
