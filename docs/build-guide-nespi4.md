# Build the AdHush box in a Retroflag NESPi 4 case

**Illustrated, print-ready version: `docs/print/AdHush-beginner-guide-NESPi4.pdf`.**

This is the beginner build from `docs/build-guide-beginner.md` with the
Raspberry Pi 4 B (2 GB) living inside a Retroflag **NESPi 4** case. Everything
about the picture path, the relay, the sound path, and the software is the
same as the standard guide, so this page only covers what the case changes.
Read the standard guide first; come here for the differences.

## What the case changes, in one paragraph

The NESPi 4's front-panel board plugs onto the Pi's header at **pins 1–8**,
the two rows of four at the corner nearest the microSD slot. That is where the
standard build takes its relay power (5 V on pin 2) and ground (pin 6), and
both 5 V pins on a Pi 4 live under that plug. So in this build the relay's
5 V and ground come from a **USB port on the case** through a USB-A breakout,
and the **only wire that leaves the case** is the mute signal on **pin 16
(GPIO 23)**. Because the USB port is the Pi's own, its ground is already the
Pi's ground, which is what lets a single wire do the job.

## Extra parts

| Part | Why | ≈ |
|---|---|---|
| Retroflag NESPi 4 case (fan, heatsinks, adapter board, SSD cartridge in the box) | The enclosure | $35–50 |
| Micro-HDMI to HDMI cable | The case brings out the Pi's micro-HDMI ports; the setup-day monitor needs this. Some bundles include one | $8 |
| USB-A male breakout with screw terminals | Feeds 5 V and ground from a case USB port to the relay | $3–5 |
| Double-sided foam tape | Sticks the relay module and breadboard to the back of the case | $3 |

The relay module, jumper kit, breadboard, audio cables, splitter, extractor,
and capture stick are the standard-guide parts, unchanged.

## Step order

1. **Wake up the Pi — bare on the table, before the case.** The case's USB
   ports are the two under the front flap, and this build uses both of them
   (capture stick + relay power). So do the Imager, first boot, keyboard, mouse,
   monitor, and the four typed lines with the Pi naked, exactly as the standard
   guide's Step 1. The monitor plugs into the micro-HDMI port nearer the USB-C
   power plug. Set up remote access (Raspberry Pi Connect or SSH) now too: you
   will not be plugging a keyboard into this box again.
2. **Build the Pi into the case.** Follow the case manual for screws. The
   parts that matter:
   - Fit the heatsinks. AdHush works the Pi all evening.
   - Plug the Pi's USB and Ethernet into the case's adapter board; plug the fan
     into the fan header on the case board.
   - Push the case's **8-pin plug onto header pins 1–8**. Count: it must not
     sit one column over.
   - Set the case board's **SAFE SHUTDOWN switch to OFF**. The ON position
     needs Retroflag's helper script, which is broken on current Raspberry Pi
     OS (it was written for RPi.GPIO and `rc.local`, both gone since
     Bookworm). OFF makes the POWER button a plain power switch, which is fine
     for a box that stays on.
   - **Leave the cartridge empty.** AdHush needs little storage, and the empty
     slot is the neatest exit for the one wire. It also leaves the internal
     USB 3.0 link idle.
   - Close the lid; don't screw it down until the relay test passes.
3. **Picture path** — as the standard guide, except the capture stick goes in
   a **front USB port under the flap**. Later NESPi 4 revisions have one blue
   USB 3.0 port there; earlier ones have two black USB 2.0 ports. Either works:
   the box captures 720p30, which fits USB 2.0. Then do the standard guide's
   *find your sound device* step.
4. **Meet your relay board** and 5. **Build the sound path** — unchanged. The
   whole sound path stays outside the case.
6. **Connect the Pi to the relay** — the changed step:
   1. Foam-tape the relay module and mini breadboard to the back of the case.
   2. Wire the USB breakout's **5V** screw to relay **VCC** and its **GND**
      screw to relay **GND**. Leave D+ and D− empty. Don't plug it in yet.
   3. Open the lid. Put a **male-to-female** jumper's female end on
      **pin 16** — even row, eighth pin along from the case's plug. Count twice.
   4. Route it out through the empty cartridge slot (or a rear vent slot) into
      any breadboard row. Close the lid.
   5. Two more male-to-female jumpers from that same row to **IN1** and **IN2**.
   6. Plug the USB breakout into the case's other front USB port. The relay
      board's power light should come on.

   Prefer a phone charger for the relay? Then add one more jumper from Pi
   **pin 9** (a free ground just past the case's plug) to relay GND, because
   the charger's ground is not the Pi's. Two wires out instead of one.

   Adult check before power-on: sound wires on COM + NC, both NO empty,
   grounds joined; case plug on pins 1–8 and nothing touching it; the only
   header jumper is on pin 16; breakout 5V→VCC, GND→GND, D+/D− empty; JD-VCC
   cap on. Then USB-C in, POWER button.
7. **Wake it up** — as the standard guide (`adhush probe --active`,
   `adhush calibrate`, `adhush run`, `sudo systemctl enable --now adhush`).
   The `active_high = false` fix for a backwards relay applies here too.

   **Turning it off:** with SAFE SHUTDOWN OFF, the POWER button cuts power.
   That is safe for the relay (fail-unmuted: sound returns) but unkind to the
   microSD card as a habit. Run `sudo shutdown -h now` from a remote shell,
   wait for the activity light to stop, then press the button.

## Troubleshooting additions

| Problem | Try |
|---|---|
| Relay board's light is off, never clicks | Breakout in a port with no power, or 5V/GND swapped on its screws. Try the other front port; check the markings |
| Case fan never spins | Fan plug belongs on the case board's fan header, red wire to + |
| POWER button does nothing, or kills the Pi instantly | That is the SAFE SHUTDOWN switch. OFF = plain power switch (this build). ON needs Retroflag's script, which does not work on current Raspberry Pi OS |
| Capture stick not found | Reseat it in the front port; run `adhush doctor` and check the video-device line |

## If you'd rather not use the case's front-panel plug at all

With SAFE SHUTDOWN OFF the buttons don't need the 8-pin plug for power, so
you could leave it off and the whole header is free — the standard guide's
pin 2 / pin 6 wiring then works unchanged. This is untested here: check that
the fan and the power LED still behave before relying on it.

## What was verified, and what wasn't

Verified from Retroflag's own safe-shutdown script and its README: the
NESPi 4 uses the same `retroflag-picase` script as the other cases, on BCM
GPIO 2, 3, 4 and 14 via RPi.GPIO — hence the pin 1–8 footprint. Reported in
community threads and retailer listings: the rear panel carries two micro-HDMI,
3.5 mm, USB-C and Ethernet with no USB-A; the front carries the USB ports and
buttons; the SSD cartridge hangs off an internal USB 3.0 link; port types
changed between revisions. Not verified: exact internal clearance for routing
a jumper out of the cartridge slot — if yours is tight, a rear vent slot is the
fallback, and a Dremel notch the last resort.
