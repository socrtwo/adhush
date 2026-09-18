# ADR 0027 — Jingle families, segment stingers, and the hour of day

Status: Accepted. 2026-09-18.

## Context

The owner watches a news channel whose every segment opens and closes
with music and sound effects that are *related* — the same house style,
re-cut per show and per hour — but not identical. Method 10 (ADR 0020)
learns one sting per channel and recognises it by aligned chroma-bit
agreement, so a re-cut a semitone up, or a version played a little
slower under a different presenter, is a different candidate that must
open three breaks of its own before it votes. The segment *stingers* —
a whoosh or hit on the cut, with no tune to learn — never promote at all.
And the sting the morning show uses at nine is not the one the evening
show uses at nine at night, yet the detector treats every hour alike.

## Decision

Three additions, in both cores, none of them a new configuration step.

1. **Family matching** in the jingle detector. A stored sting is compared
   with the live audio as heard, and also under every transposition of up
   to `pitch_shifts` semitones either way (the twelve chroma bits rotate;
   pitch class *c* lives in bit 11 − *c*, so up is a rotate towards bit 0)
   and under a tempo stretch of ±`tempo_tolerance` (the stored blocks
   resampled by nearest index). A variant match is docked
   `family_penalty` before it is compared with the threshold, so the sting
   as heard always wins a tie and a chance match under a variant is rarer
   than one under the identity. A variant hit counts for the jingle it
   resembles, in learning and live: one memory covers the family. The vote
   reason names the variant (`family=+1st/1.10x`). Defaults ±2 semitones,
   ±10 % tempo, penalty 0.05.

2. **A stinger detector** (`stinger`, Python `detect/stinger.py`, Kotlin
   `Stinger.kt`), default weight, inert until it fires. Per 100 ms block
   it measures level and spectral flatness. A *burst* is up to
   `max_burst_s` of blocks that are noisy rather than tonal (flatness ≥
   `min_flatness`) and `burst_above_db` louder than the median of the two
   seconds before. It is a stinger when the second after it sits
   `level_step_db` away from the bed before it, or the burst itself was
   `loud_burst_db` above. Applause and traffic fail the length; a plain
   level step without a burst is loudness's business. The vote is 1.0 at
   confirmation and decays to nothing over `hold_s`, so it tips a balance
   the other detectors already lean towards and never ducks alone. It goes
   quiet for `duck_guard_s` after our own volume change (ADR 0016). On the
   phone it rides on the Loudness method, as ad units ride on Quiet gaps.

3. **The hour of day.** Every jingle remembers the local hours it opened
   (or closed) a break in, from the engine's wall clock at the start of the
   break — a replay has none and learns none. Heard again at one of those
   hours it is trusted a break early (two openings instead of three) and
   matched `hour_bonus` more loosely. The TSV gains an eighth column,
   `hours` (`# adhush jingles v2`); v1 rows load without it, and the shared
   memory carries the hours between phones.

## Consequences

- One more default detector (`stinger`), so the replay pins and the
  Android method-toggle test change; the example configs list it.
- Fifteen variant comparisons per promoted jingle per half second: a few
  thousand integer operations, well inside the budget.
- `JingleDetector.learn_break` takes an optional `wall_start`; the engine
  passes the wall time it already keeps for the break clock. Kotlin
  `learnBreak(start, end, wallStart)` likewise, and the engine ticks the
  jingle detector with the wall clock as it ticks the break clock.
- A sting learned at nine in the morning that is also the nine-at-night
  sting simply collects both hours; the mechanism only ever admits a
  jingle earlier, never later.
