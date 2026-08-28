# Architecture

    capture ──▶ frame/audio bus ──▶ detectors (parallel) ──▶ fusion ──▶ state
                                          │                              │
                                    fingerprint store ◀── learner        ▼
                                                              mute controller

## Layers

**capture** produces a normalized stream of `FrameEvent` and `AudioEvent`,
regardless of source. All backends expose the same `caps()` so detectors can
disable themselves when a required modality is missing (audio-only mode
disables every video detector automatically).

**detect** is a set of independent plugins. Each returns a `DetectorVote` with a
confidence in [0,1] and a reason string. Detectors are stateless across
sessions except for rolling baselines they own.

**fusion** combines votes using per-profile weights and produces a
`MuteDecision`. It applies hysteresis: entering AD requires higher combined
confidence and longer dwell than leaving it. Asymmetry is deliberate — a missed
mute is an annoyance, a stuck mute is a defect.

**state** runs PROGRAM -> SUSPECT_AD -> AD -> RECOVERY with timers. A known
fingerprint hit can jump straight to AD with its learned duration.

**control** abstracts the mute path. Controllers declare whether they support
discrete on/off and whether they can read state back. Toggle-only IR paths get
a verification strategy (audio-level confirmation via the capture stream).

**devices** holds profiles: data files describing a make/model's control
backends, IR codes, logo ROI defaults, and quirks.

## Concurrency
One decode thread, one audio thread, a detector thread pool, and the control
loop. Frames are shared read-only; no detector copies full-resolution frames.
