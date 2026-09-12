# Device support

## Control path preference order
1. **RS-232 / network API** — deterministic, discrete, verifiable. Best.
2. **HDMI-CEC** — convenient where honored; support varies wildly by set.
3. **Infrared** — universal but open-loop. Many remotes expose only a mute
   *toggle*, which desynchronizes after a single missed emission. Where a
   discrete mute-on/mute-off code exists, always use it.
4. **Host audio / passthrough box** — no TV cooperation required at all.

## Infrared
Protocols to support: NEC, extended NEC, RC-5, RC-6, Sharp, Sony SIRC, Samsung,
Panasonic, plus raw pulse/space playback for anything unrecognized. Carrier is
typically 38 kHz; Sharp and Sony variants differ. Profiles carry either a named
protocol + device/subdevice/command triple or a raw timing array.

`adhush ir-test` sweeps candidate codes and confirms success by observing the
audio level in the capture stream — closed-loop verification for an open-loop
transport.

## Sharp AQUOS LC-46LE830U (reference set)
2010-model LED AQUOS, and the best-case device for this project: it accepts the
AQUOS command set over **both** a serial port and the network, with a discrete
mute rather than a toggle. All of the following is confirmed against the
operation manual for the LC-40/46/52/60LE830U series.

**Serial (preferred).** The RS-232C terminal is a 9-pin D-sub **male**
connector (specifications, p. 76), and the manual calls for a **cross-type**
(null modem) cable — so the cable end at the TV is female and cross-wired. A
USB-to-serial null-modem cable with an FTDI chip covers it in one piece; a
straight FTDI adapter plus a female-to-female null-modem adapter does the same
in two. Settings are 9,600 bps, 8 data bits, no parity, 1 stop bit, no flow
control (p. 58).

**Command framing** (p. 58), identical on both transports: four ASCII command
characters, then four parameter characters left-aligned and space-padded, then
CR. The set answers `OK` or `ERR`. `MUTE` takes 0 = toggle, **1 = on, 2 = off**
(p. 59) — so muting is `MUTE1␣␣␣\r` and unmuting is `MUTE2␣␣␣\r`. A `?`
parameter returns the present value for some commands; where `MUTE?` is not
one of them the set answers `ERR`, and `rs232_sharp.state()` degrades to
`None` rather than failing.

**Volume, not just mute.** The command table (p. 59) also lists `VOLM` with
parameter "Volume (0–60)" — absolute volume, not a step. That matters for any
build whose microphone is downstream of the TV's own volume control, because
*ducking* to a low level keeps the sensor alive where muting blinds it; see
`docs/android-app-design.md`. This set answers `VOLM?` with the current value
(`19\r`) and `MUTE?` with `1`/`2` — verified from the Android app on
2026-09-09; `VOLM<n>` and `MUTE1`/`MUTE2` reply `OK\r`. Of the Python
controllers only `network_ip`'s profile uses `MUTE`; `VOLM` ducking lives in
the Android app so far.

**Network (same commands, no cable).** Enable under MENU > Initial Setup >
Internet Setup > Network Setup > IP Control Setup, which also sets the port and
an optional login ID and password (p. 58). `network_ip` answers that handshake
on every connection via `perform_login` and opens a connection per command.
Login fields end with CR (`login_terminator = "\r"`, the default — with CRLF
this set took the stray LF as the password and answered "User Name or
Password mismatch").
The Android app's first run against a real set found the firmware ignoring
connections opened back-to-back, so the app holds one connection open
(`platforms/android`); if `network_ip` shows the same symptom on this set,
it needs the same change. Serial is
still the better choice for an unattended box: it cannot be broken by Wi-Fi
dropping, DHCP reassigning the TV, or a firmware update clearing a menu
toggle.

Ethernet on this generation also serves the set's own network features; IP
control is a separate thing that must be switched on deliberately.

## Shipped profiles

| Profile | Best path | Mute | Notes |
|---|---|---|---|
| `passthrough-box` | GPIO relay on the audio path | discrete + readback | any TV; docs/hardware-passthrough-box.md |
| `sharp-lc46le830u` | RS-232C | discrete + readback | reference set |
| `sony-bravia-generic` | Simple IP (TCP 20060) | discrete + readback | enable IP Control on the set |
| `roku-tv-generic` | ECP (HTTP 8060) | toggle | enable "Control by mobile apps" |
| `samsung-generic` | IR (samsung protocol) | toggle | |
| `lg-generic` | IR (NEC) | toggle | webOS API needs pairing; not yet a backend |
| `vizio-generic` | IR (extended NEC) | toggle | SmartCast API needs pairing |

IR codes in these profiles come from community-documented tables; always
confirm with `adhush probe` and `adhush ir-test` before trusting them. Toggle
paths must run with `control.verify_with_audio` enabled.

## Adding a profile
See `config/profiles/generic.example.toml`. Profiles inherit from `generic` and
override only what differs. Backend endpoints and codes belong in the profile
(`[ir]`, `[network_ip]`, `[<backend>]` sections); the main config's
`[control.<backend>]` section overrides per installation (e.g. your TV's IP
address). `adhush probe` reports which listed backends are usable on the
current machine, in preference order.
