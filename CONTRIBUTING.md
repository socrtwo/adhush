# Contributing

## Adding device support
Most sets need no code. Copy `config/profiles/generic.example.toml`, fill in
make, model, control backends, IR codes, and logo ROI, then open a PR with a
`device_support` issue referenced. Include:

- Exact model string and year
- Which control paths you verified (IR / CEC / RS-232 / network)
- Whether discrete mute-on/off exists or only toggle
- Capture path used and the resulting logo ROI coordinates

## Code
- Python 3.11+, type-annotated, `ruff` + `mypy` clean.
- One module, one responsibility. See `CLAUDE.md`.
- New architectural decisions get an ADR in `docs/adr/`.
