# ADR 0024 — Three feeds: the schedule, SCTE-35, and the crowd

Status: Accepted. 2026-09-16.

## Context

The last three items of the backlog are not detectors that look at the
picture or listen to the sound; they are *feeds*: an EPG that says what
is on, the splice cues a cable stream carries in-band, and other AdHush
devices watching the same channel. Each is authoritative in its own way
and useless without configuration, so each is off — inert — until it is
pointed at something.

## Decisions

### `schedule` — XMLTV programme boundaries

`util/xmltv.py` reads the XMLTV that Schedules Direct's grabbers,
`tv_grab_*` and most EPG tools write; `detect/schedule.py` is fed wall
time through the new `Detector.tick(wall)` (which now reaches every
detector, not only the break clock). Two uses, both mild by design:

- For `start_grace_s` after a scheduled start the detector votes 0 with
  weight: a break in the first minute and a half of a show is unlikely,
  so the mute votes are *diluted* then — never vetoed, since a schedule
  is a minute out as often as not and a break across the top of the hour
  must still mute once the evidence is there.
- On a channel listed in `ad_free` (PBS, BBC) the engine never mutes at
  all (`ad_free_now`), whatever the detectors think, unless the owner
  holds a duck by hand.

It never claims the programme is *present*: an EPG cannot see a break
end. The current title is in the status.

### `ts_stream` + `scte35` — the cues in the stream

A new capture backend, `ts_stream`, reads an MPEG transport stream over
HTTP (an HDHomeRun's `http://<ip>:5004/auto/v<channel>`) or UDP (a DVB
dongle's streamer, an IPTV multicast). One reader thread fans the bytes
out three ways: to an ffmpeg for the frames, to an ffmpeg for the audio
(stereo, so width is measured), and to `util/mpegts.py`, a demuxer that
follows PAT → PMT to the SCTE-35 PID (stream type 0x86), reassembles
the sections and parses `splice_insert` and `time_signal` with
segmentation descriptors of the ad and break types. Cues arrive on the
event bus as `CueEvent`s (`CaptureCaps.cues`; `run_live` pumps a third
stream; `Pipeline.process` hands them to `Detector.observe_cue`).

`scte35` votes 1.0 from the out-of-network cue until the in-network cue,
the announced duration plus five seconds, or `max_break_s`, whichever is
first, and counts the in cue as programme evidence for `in_hold_s`. It
carries the alone weight: where the stream carries cues they are the
authority every other detector only estimates. Where a provider strips
them (most streaming apps, some cable boxes) the detector simply never
sees one.

### `crowd` — a shared feed of break times

The SponsorBlock idea for live TV. `adhush crowd serve` runs a server of
a few dozen lines: devices POST `/report` (a sha256 of the channel name
under a shared salt, a kind, a wall-clock time, a random device id) and
GET `/recent?prefix=<4 hex>` to receive every report whose hash starts
with that prefix — k-anonymity: the server never learns which channel a
device watches, only a sixteenth of the space, and the device keeps the
reports for its own full hash. Reports live ten minutes in memory;
nothing is written to disk.

`detect/crowd.py` polls on its own thread (`tick` only schedules; the
decision loop never waits on the network), and votes 1.0 with the alone
weight when at least `min_reports` *other* devices have reported a start
within `window_s` and no end since; an end from others is programme
evidence for a few seconds. The engine reports this device's own
confirmed breaks — start at MUTE, end when a real break ends, never a
timed duck — when `report` is on. Off by default: it needs a `url` and a
`channel`.

## Consequences

- Three more names in the default `enabled` list, all inert until
  configured; the listener example carries `schedule` and `crowd` too.
- A new capture backend and a new event type; `run_live` grew a third
  pump thread. `file_replay` has no cues and no wall clock, so the feeds
  stay inert in every replay test; their own tests build a schedule file,
  a transport stream and a server in-process.
- The crowd server is a LAN or self-hosted service. Nothing in AdHush
  points at a public one, and the salt is configuration, so two
  households sharing a server also share the salt.
- `adhush crowd serve` binds all interfaces on purpose; put it behind
  the same firewall as the rest of the house.
