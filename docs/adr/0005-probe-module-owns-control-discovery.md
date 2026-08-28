# 5. Probe module owns control-path discovery

Date: 2026-08-28

## Status

Accepted

## Context

Phase 3 ships six control backends. A user setting up an unfamiliar set needs
to know which of them can actually drive their hardware before trusting the
system to mute at 2 a.m. That discovery logic — is the binary installed, does
the device node exist, does the blaster answer TCP, are the profile's codes
complete and encodable — belongs to the control layer, but no scaffolded
module's docstring covers it: `control/base.py` is the controller contract,
and the backend modules each own exactly one transport.

## Decision

Add `src/adhush/control/probe.py` with exactly that responsibility: cheap,
side-effect-free availability checks per backend, reported in the device
profile's preference order (docs/device-support.md) with each backend's
discrete-mute capability. Probing never transmits a mute; the CLI's
`adhush probe --active` drives an actual mute/unmute through the real
controller separately, so the safe path stays safe. The probe's environment
(PATH lookup, filesystem, TCP connect, import checks) is injectable for
tests.

Alongside it, the control registry (`control/__init__.py`) gains
`resolve_options`: profile-supplied backend settings (including the shared
`[ir]` section for IR transports) merged under the main config's
`[control.<backend>]` overrides. This keeps device specifics in profiles —
per CLAUDE.md — while probe and registry construct controllers from one
resolved view.

## Consequences

- `adhush probe` can rank working control paths on any machine without
  touching the TV.
- New backends add one `_probe_one` branch next to their registry entry.
- Profiles stay the single home for device-specific codes and endpoints.
