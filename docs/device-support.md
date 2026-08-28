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
2010-model LED AQUOS. Verify against the operation manual for your unit, but
this generation generally provides a 9-pin RS-232C control port with the Sharp
AQUOS serial command set, in which the MUTE command takes a parameter for
toggle / on / off, plus a query form for reading current state. That gives
discrete, verifiable muting and should be the preferred backend for this set.
Infrared remains the fallback if the port is absent or occupied.

Ethernet on this generation is for the set's own network features and is not a
general control API; do not assume network control without verifying.

## Shipped profiles

| Profile | Best path | Mute | Notes |
|---|---|---|---|
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
