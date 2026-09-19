# ADR 0015 — Two AI judges as methods seven and eight; the news ticker instead of the bug

Status: Accepted. 2026-09-15.

## Context

The word-based methods (ADR 0012, 0013) recognise commercials they have
heard before, or stock legal phrases. The owner asked for a method that
judges *any* stretch of speech — "listen to MSNOW and ask a cheap Claude
whether this is content or a commercial" — and, for a new phone, the same
with a language model running on the device. Separately, on news channels
the corner bug is small and translucent while the lower-third ticker is a
large, high-contrast band that is present throughout programming and gone
during commercials.

## Decision

- **One judge detector, two brains.** `JudgeDetector` (core) collects the
  words the speech and caption detectors already produce, and on a cadence
  hands the last `windowS` (40 s) to a `TranscriptJudge` on a thread the
  app supplies. The answer is one line, `COMMERCIAL 0.9: why` or
  `SHOW 0.8: why` (`Verdict.parse` is tolerant of prose and JSON). A
  commercial answer is a vote at the logo weight held for `holdS` (15 s);
  the user's "Not an ad" drops it. The default cadence is **tie-breaker**:
  ask only when fusion is in the grey zone (0.2–0.72) or the set is
  ducked, and otherwise at most once a minute; "always" asks every 10 s.
  A confident commercial over 15+ words is saved to the shared script
  store (deduplicated through `TranscriptMatcher.identify`) and the script
  detectors reload, so the next airing is recognised offline for free.
- **Method 7, `judge_claude`:** the official Anthropic Java SDK
  (`com.anthropic:anthropic-java`), one `messages.create` per question with
  the instruction as a cached system block and `max_tokens` 64. Haiku 4.5
  by default; Sonnet 5 and Opus 5 selectable. Opt-in, with the API key in
  the encrypted settings; text only ever leaves the phone, never audio.
- **Method 8, `judge_local`:** MediaPipe's LLM Inference task
  (`com.google.mediapipe:tasks-genai`) running Qwen2.5-0.5B-Instruct
  (8-bit, ~550 MB, Apache-2.0, LiteRT community build, no login) from
  app-private storage; the Qwen chat template is applied in the prompt;
  the download resumes with a `Range` request. Gemma 3 1B would be the
  better small model but is gated behind a Hugging Face login, which an
  app cannot satisfy.
- **The ticker.** `LogoFinder.bandResult` finds rows in the bottom 40 % of
  the normalised screen where a strong edge persists across at least half
  the width — the band's top and bottom lines — and builds a full-width
  template between them. The existing `LogoAbsenceDetector` runs on it
  under the name `ticker_absence` with a 4-px search window; the same
  sighting, whole-screen and stale rules apply. Camera setup gains a
  target choice and saves to `ticker.tsv`.
- **Speech model choice.** The Vosk medium model
  (`vosk-model-en-us-0.22-lgraph`, 128 MB) is offered next to the small one,
  because the judges are only as good as the words they read.

## Consequences

- Both judges need words: without speech or captions on, the app says so
  and they sit idle.
- Cost of method 7 at Haiku rates: about $0.0004 a call, ~$0.13 an hour in
  "always" mode, a tenth of that as a tie-breaker. Method 8 costs CPU and
  battery instead.
- The judges are late by nature (utterance finalisation + window + answer):
  they start ducks well; the unmute is better left to the bug, the ticker
  or a remembered break.
- The Pi listener has no speech recogniser yet, so neither judge runs
  there; that is the next port (Vosk has a Python package and Pi wheels).
