# Changelog

All notable changes to this project are documented here.
Format follows Keep a Changelog; versioning follows SemVer.

## [Unreleased]

## [0.2.0] - 2026-08-28
### Added
- Phase 2 (vision and memory) implementation:
  - `logo_absence` detector: edge-template calibration via `adhush calibrate`
    (live or from a recording), Pearson-correlation presence scoring, absence
    runs voting AD, and a positive `program_present` signal.
  - `scene_cut` detector: shot-change rate mapped to confidence between
    configurable cuts-per-minute bounds.
  - Fingerprint subsystem: DCT perceptual hash (`video_phash`), chroma-bit
    audio fingerprint (`audio_chroma`), SQLite store with TTL pruning,
    vectorized Hamming matcher with consecutive-hit confirmation and audio
    corroboration, and a learner with duration averaging, duplicate
    detection, and slot snapping (15/30/45/60 s) until enough airings agree.
  - `fingerprint` detector: samples frames (flat frames gated out), keeps
    rolling hash/chroma buffers, and confirms known ads.
  - State machine promotion: a confirmed fingerprint hit jumps straight to
    AD with no dwell; inside the matched window only sustained positive
    program evidence (the logo back on screen) unmutes early, and the
    max-mute ceiling still wins.
  - Engine: promotion wiring, learned-duration windows, learning
    fusion-confirmed segments on unmute, and duration updates from each
    airing (an early unmute shortens the stored duration).
  - CLI: `calibrate` and `learn` implemented; `replay --config` now runs the
    full pipeline including the fingerprint store.
- Config: `[detect.logo_absence]` (ROI defaulting from the device profile,
  template path), `[detect.scene_cut]`, expanded `[fingerprint]` options,
  and `fusion.fp_unmute_dwell_ms`.

### Changed
- An uncalibrated `logo_absence` is excluded from fusion's enabled mass
  instead of diluting it with permanent zero votes.

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
