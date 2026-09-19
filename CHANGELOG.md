# Changelog

All notable changes to this project are documented here.
Format follows Keep a Changelog; versioning follows SemVer.

## [0.28.2] - 2026-09-19
### Added
- Android, stream learning: a player that blocks capture is named as such
  within eight seconds, on the Home card and the notification, instead
  of a log line after forty. Digital silence from the first second means
  the player opted out of being heard; a black picture as well is the
  protected-window flag behind Android's "App content hidden from
  screenshare" message. Both cases say what to do instead: the channel's
  website in Chrome, or the microphone with the normal Start. A black
  picture with audio still heard is reported too, since the AD badge
  cannot be read from it. Help explains the message.

## [0.28.1] - 2026-09-19
### Fixed
- **The first evening's loop** (ADR 0027, amended). A taught break that
  ran into the show, two mis-taps of ten and forty seconds, and a jingle
  "opener" cut from the show's own bed fed each other: remembered breaks
  matched the show, a closer learned from the show ended every duck in
  three seconds, and each three-second duck taught more jingles. Now, in
  both cores: a break shorter than 20 s teaches the jingle learner
  nothing; two hearings of a sting within half an hour count once, so one
  session cannot promote it; a taught break under half a minute is a slip
  of the finger and one over six minutes is cut there; a remembered break
  whose duck ends within ten seconds on programme evidence is a false
  match, and two of them forget the record.
- Android: pressing Download twice started two writers on the same model
  file and the second one deleted the finished model. One download at a
  time, and a finished model is never deleted.
### Added
- Android: **Forget what it learned…** (Methods page, and the ⋮ menu):
  tick remembered breaks, jingles, the break clock or scripts and they are
  gone. **Save to a file** on the Log page (and the ⋮ menu) writes the log
  through the system file picker, beside Share.
### Changed
- Android: the set-up wizard is a button on the Home page next to Start,
  Stop, Test TV and Remote, labelled as the wizard on its own screen, and
  never opens by itself. The Home page's timed ducks are a row of chips;
  Is an ad and Show's back sit side by side; the test output appears only
  once there is some. The remote is laid out like one: power, input and
  mute on top, volume and channel rockers either side of a real D-pad,
  then the number pad, transport and picture keys; every key is at least
  56 dp tall. Buttons and cards are taller and rounder throughout, and
  the app has proper dark-mode colours.

## [0.28.0] - 2026-09-18
### Added
- **Jingle families** (ADR 0027): a learned sting is also recognised
  transposed by up to two semitones and stretched by up to ten per cent
  in tempo, at a higher bar, so a channel's per-show re-cuts of its house
  sting count as the sting they resemble. The vote reason names the
  variant. Both cores.
- **Segment stingers** (`stinger`, ADR 0027): the whoosh or hit a channel
  drops on the cut into a segment or a break — a short noisy burst over
  the bed, then the level moves. Default weight, inert until it fires,
  decays over 2.5 s; ignores our own duck. On by default in the Python
  core; on the phone it rides on the Loudness method.
- **The hour of day** (ADR 0027): every jingle remembers the local hours
  it opened breaks in; heard again at one of those hours it is trusted a
  break early and matched a little more loosely. `jingles.tsv` becomes v2
  with an `hours` column; v1 files still load, and the shared memory
  carries the hours between phones.

## [0.27.0] - 2026-09-18
### Added
- **The other TV families** (ADR 0026): Android TV / Google TV (paired
  once with the six-character code, the phone's own certificate kept),
  Hisense VIDAA (its MQTT broker; the four-digit code on newer sets),
  and Philips JointSpace 1 and 6 (a PIN once on the Android-based sets).
  All three read the volume back, so they duck to an exact level. The
  wizard's Find my TV tries them like the rest and shows one code row
  when a set asks for one; the remote works over all three.

## [0.26.0] - 2026-09-18
### Added
- **Any brand of TV from the phone** (ADR 0025). The wizard's Find my TV
  asks every set on the Wi-Fi who it is (SSDP, then each brand's own
  question) and proves each way in until one works: a Sharp's control
  port, a Sony Bravia (with its pre-shared key), an LG webOS (allow it on
  the set once), any DLNA renderer over UPnP, a Samsung's remote channel
  (allow once), a Roku TV, a Vizio SmartCast (a PIN once). Paths that read
  the volume back duck to an exact level; key-only paths step the volume,
  proven by a MUTE fired twice and your yes/no. Then the serial cable, then
  infrared brand by brand — Sharp, Samsung, LG/Vizio, Sony, Philips,
  Panasonic, Toshiba — until the set muted. The TV page's fourth radio
  carries what was found; the remote works over Roku, Samsung and Vizio too.
- Core: `VolumeDevice` and `LevelController` (the Sharp controller made
  general), infrared encoders for NEC, Samsung, Sony SIRC, RC-5 and
  Panasonic, `MiniHttp`, a hundred-line `MiniWebSocket`, and `TvFinder`.

## [0.25.0] - 2026-09-18
### Added
- Android wizard, **Find my TV** (ADR 0022, amended): the TV step tries
  every way in and reports each — the Wi-Fi (the typed address, or a scan
  of the phone's subnet for a Sharp on the control port), a serial cable
  through the USB adapter (asking for USB access if needed), and the
  infrared blaster (fired twice, then a Yes/No on whether the sound
  dipped). The first that worked is chosen, network first, and the found
  address is saved.

## [0.24.0] - 2026-09-16
### Added
- **What comskip, MythTV and the Auto-Cut VCRs knew** (ADR 0023), all
  inert until they have evidence, all in the default set:
  `rating_bug` — the parental-rating box after a break is positive
  programme evidence (Python HDMI path and the phone camera);
  `ad_units` — gaps on the 15-second ad-unit grid hold a mute through
  the middle of a pod (both cores; on the phone it rides on Quiet gaps);
  uniform separators in `black_frame` — a white flash or colour card
  counts like black; `cutscene` — captured intro/outro frames
  (`adhush cutscene add`), an "in" frame ducks alone, an "out" frame is
  the show back; `stereo_width` — the mono/stereo switch, measured by the
  capture backends before their downmix (`capture.stereo`); `watermark`
  — ATSC A/335 / DVB-TA presence lost when a local spot replaces the
  marked picture (experimental: written from the spec's shape, not a
  live feed).
- **Three feeds** (ADR 0024): `schedule` — XMLTV programme boundaries
  (a grace window after a start dilutes mutes; `ad_free` channels never
  mute); `ts_stream` capture + `scte35` — an HDHomeRun or DVB transport
  stream's in-band splice cues, parsed in-process, authoritative where
  they survive; `crowd` — an opt-in shared feed of break times on the
  same channel with k-anonymous hashing, `adhush crowd serve` for the
  server.
- Programme evidence comes from any detector that has it
  (`Detector.program_present`); wall time reaches every detector
  (`Detector.tick`); cues are a third event type (`CueEvent`).

### Fixed
- The release run built every binary and then refused to publish: strict
  `mypy` cannot find Pillow, which `adhush cutscene add --image` imports
  optionally inside a `try`/`except` and which CI does not install. The
  missing import is now ignored by configuration, and `ci.yml` runs the
  Python checks on **every branch push** instead of only `main` and pull
  requests, so the next such drift appears on the push rather than at the tag.

## [0.23.0] - 2026-09-16
### Added
- Android **set-up wizard** (ADR 0022): offered once on first run and always
  on the Home page. It asks how the phone reaches the TV (and asks the set
  its volume), where the phone sits, the channel, ticker and captions;
  listens to fifteen seconds of the show and ten of the room with the TV
  muted; looks through the camera for eight seconds; then suggests every
  method, the duck level, the camera target and zoom and the local AI size
  with a reason each, writes them, and opens the Methods page for review.
  Numbers only, never audio or pictures.
- `docs/ideas-backlog.md`: fourteen ranked ideas from a search of comskip,
  MythTV, the ATSC/DVB watermark specs, patents and peer projects.
### Fixed
- Every count of the phone's methods now says ten: the Help "Choosing
  methods" topic (eight), its lists of weak and strong methods (the clock
  and the jingle were missing), the Help header, the Android README's
  screens section (six) and the local-AI print guide (eight; PDF
  regenerated).

## [0.22.0] - 2026-09-16
### Added
- **`aspect_change`** (ADR 0021, after comskip): the Python core's HDMI and
  screen-capture paths now watch the picture's shape. Dark, flat bars at
  the edges give the active picture's aspect ratio; the programme's shape
  is a rolling mode, and a spot that arrives pillarboxed or letterboxed
  votes for as long as it holds the other shape. Inert while the shape is
  the programme's, so it never dilutes the other detectors; never mutes
  alone. On by default and in the HDMI example configs; the synthetic
  fixtures gained a 4:3 ad for its ground truth.
- **Crest factor in loudness** (ADR 0021, after beepscore): the
  peak-to-RMS ratio rides in the same short-term window against the same
  slow baseline, and a drop of 4 dB under the programme's — a spot
  compressed flat, however loud — adds up to half a vote. Python and the
  phone; `crest_drop_db = 0` turns it off.

## [0.21.0] - 2026-09-15
### Added
- **Method 10, break jingles** (ADR 0020, after AdVent): the channel's own
  sting into and out of every break is learned from the breaks the
  detectors already end — three breaks opened by the same three seconds of
  sound promote it — and then ducks the set the moment it is heard, ahead
  of every other method. A closer heard while ducked counts as the
  programme coming back. Python core (`jingle`, on in the listener example)
  and the phone; `jingles.tsv` travels with the shared memory.
- **The ad badge** during stream learning on Android: ML Kit reads the
  corners of the player once a second and "Ad", "Ad 1 of 3", "AD 0:15" or
  "Your video will resume" is ground truth for the break.
- **Learned break lengths**: the break clock keeps a histogram of how long
  this channel's breaks run; a mute's ceiling becomes the 90th percentile
  plus 30 s once five breaks are known, and the status line shows "about
  m:ss left" during an automatic duck.
- **A countdown you can extend**: manual ducks of 150, 180, 210, 240, 270
  and 300 s next to the 30–120 s ones, a `+30 s` button (phone Home, the
  remote, the notification, the web page, IPC `duck_for` with
  `extend: true`) and a live `m:ss left` on the status line. `+30 s` on an
  automatic duck holds it as a timed one.
- A remote-control icon in the toolbar of every phone screen.

## [0.20.0] - 2026-09-15
### Added
- **A release for every platform** (ADR 0019): one-file binaries of the
  core for Windows x64, macOS (Apple silicon), Linux x64 and Linux arm64,
  each smoke-tested on its own runner; a Raspberry Pi bundle with the arm64
  binary, the listener config and the PDF guides; the Android APK, the web
  app (which is also the iOS install, through Add to Home Screen) and the
  wheel as before. `adhush init` writes a starter config and the profile
  library next to a binary. The web front end and the profiles travel
  inside the binary; ffmpeg is the one thing to install.

## [0.19.0] - 2026-09-15
### Added
- Android **Learn from a stream** (ADR 0018): with Android's playback
  capture the phone hears the live stream it is playing (MS NOW, Xfinity
  Stream, any channel's web player) and learns every break into the same
  memory it uses at the TV — fingerprints, and words with Speech on; no TV
  is touched. Started from the Methods page; the notification counts the
  breaks. `docs/print/AdHush-guide-stream-learning.pdf` is the guide.
- Android **memory that moves**: *Share this phone's memory* zips the
  breaks, scripts and clock; *Import memory from a file* merges another
  phone's without doubling what is known.

## [0.18.0] - 2026-09-15
### Added
- **Method 9, the break clock** (ADR 0017): learns which minutes of the
  hour the breaks land on from every confirmed break, votes once a minute
  has been watched in three hours, never ducks alone. Phone and Python;
  `[detect.clock]`, `clock.tsv`.
- **Timed manual duck**: 30, 60, 90 or 120 seconds, then back up on its
  own; works from any state, extends when already ducked, "Show's back"
  ends it, nothing is learned. Home page, the remote, the web page
  (`duck_for` IPC command; `timed_s` in status).
- **Remote control**: every key of the Sharp handset through the control
  port (`RCKY`), on a phone screen that also carries Start, Stop, Is an
  ad, Show's back, Not an ad and the timed ducks; the same grid on the web
  page (`remote` IPC command, `send_key` on controllers,
  `commands.remote_key` in the Sharp profile).

## [0.17.0] - 2026-09-15
### Added
- **Three sizes of local AI** on Android (method 8): Qwen 2.5 0.5B
  (550 MB), Qwen 2.5 1.5B (1.6 GB) and Qwen 3 4B (2.7 GB, the newer
  `.litertlm` bundle). Each size keeps its own file; pick one under
  Methods, download, switch on. `docs/print/AdHush-guide-local-AI-OnePlus.pdf`
  walks through it.
- **Duck compensation** (ADR 0016, borrowed from admuffs): with a room
  microphone the loudness detector is told about every duck, waits one
  window, measures how far the room dropped and judges the ducked ad on the
  original scale — so a ducked commercial no longer reads as "programme
  resumed" and the set is not ducked again and again. When the ducked set
  is buried under the room (near the silence gate, or flat fan noise), the
  loudness and silence detectors go inert and the other methods carry the
  unmute; the reason says `ducked_buried`. Python listener and phone alike.

## [0.16.0] - 2026-09-15
### Added
- Android **method 7, Ask Claude** (ADR 0015): the last 40 seconds of words
  go to Claude with one question — is a commercial playing? — and the
  one-line answer is a vote strong enough to duck on its own. Haiku 4.5 by
  default (Sonnet 5 / Opus 5 selectable), tie-breaker cadence by default
  (ask only when the other methods are unsure or while ducked), a confident
  answer saved as a script. Opt-in, API key in encrypted settings, text only
  ever leaves the phone. **Test Claude with a sample** shows its answers.
- Android **method 8, Local AI**: the same question answered by
  Qwen2.5-0.5B running on the phone through MediaPipe's LLM Inference
  (about 550 MB, downloaded once, resumable). Free and private.
- Android camera: **the news ticker instead of the bug** — a target choice
  under Channel bug; setup finds the lower-third band's edges and watches
  it with the same sighting and whole-TV rules.
- Android speech: a **better speech model** choice (Vosk medium, 128 MB).
- Android: the status line and the Methods page show what each AI last
  said; warnings when a judge has no words (speech and captions both off),
  no key, or no model.

## [0.15.1] - 2026-09-15
### Added
- `rs232_sharp`: **ducking** over the cable (`duck_level`, `normal_volume`,
  `duck_state_file`, crash recovery at the next start, restore on close),
  the same behaviour `network_ip` gained in 0.15.0.
- The listener guide now wires the TV by its **RS-232C serial socket**
  (USB null-modem cable, no login, no Wi-Fi dependence), with Wi-Fi as the
  alternative; `config/adhush-listener.example.toml` defaults to the cable.
  The illustrated NESPi 4 edition
  (`docs/print/AdHush-beginner-guide-listener-NESPi4.pdf`) is rewritten to
  match and gains an optional SSD-in-the-cartridge page.
- Android: version number only.

## [0.15.0] - 2026-09-14
### Added
- **The Pi listener** (ADR 0014): the Python core learns what the phone
  learned. A Raspberry Pi with a webcam and a microphone on a shelf, turning
  a Sharp down over Wi-Fi: `config/adhush-listener.example.toml` and a
  beginner's guide, `docs/build-guide-tv-listener.md`.
- `network_ip`: one persistent connection (login once, late replies drained,
  reconnect and resend on a hang-up), a set that closes before the login
  prompt reported as **busy** rather than a wrong password, and **ducking**
  (`duck_level`, `normal_volume`, `duck_state_file`) through the profile's
  new `volume_set` / `volume_query` commands; a run that died ducked is
  repaired at the next start. The Sharp LC-46LE830U profile carries the VOLM
  commands.
- Logo detector: `require_sighting` (the bug must be seen before it can be
  missed; "Not an ad" demands a fresh sighting), `search_px` (the box slides
  and the best match counts), `stale_s` (no frame = inert). Camera capture
  drops frames that show only part of a screen ("whole TV or nothing").
- Detectors can be **inert** (`voting`), and fusion normalises over the
  detectors present this tick; detectors are told when the user says it was
  the show (`user_says_program`).
- **Teach mode** in the core: `confirm_ad` outside a mute ducks now and
  holds; the new `show_back` command restores and learns the bracketed
  break. `[fusion] not_ad_quiet_s`: after "Not an ad" nothing may mute for a
  minute. Status carries `teaching`, `quiet_s` and `camera`; the web page has
  **▶ Show's back** and shows both.
- Android: version number only (no code changes since 0.14.1).

## [0.14.1] - 2026-09-14
### Fixed
- Android: the bottom tab bar covered the last part of every page (the
  Save/Test row on TV, the last method cards, the end of the log). Android
  ignores `paddingBottom` when `padding` is also set on the same view, so
  the room left under each page was never applied. Each page now pads its
  sides explicitly and scrolls clear of the bar.
- Android: **Test TV while AdHush is running** failed with "hung up during
  the login handshake". The Sharp allows one control connection at a time
  and the running service holds it, so the test's second connection was
  closed before the login prompt. The test now runs through the service's
  own connection while it is running, and a set that hangs up before
  asking for a login is reported as busy rather than as a wrong password.
- Android: pressing **Is an ad** (button or tile) before the microphone had
  ever been allowed crashed the app: Android refuses a microphone-type
  foreground service without the permission. The service now logs and
  stops instead, and the app's control buttons say "not running" when
  nothing is running.

## [0.14.0] - 2026-09-14
### Fixed
- Android: the set ducked again seconds after every **Not an ad** during a
  show. The camera's logo detector called the bug "gone" without ever having
  seen it (a template that did not match the live picture), and its vote is
  strong enough to duck alone. Now the bug must be **sighted** once before
  its absence counts; **Not an ad** and **Show's back** tell every detector it
  was wrong (the camera then needs a fresh sighting, a matched script is
  dropped) and open a **one-minute quiet period** in which nothing may duck.
- Android camera: the logo box is **slid over a small window** and the best
  match counts, so a screen edge found a few pixels off in a hand no longer
  reads as "logo gone" (the likely cause of the mismatch above).
- Android camera: a TV that runs off the edge of the picture, or a lit shape
  that is not TV-shaped, is **"whole TV not in view"** — inert, never a duck.
- Android: a blank TV address was tried as ":10002" every five seconds; the
  app now refuses to start (and says so) until one is typed. The encrypted
  settings falling back to plain ones is now logged.
- Android speech: stopping while the microphone was still feeding the
  recogniser threw `RejectedExecutionException` on the mic thread (two
  crashes in the log). The feed is guarded and the mic is stopped first.

### Added
- Android **Methods** page: all six ways to spot a commercial are switches —
  quiet gaps, loudness jumps, remembered breaks, channel bug (camera), spoken
  words (speech), **on-screen captions** (new, ADR 0013). At least one must be
  on; the app refuses to start otherwise. A coloured dot on each card and a
  chip row on Home show which methods are running right now; buttons of a
  running method are tinted; Start turns into a green "Running", Stop turns
  red.
- Android **captions** (sixth method): the camera reads the closed-caption
  band off the screen with the on-device text recogniser and feeds the words
  to the same script learning and matching as speech. Nothing is misheard
  over the room's fans.
- Android **Test mode** card on Home: Test TV with its byte-by-byte result
  printed right there (still on the TV page too).
- Android **Help** page and ⓘ buttons on every card: what the "bug" is, what
  a "script" is, ducking vs muting, teaching, test mode, and each method.
- Android **splash screen** (the app's mark on a deep blue field) and colour:
  the status card is green for the show, red while ducked, orange while
  teaching, grey when stopped; each method has its own colour.
- Android camera setup in **colour**, with **pinch-to-zoom** (and a slider)
  that zooms the camera itself so the bug is big; the zoom is saved and used
  by the service. The TV outline turns red with a warning when the whole TV
  is not in the picture. The status line says SEEN / GONE / not seen yet.
- Android status line reports what the camera can see ("bug seen", "whole
  TV not in view", "looking for the bug") and the quiet period after Not an
  ad.

## [0.13.0] - 2026-09-14
### Fixed
- Android: the app could vanish without a word. The service installed a
  crash handler that killed the process silently on any uncaught error.
  The app-wide handler now writes the stack trace to the error log,
  restores the TV volume, and lets Android report the crash.
- Android speech: the 40 MB model was loaded on the main thread inside the
  service start, long enough to stall it; it now loads in the background
  and attaches when ready. Closing the recogniser now waits for the block
  being processed, instead of racing a native call.

### Added
- Android **error log**: a rolling file every status line, caught error and
  crash goes to, shown on the Log tab, with **Share error log**.

### Changed
- Android interface rebuilt on Material 3: a top bar with the live status,
  four tabs (Home, TV, Senses, Log), cards, labelled text fields with a
  password toggle, switches, and one primary action per card. All the same
  functions, arranged the way a phone app is expected to be.

## [0.12.0] - 2026-09-14
### Added
- Android **transcript detector**, the fifth way (ADR 0012): offline speech
  recognition (Vosk, small English model downloaded once), commercials
  found by **repetition** in the last three hours of words (a run of ten or
  more words heard again minutes apart), teach-mode windows saved as
  scripts, live matching that tolerates misheard words with a rolling
  grace, and legal/sales boilerplate that ducks on its own. `Learn scripts
  now` button and a ten-minute automatic pass.

## [0.11.0] - 2026-09-13
### Changed
- Android camera: a **setup screen** replaces the blind 45-second button —
  the live picture with the screen and logo boxes drawn on it, a magnified
  crop of the chosen box, a live match score, and drag-to-draw to override
  the automatic choice. The first real run found the finder picking a
  persistent news graphic rather than the bug, unverifiable without seeing
  it.
- Hand-held phones: the screen is re-found in every frame with edges refined
  to the pixel (the coarse box was only good to 8 px), capture is 1280 × 720,
  a blur guard makes a smeared frame inert rather than "absent", and absence
  must last 2.5 s. Frames come out upright.

## [0.10.0] - 2026-09-12
### Added
- Android **camera logo watching** (ADR 0011): the back camera feeds two
  luma frames a second; the lit screen is found automatically; a
  **one-button set-up** watches 45 s of programme, finds the corner whose
  edges never move and saves it as the logo template; the ported
  logo-absence detector then ducks the set when the bug disappears and
  restores when it returns. Presence of the logo vetoes audio-only ducks
  and ends fingerprint holds early. Inert when no screen is in view.
  Vision code and the finder are in the core with synthetic-room tests.

## [0.9.0] - 2026-09-12
### Added
- Android: **three TV paths** in one APK (ADR 0010) — Network (as before),
  **Serial cable** (RS-232C through a USB-OTG adapter; same Sharp commands,
  no login, two-way), and **Infrared** (the phone's own blaster speaking
  Sharp's 15-bit protocol; ducking by counted volume presses, persisted so a
  crash still restores; mute deliberately unused). `Test TV` exercises
  whichever path is selected. Sharp IR encoding and the step controller
  live in the core with tests.

## [0.8.0] - 2026-09-12
### Added
- Android **teach mode** (ADR 0009): *Is an ad* now holds the duck until
  *Show's back*, and the whole bracketed break is learned as one record of
  *material*. Matching on material is rolling — the duck stays while the
  live audio agrees with anything known, re-anchors when the next spot
  starts, and releases 5 s after the last known spot — so taught spots are
  recognised in any order, alone, or as cut-downs. *Not an ad* during
  teaching cancels without learning; the 4-minute ceiling ends a forgotten
  session and still learns it. Notification actions are now Is an ad /
  Show's back / Not an ad (Stop lives in the app).

### Fixed
- *Is an ad* used to restore the volume after 400 ms, having learned
  nothing, because nothing held the state machine in AD.

## [0.7.4] - 2026-09-09
### Fixed
- Loudness detector (both cores): the baseline was taken from the first
  short-term value above the gate, which on a phone is a window still half
  full of the microphone's start-up silence — several dB low — and the
  freeze-while-elevated rule then kept it there for good. The first room
  survey showed the detector at full confidence 83% of the time. The
  baseline now waits for a whole un-gated window, and an elevation longer
  than `max_elevated_s` (180 s, longer than any ad pod) unfreezes it.
- Room survey gains a `baseline_lufs` column so this is visible.

## [0.7.3] - 2026-09-09
### Added
- Android **room survey**: a 10-minute recording of what the detectors
  measure (dBFS, flatness, short-term LUFS, silence floor, confidences,
  ducked flag) — numbers, never audio — with a digest in the app and a
  *Share survey* button that hands the TSV to any app via FileProvider.
  `RoomSurvey` lives in the core so the digest is unit-tested.

### Verified
- LC-46LE830U over IP from the Android app: login accepted, `VOLM?` and
  `MUTE?` answer, mute/unmute and ducking confirmed `OK`. Documented in
  `docs/device-support.md` and the Android README.

## [0.7.2] - 2026-09-09
### Fixed
- Sharp IP login: fields are ended with **CR**, not CRLF — the LC-46LE830U
  took the stray LF as the password and refused every login. The Android
  client falls back to CRLF once if a set refuses CR; `network_ip` gains a
  `login_terminator` option (default `"\r"`). "User Name or Password
  mismatch" now counts as a refusal on both platforms, so the app says
  *rejected login* instead of *unreachable*.

## [0.7.1] - 2026-09-09
### Changed
- Android: the Sharp client keeps **one connection** open and reconnects when
  the set drops it, instead of a connection per command — the first run
  against an LC-46LE830U showed the set ignoring connections opened
  back-to-back. A silent reply is now "unconfirmed", not a rejection; only
  `ERR` rejects. *Test TV* logs every raw exchange, including the login
  prompts, and tests ducking with the configured normal volume when the set
  will not answer `VOLM?`.

## [0.7.0] - 2026-09-08
### Added
- **Android on-device app** (`platforms/android`, ADR 0007): a pure-Kotlin
  `core` — DSP (arbitrary-length FFT via Bluestein), the loudness detector
  ported constant-for-constant, a microphone silence detector with an
  adaptive room floor, fusion and the state machine ported verbatim, 12-bit
  chroma fingerprints with an **audio-primary** pair-key matcher and learner,
  a file-backed store, the Sharp AQUOS IP client with login handshake and a
  **ducking** controller (`VOLM`, persisted pre-duck volume, remote-wins), and
  the engine — plus a thin `app`: microphone foreground service, encrypted
  settings, notification actions, a *Not an ad* Quick Settings tile, and a
  *Test TV* button that settles whether the set answers `VOLM?`.
- Conformance harness: `tools/gen_conformance.py` writes fixtures from the
  Python core; the Kotlin tests reproduce the signal and match every block
  (worst LUFS delta 5e-10) and replay fusion/state decisions tick for tick.
- CI builds the debug APK on every change under `platforms/android` (verified:
  the workflow assembles and uploads `adhush-android-debug`) and the
  release workflow attaches it to each release.

## [0.6.0] - 2026-09-08
### Added
- **Always-on-top mini window** (`adhush overlay`, `src/adhush/ui/overlay.py`,
  ADR 0008): a small, borderless, translucent, draggable pill showing
  PROGRAM / SUSPECT / MUTED with ✗ not-an-ad, ✓ is-an-ad and ■ stop. Standard
  library only (tkinter + urllib), runs as its own process so it can never
  stall detection, remembers where you put it. Started automatically by
  `adhush run` when a display exists; `[ui] overlay` / `--no-overlay` turn it
  off.
- **Always running**: `adhush service install|uninstall|status` installs the
  core as a start-at-login service on Linux/ChromeOS (systemd user unit),
  macOS (launchd agent) and Windows (Task Scheduler logon task), in the
  user's session so the overlay has a display.
- **`shutdown` IPC command**: the one sanctioned way a UI stops the core.
  Additive to protocol v1.
- **The core serves the web front end** at `/` (`[ipc] web_root`), so any
  phone, tablet or PC on the network needs only the core's address. Fixed
  allow-list of files, no path traversal, static files public while the API
  stays token-gated.
- **Installable web app**: `platforms/web` gains a manifest, a service worker
  and icons, so the front end installs to the home screen on Android, iOS,
  ChromeOS and desktop browsers. A **Mini window** button opens a Document
  Picture-in-Picture window (Chromium 116+): a real always-on-top floating
  control on ChromeOS, Windows and macOS browsers; a popup fallback elsewhere.
- Tag-triggered release workflow builds the wheel, sdist and a web-front-end
  zip and publishes them as a GitHub Release.
- `docs/release.md`: what each platform gets in this release, including what
  it does not (no native Android/iOS binaries yet; see ADR 0007).
### Changed
- `adhush run` prints the served address rather than a file path.
- Overrides win in the status display: a forced mute reads MUTED before the
  state machine ticks.

## [0.5.0] - 2026-08-28
### Added
- Phase 5 (hardware passthrough box) implementation:
  - `relay_hdmi` controller: a GPIO-driven relay physically opening the
    intercepted audio path. Discrete, instant, TV-agnostic, with
    commanded-state readback; wired through normally-closed contacts and
    energized only to mute, so a crash, power loss, or shutdown always
    fails *unmuted*. Injectable pin driver (pigpio by default).
  - `passthrough-box` device profile and
    `config/adhush-passthrough.example.toml` (HDMI-UVC capture, full
    detector set, relay control, LAN-visible IPC with a token).
  - `docs/hardware-passthrough-box.md`: signal topology, parts list,
    fail-unmuted relay wiring, and the latency/delay-line design note.
  - `scripts/install-pi.sh`: real installer — system packages, pigpiod,
    venv, and an `adhush.service` systemd unit.
  - Probe support for `relay_hdmi`; every roadmap control backend is now
    implemented.

## [0.4.0] - 2026-08-28
### Added
- Phase 4 (platforms) implementation:
  - IPC: versioned JSON wire schema (`ipc/protocol.py`) and a stdlib-only
    localhost HTTP + Server-Sent-Events API (`ipc/api.py`, ADR 0006) with
    status, override (auto/mute/unmute), confirm/reject ad, live detector
    trace, optional bearer-token auth, and permissive CORS. `adhush run`
    starts it when `[ipc] enabled = true`.
  - Engine IPC surface: thread-safe status snapshots, controller override
    pinning, event listeners, and user feedback — confirm forces learning a
    segment; reject unmutes immediately, skips learning, and deletes the
    fingerprint behind a false match.
  - Capture: `screen` (x11grab / avfoundation / gdigrab), `camera`
    (v4l2 / avfoundation / dshow with adaptive screen-rectangle detection,
    glare-tolerant auto-crop), `microphone`, and `line_in`, all with
    format-prefixed device strings and pure, testable argv builders.
  - Control: `local_audio` host mute (pactl/amixer, osascript, nircmd) with
    state readback where the platform allows; probe support included.
  - Platform shells: `platforms/web/index.html` — a dependency-free static
    front end over the API (SSE live state, override and feedback buttons) —
    and concrete per-platform run instructions for Linux/Pi, Windows, macOS,
    ChromeOS, Android, and iOS (mobile = thin client over a networked core).

## [0.3.0] - 2026-08-28
### Added
- Phase 3 (breadth of control) implementation:
  - `cec`: mute toggle through cec-client (User Control Pressed/Released);
    toggle-only by CEC's nature, so pair with audio verification.
  - `ir_pigpio`: LIRC-free raw IR waveforms on a GPIO pin, encoding NEC,
    extended NEC, Samsung, Sharp (double inverted frame), Sony SIRC
    (12/15/20-bit), RC-5 bi-phase, and raw pulse/space arrays.
  - `ir_blaster_net`: Global Caché iTach `sendir` over TCP and Broadlink
    RM devices via the optional `broadlink` package.
  - `network_ip`: profile-driven TCP (e.g. Sony Simple IP with discrete
    mute and state readback) and HTTP (e.g. Roku ECP) control.
  - `control/probe.py` + `adhush probe` (ADR 0005): safe, side-effect-free
    discovery of which backends can drive the set, reported in the
    profile's preference order; `--active` sends a real mute/unmute pair.
  - `resolve_options`: profile-supplied backend settings (including the
    shared `[ir]` section) merged under `[control.<backend>]` overrides,
    keeping device specifics in profiles.
  - Profile library: `samsung-generic`, `lg-generic`, `sony-bravia-generic`
    (Simple IP discrete mute + readback), `vizio-generic`, `roku-tv-generic`
    (ECP), documented in docs/device-support.md.
- Config: `ControlConfig.sections` keeps every `[control.<backend>]` section
  so probing can resolve options for non-selected backends.

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
