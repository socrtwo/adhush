# Changelog

All notable changes to this project are documented here.
Format follows Keep a Changelog; versioning follows SemVer.

## [Unreleased]

## [0.1.0] - 2026-08-28
### Added
- Phase 1 (Raspberry Pi reference) implementation:
  - Event types, TOML config loading with device-profile inheritance,
    ring buffers, image ops, dwell timers, and slot snapping.
  - Capture: deterministic `file_replay` (`.npz` fixtures and ffmpeg-decoded
    media) and live `hdmi_uvc` (V4L2 + ALSA via ffmpeg).
  - Detectors: `black_frame`, `silence` (spectral-flatness aware), and
    `loudness` (K-weighted short-term level vs. frozen-while-elevated program
    baseline), each voting confidence with machine-readable reasons.
  - Weighted fusion with Schmitt-trigger hysteresis; PROGRAM → SUSPECT_AD →
    AD → RECOVERY state machine with asymmetric dwell and a hard max-mute
    ceiling.
  - Controllers: `rs232_sharp` (AQUOS discrete mute with state readback) and
    `ir_lirc` (discrete or toggle via irsend).
  - Engine driving offline replay and live capture, with mute-onset /
    unmute-onset precision–recall evaluation (ADR 0004).
  - CLI: `run`, `replay` (with labeled-ground-truth scoring), `doctor`,
    `ir-test`.
- Test suite: unit tests per module plus labeled `file_replay` integration
  tests reporting mute-onset and unmute-onset scores separately.

### Changed
- Default loudness window shortened to 1.5 s so unmute latency stays under
  ~2 s; a late unmute is the worse failure.

## [0.0.1] - 2026-08-28
### Added
- Initial repository scaffold: module layout, interfaces, docs, config schemas.
