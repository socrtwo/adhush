# 4. Engine module owns pipeline wiring

Date: 2026-08-28

## Status

Accepted

## Context

Phase 1 makes the pipeline real: capture feeds detectors, fusion combines
votes, the state machine decides, a controller acts. That wiring has to live
somewhere. `cli.py`'s responsibility is argument parsing and subcommands;
`detect/fusion.py` combines votes and must not know about capture or
controllers; detectors must never call a controller (CLAUDE.md). No scaffolded
module's docstring covers "drive the loop".

## Decision

Add `src/adhush/engine.py` with exactly that responsibility: merge capture
events, feed detectors, invoke fusion and the state machine at a fixed decision
cadence, and dispatch resulting actions to the controller. It owns both the
deterministic offline mode (timestamp-merged, used by CI and `adhush replay`)
and the live threaded mode (one decode thread, one audio thread, per the
concurrency section of docs/architecture.md). Because the engine produces the
transition log, it also owns scoring that log against labeled ground truth
(`evaluate_onsets`), reporting mute-onset and unmute-onset separately as
CLAUDE.md requires.

## Consequences

- `cli.py` stays thin: subcommands construct objects and call the engine.
- Detectors, fusion, state, and control remain unit-testable in isolation;
  end-to-end behavior is tested by driving the engine with `file_replay`.
- Later phases (fingerprint promotion to AD, IPC front ends) extend the engine
  rather than adding a second loop.
