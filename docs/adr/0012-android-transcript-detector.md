# ADR 0012 — Android: the fifth detector hears the words

Status: Accepted. 2026-09-14.

## Context

The owner asked for a detector that listens to the audio for an hour or
indefinitely, identifies the commercials in the transcript, and afterwards
ducks the set when the same words are heard. The earlier assessment (a
brand-name list would mute the news; no public script database exists)
still holds; this is the version that works around both.

## Decision

- **Recognition on the phone.** Vosk with its small English model (about
  40 MB, downloaded once into app-private storage). The microphone's 48 kHz
  blocks are averaged to 16 kHz; finished utterances yield words with media
  time stamps. Nothing leaves the phone.
- **Commercials are found by repetition.** A spot airs several times an
  hour and its words recur verbatim; programme speech never repeats a run
  of ten words. `RepeatLearner` indexes every 4-word key with its time; a
  key heard again at least two minutes later starts a run, consecutive
  agreeing words extend it (one-word slips allowed), and runs of at least
  ten words become scripts. Every later airing folds into the same script.
  It runs every ten minutes and on demand.
- **Teach mode feeds it too.** The words heard between *Is an ad* and
  *Show's back* are saved as a script directly.
- **Live matching tolerates errors.** `TranscriptMatcher` aligns the last
  four words against the scripts like the audio matcher aligns chroma
  blocks; a misheard word breaks four keys but not the alignment tally;
  three aligned hits confirm. The vote is 1.0 while hits keep coming,
  rolling an 8 s grace past the last one (ads have music and pauses).
- **Boilerplate on its own.** "Side effects may include", "ask your
  doctor", "call now", "results may vary" and the like essentially never
  occur in programming; hearing one holds the duck for 15 s.
- **Weight.** Three default weights, like the logo: a known script mutes
  alone.

## Consequences

- Utterance-level latency: a match arrives when the recogniser finishes a
  phrase, typically two to five seconds into it.
- Battery: continuous recognition is the costliest thing the app does;
  it is opt-in.
- The room matters: the survey put the TV about 4 dB above the fans, poor
  conditions for word recognition; louder TV, closer phone.
- Recurring programme lines (a show's opening) could be learned if they
  repeat within the window verbatim for ten words; *Not an ad* does not yet
  forget a script — a later addition if it happens.
