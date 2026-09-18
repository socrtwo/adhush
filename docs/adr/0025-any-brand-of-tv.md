# ADR 0025 — Any brand of TV, from the phone

Status: Accepted. 2026-09-18.

## Context

The phone app spoke one language: Sharp's, over its network port, its
RS-232C port, or its infrared codes. The Python core has profiles for
Samsung, LG, Sony, Vizio and Roku TV, but the owner's request was for the
*wizard* on the phone to check the TV and set itself up whatever the brand
is. The set-up wizard's "Find my TV" (ADR 0022, amended) had just learned
to search the Wi-Fi for a Sharp; this is the same idea for everyone else.

Two facts shape the design. Most sets can be reached one of two ways: a
path that *reads the volume back* (then AdHush ducks to an exact level and
notices the remote) or a path that only *sends keys* (then AdHush steps the
volume and keeps count, as it already does over infrared). And every brand
has a question that needs no key or pairing to answer — a Roku's device
info, a Samsung's `/api/v2/`, a Sony's interface information, a Vizio's
power state, an LG's open port, a Sharp's `VOLM?`, and the UPnP
description every DLNA-capable set publishes — so the phone can find out
*what* it is talking to before it asks for anything.

## Decision

### A generic level controller

`VolumeDevice` (get, set, mute, a maximum) and `LevelController` on top of
it are the Sharp controller made general; `SharpIpClient` now implements
`VolumeDevice` and `SharpController` is `LevelController` with the Sharp
client underneath, so nothing that worked changed. One-way paths stay
`KeySender`s under the existing `StepVolumeController`.

### Drivers, in the core module, pure JVM

| Path | Speaks | Reads volume back | Needs once |
|---|---|---|---|
| `sharp` | its control port | yes | login, if the set has one |
| `sony` | JSON-RPC on port 80 | yes | the pre-shared key the owner set in the TV's menu |
| `lg` | SSAP over a WebSocket on 3000/3001 | yes | "Allow" on the set; the client key is kept |
| `upnp` | UPnP RenderingControl (SOAP) | yes | nothing |
| `samsung` | the remote channel over a WebSocket on 8002/8001 | no | "Allow" on the set; the token is kept |
| `roku` | ECP on port 8060 | no | nothing |
| `vizio` | SmartCast HTTPS on 7345 | no | a PIN the set shows; the token is kept |

Two helpers make this possible without a networking library in the app:
`MiniHttp` (one request, headers, a timeout, and a trust-all TLS context
for a set's self-signed certificate on the LAN only) and `MiniWebSocket`
(a text-frame RFC 6455 client in a hundred lines: masked frames, pings
answered, tested against a loopback server). Every parser is a function of
a string and is unit-tested; nothing else in the tests touches a network.

### Finding the set: `TvFinder`

One SSDP search names the makers, models and renderers in the house.
Every address it names is then asked the brand questions, in parallel;
with nothing on SSDP the phone's own /24 is swept for the brand ports.
The result is a `FoundTv` per address with its brand, model and the paths
that answered, exact paths ordered first.

### The wizard, brand by brand

Find my TV lists what it found, then proves the paths in order. An exact
path proves itself by reading the volume (a Sony needs its key typed; an
LG makes the set prompt for permission and waits up to a minute). A key
path cannot prove itself, so the wizard fires MUTE twice over it two
seconds apart and asks the owner — the same yes/no as infrared — one path
at a time until one is confirmed (a Vizio first shows a PIN, which the
wizard asks for and pairs with). Then the serial cable, then the blaster:
infrared now cycles through the code sets brand by brand, the detected
brand's first, until the owner says the set muted. The first thing that
worked is chosen, network before cable before blaster, and the TV page's
fourth radio carries it: "Another brand, found by the wizard: Samsung
UN55TU8000 — Samsung remote channel".

### Infrared for the others

`Ir.kt` gained encoders for NEC, Samsung's NEC variant, Sony SIRC-12,
Philips RC-5 (Manchester) and Panasonic's Kaseikyo, and a table of the
four keys for Sharp, Samsung, LG (and Vizio, which answers LG's codes),
Sony, Philips, Panasonic and Toshiba. The Sharp set still takes the
address and codes typed on the TV page.

### The remote

Roku, Samsung and Vizio have key maps, so the remote screen works over
them; a Sharp keeps its RCKY keys; the exact paths without a key protocol
(Sony, LG, UPnP) answer only volume and mute from the remote.

## Consequences

- `usesCleartextTraffic` is on: Roku, Sony and UPnP speak plain HTTP on
  the LAN. Nothing in the app talks plain HTTP to the internet.
- Tokens and keys live in the encrypted settings with the Sharp password.
- Not one of these paths has been exercised against real hardware from
  this session; the parsers and the WebSocket client are tested, the
  protocols are written from their published shape, and the wizard's live
  test is the verification. Where a set does not answer, the wizard says
  so and moves to the next path.
- Not done: Android TV / Google TV's pairing protocol (Sony's newer sets,
  TCL and Hisense without Roku), Hisense VIDAA, and Philips JointSpace.
  Those sets are usually reachable over UPnP or infrared meanwhile.
