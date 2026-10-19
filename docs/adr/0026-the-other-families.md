# ADR 0026 — The other families: Android TV, Hisense VIDAA, Philips JointSpace

Status: Accepted. 2026-09-18.

## Context

ADR 0025 left three families out because each has its own pairing:
Android TV / Google TV (Sony's newer Bravias, TCL and Hisense with Google
TV, Chromecast with Google TV, Philips' Android sets, the Shield), Hisense's
own VIDAA, and Philips JointSpace. The owner asked for them too.

## Decision

Three more `VolumeDevice` drivers in the core, each with the protocol
plumbing it needs and nothing more, all pure JVM and unit-tested:

- **Android TV** (`AndroidTv.kt`, `MiniProto.kt`, `ClientIdentity.kt`). The
  remote protocol v2 authenticates the *client* by certificate, so the
  phone makes a self-signed one once (BouncyCastle, the core's first
  dependency) and keeps it in the encrypted settings as a PKCS#12 blob.
  Pairing on port 6467: request, options, configuration, then the set
  shows six characters and the secret is SHA-256 over both RSA keys and
  the code's last four hex digits. The session on 6466 answers the set's
  configure and ping messages and reads its volume reports, so the level
  is known and set by stepping the volume keys. Messages are
  protocol-buffers, written and read by a fifty-line codec.
- **Hisense VIDAA** (`Hisense.kt`, `MiniMqtt.kt`). The set runs an MQTT
  broker on 36669 over TLS; the client connects with Hisense's service
  name and password, subscribes to the mobile topics, asks the set's
  state, and — on a newer set — types back the four digits it shows.
  Volume is read (`getvolume`) and set (`changevolume`) exactly; keys go
  through `sendkey`. Sets from about 2022 on demand a client certificate
  Hisense issues; AdHush does not carry it, and the wizard says so when
  the handshake is refused. MQTT 3.1.1 is a hundred lines, tested against
  a loopback broker.
- **Philips JointSpace** (`Philips.kt`, `Digest.kt`). Version 1 on port
  1925 needs nothing: `/1/audio/volume` reads and writes. Version 6 on
  1926 uses HTTP digest authentication with a paired device id and key:
  the set shows a PIN and the grant is signed with Philips' published
  secret. Digest is checked against the RFC 2617 example.

`TvFinder` probes the three ports; the wizard's Find my TV tries the
paths like the others, and when one wants a code it shows a single code
row (the same row a Vizio's PIN uses) and waits up to two minutes on its
own thread for the owner to type it. The remote screen works over all
three.

## Consequences

- BouncyCastle (bcpkix) is in the core, for one certificate. Nothing
  else uses it.
- A lesson kept in a comment: inside `Socket().apply { }` the name
  `port` is the socket's own, not the class's — the MQTT client connected
  to port 0 until the loopback test caught it.
- None of the three has met real hardware from this session; the framing,
  hashing and parsing are tested, and the wizard's live test is the proof.
