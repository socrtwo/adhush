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

## Amendment (0.28.1, 2026-09-19)

The first evening at a news channel showed the three additions feeding on
polluted memories. A taught break that ran into the show, two mis-taps of
ten and forty seconds, and a jingle "opener" cut from the show's own bed
fed each other: remembered breaks matched the show; a closer learned from
the show said *programme present* three seconds into every duck; and each
three-second duck was "a real break" that taught more openers, which the
hour-of-day rule then trusted after two. The set ducked for seconds every
ten to twenty seconds.

Four rules, in both cores, all default-on:

- The jingle learner ignores a break shorter than `min_break_s` (20 s).
- Two hearings of a sting closer together than `hit_spacing_s` (30 min,
  by the wall clock) count as one hit: one session cannot promote it. Each
  jingle keeps the wall time of its last counted hit (TSV column 9).
- A taught break shorter than `min_material_s` (30 s) is a slip of the
  finger and teaches nothing; longer than `max_material_s` (6 min) it is
  cut there, since the viewer forgot to press Show's back.
- A remembered break whose duck ends sooner than `false_match_s` (10 s)
  was ended by the show's own evidence: a false match. `false_match_limit`
  (2) of them forget the record, as "Not an ad" does at once.

Family matching still counts a variant hearing as a hit for the sting it
resembles; the spacing rule, not the family rule, is what stops one
evening's bed music from becoming a jingle. The phone also gained a
*Forget what it learned* button, because a bad evening is cheaper to erase
than to unlearn.
