# Ideas backlog — from the September 2026 search

**Status (0.24.0):** items 1–7 and 10–12 are built (ADR 0023, ADR 0024);
5 (caption style), 8 (packshots), 9 (subtitle PIDs), 13 (Android TV
accessibility) and 14 (smoothing) remain.

What the wider world does that AdHush does not yet, ranked by what it
would buy this project. Each entry says what it is, why it would help, how
hard it is, and where it came from. Everything AdHush already has (black
frames, silence, loudness and crest, logo, scene cuts, aspect change,
fingerprints, speech and caption scripts, the AI judges, the break clock
and lengths, jingles, the AD badge, the ticker, timed ducks) is left out.

1. **Parental-rating bug as an unmute cue.** US networks flash the TV-14 /
   TV-PG box at programme start and after every break. MythTV scores it;
   for AdHush it is a near-perfect *unmute-onset* signal, the failure this
   project fears most. An ROI template or OCR on the decoded frame; easy.
   MythTV `ClassicCommDetector.cpp`.
2. **ATSC 3.0 / DVB-TA video watermark (A/335) on the HDMI path.** A
   payload in the luma of the first two lines, designed to survive set-top
   boxes and HDMI, used by A/336 and HbbTV-TA to tell TVs about ad breaks.
   Where a broadcaster emits it, an authoritative in/out marker without a
   tuner. A spec-driven decoder of two lines; medium; only where deployed.
   atsc.org A/335, dvb.org targeted-advertising watermarking.
3. **Ad-unit length quantisation.** Segments between boundary events that
   snap to 15/30/60 s, and a block of 60–395 s built from such units,
   score higher inside a candidate break (Comskip, MythTV). Different from
   the break-length histogram: it scores *inside* a break. Post-processing
   on existing detectors; easy.
4. **Audio channel-count or stereo-width switch.** Comskip penalises
   channel-count transitions; Japanese "Auto-Cut" VCRs detected ads purely
   by mono→stereo flips (US patent 5692093). On HDMI, the audio InfoFrame
   (5.1→2.0); on a mic or screen path, L–R correlation width. Easy on
   HDMI.
5. **Caption *style* changes.** Roll-up vs pop-on vs paint-on, a window
   that moves, a caption gap longer than N seconds; Comskip's "wrong type"
   modifiers. XDS carries the programme name. Medium; the caption region
   tracking half exists.
6. **Uniform-colour frames, not only black.** White or single-colour
   separators, and broadcasters that deliberately drop black frames.
   Generalise the black-frame run to low-variance frames (Comskip's
   `non_uniformity`). Trivial.
7. **Intro/outro template ("cutscene") matching.** Comskip keeps up to
   eight captured frames (bumpers, "we'll be right back", the title card)
   and correlates every frame. Sharp break-start *and* return timing per
   channel; easy, and the jingle's visual twin.
8. **Overlay-text density / packshot detector.** Ads end on a static
   product shot with a logo, URL or price; many small text patches across
   the frame (IEEE 5302320). Reuse the OCR's bounding boxes: count, area,
   position. Medium.
9. **Subtitle-PID absence (DVB) and audio-description dropout (US SAP).**
   Only with a transport-stream input; see 10.
10. **Optional transport-stream input (HDHomeRun or a DVB dongle) for
    SCTE-35 and EIT.** In-band `splice_insert` / `time_signal` cues;
    TSDuck parses them. A new capture path, not a detector; hard-ish but
    authoritative on cable local insertion.
11. **EPG programme boundaries (XMLTV, Schedules Direct).** Force
    "programme" at the scheduled start, gate the break clock per
    programme, mark ad-free channels. Easy.
12. **Crowdsourced live break timestamps (the SponsorBlock model).**
    Users on the same channel, NTP-synced, share mute/unmute events with
    k-anonymity hashing. High payoff, hard: opt-in, privacy, a server.
13. **Accessibility-service ad detection on Android TV / Fire TV.** Watch
    the UI tree for "Ad" / "Skip ad" nodes instead of camera OCR; a
    companion app. Medium.
14. **Neighbour-block smoothing.** MythTV nudges zero-score blocks toward
    their neighbours; a small HMM or hysteresis over fusion to stop
    flapping. Trivial.

Skipped after looking: Nielsen watermarks (a commercial flag exists but
there is no open decoder), PDC/VPS pause codes (unused by commercial
broadcasters), DNS blockers (cannot see server-side inserted ads), 3:2
cadence and HDR/SDR switches (no evidence), and tiny image classifiers of
one player's ad UI (the AD badge already covers it).
