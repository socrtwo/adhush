# ADR 0010 — Android: three ways to reach a Sharp set

Status: Accepted. 2026-09-12.

## Context

The owner has two Sharp sets and two phones. The LC-46LE830U has IP control;
the older LC-C3242U has no network port, only (very likely) an RS-232C
socket and an infrared receiver. One phone has an infrared blaster. A
USB-to-serial cable is on hand.

## Decision

One APK, one setting: *How this phone reaches the TV* — Network, Serial
cable, Infrared. Each phone keeps its own choice.

- **Network** is the existing path (`SocketTransport`, `SharpController`).
- **Serial cable** is the same Sharp command set and the same
  `SharpController`, over `SerialTransport` (usb-serial-for-android, 9600
  8N1, no login). Two-way, so `VOLM?` readback and the remote-wins rule work
  if the set supports them.
- **Infrared** is one-way. `IrKeySender` transmits Sharp's 15-bit frames
  through `ConsumerIrManager`; `StepVolumeController` ducks by pressing
  volume-down (normal − duck) times and restores by pressing volume-up the
  same number, persisting the count before the first press so a crash still
  restores. Mute is never used on this path: a toggle without readback
  cannot be made fail-safe. The code bytes are editable because a phone
  cannot learn them from the remote.

`DuckController` is the common face the service holds; `SharpController`
and `StepVolumeController` implement it.

## Consequences

- A separate APK per set is unnecessary; a build flavour could rename the
  app per phone if wanted.
- Infrared ducking takes about a second per fifteen steps and shows the
  set's volume bar; a missed press drifts the level by one step until the
  user's remote or *Restore volume* resets it.
- The default Sharp codes (address 1; volume up 0x14, down 0x15, mute 0x17,
  power 0x16) are the family's long-standing ones but unverified on the
  LC-C3242U; *Test TV* on the Infrared setting presses down three and up
  three so the owner can see whether the bar moves.
