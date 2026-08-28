# Build the AdHush box — beginner's guide

This guide is written so a 12-year-old can follow it. No soldering, nothing
dangerous: every part plugs together or screws down with a small screwdriver.
Everything runs on low-voltage USB power — **never** open or wire anything
that plugs into a wall outlet. Ask an adult to help with the two steps marked
👨‍🔧, and to check your wiring before you turn anything on.

**What you're building:** a little box that sits between your cable box (or
antenna tuner, or streaming stick) and your TV. It *watches* the show, and
when a commercial starts, it clicks a switch that cuts the sound. When the
show comes back, it clicks the sound back on. The TV never knows.

**Time:** about an afternoon. **Difficulty:** if you can build a LEGO set
from instructions and type commands carefully, you can build this.

## The parts (about $120–160 total)

| # | Part | What it does | Rough price |
|---|------|--------------|-------------|
| 1 | Raspberry Pi 4 (2 GB or more) + official USB-C power supply | The brain — a tiny computer | $45–60 |
| 2 | microSD card, 32 GB | The brain's memory | $8 |
| 3 | Powered HDMI splitter, 1 in → 2 out | Makes a copy of the TV picture | $15 |
| 4 | HDMI audio extractor (HDMI in → HDMI out + headphone/RCA audio out) | Pulls the sound out into a normal audio cable | $15–20 |
| 5 | USB 3.0 HDMI-to-USB capture stick (says "UVC", 1080p is fine) | Lets the Pi see the copied picture | $15–25 |
| 6 | Relay module, 2-channel, 3.3 V logic, with screw terminals ("opto-isolated" is best) | The click-switch that cuts the sound | $7 |
| 7 | 3 female-to-female jumper wires | Connect the Pi to the relay | $3 |
| 8 | Two 3.5 mm audio cables (one will be cut in half) + one you keep whole as a spare | Carry the sound | $6 |
| 9 | 2 short HDMI cables (plus the ones you already have) | Connect everything | $10 |
| 10 | Small screwdriver, scissors or wire strippers | Tools | — |

You also need, just for setup (borrow them): a keyboard, a mouse, a monitor
or TV with HDMI, and your home Wi-Fi password. Speakers or a soundbar with a
3.5 mm/aux input make the best sound path; the TV's own "audio in" works too.

## Step 1 — Set up the Pi (👨‍🔧 with an adult)

1. On a computer, install **Raspberry Pi Imager** (from raspberrypi.com),
   put in the microSD card, and write **Raspberry Pi OS** to it. In the
   imager's settings gear, set a username/password and your Wi-Fi.
2. Put the card in the Pi, connect keyboard, mouse, and a monitor, and plug
   in the power. Wait for the desktop.
3. Open the black **Terminal** window and type these lines, pressing Enter
   after each (typing must be exact — capital letters matter):

   ```
   git clone https://github.com/socrtwo/adhush
   cd adhush
   sudo scripts/install-pi.sh
   cp config/adhush-passthrough.example.toml config/adhush.toml
   ```

That last file is the box's settings. You can leave it alone for now.

## Step 2 — Build the video path (no tools needed)

Think of it as making a copy of the TV signal so the Pi can watch too:

1. Unplug the HDMI cable that goes from your **cable box** to your **TV**,
   and plug it into the **splitter's IN** instead.
2. Splitter **OUT 1** → a cable → the **audio extractor's IN**.
3. Audio extractor **HDMI OUT** → a cable → your **TV**. (Picture works again!)
4. Splitter **OUT 2** → a cable → the **capture stick** → a blue USB 3.0
   port on the Pi.
5. Plug in the splitter's little power cord (it's USB, not wall-voltage).

Turn on the TV: you should see your channels exactly like before. If the
screen is black, try the splitter's other output, or a different HDMI cable.

## Step 3 — Build the sound path (scissors time, 👨‍🔧 check before power-on)

The sound will now travel: extractor → **through the relay** → speakers.
The relay is the switch AdHush clicks.

1. Take one 3.5 mm audio cable and **cut it in the middle**. Strip about
   2 cm of the outer cover from each cut end. Inside are small colored wires
   (usually red, white/green, and a bare or copper one — that bare one is
   "ground").
2. Twist the **ground** wires from both halves together — ground is never
   switched. Wrap the joint in tape.
3. Screw the **red** wire from one half into relay channel 1's **COM**
   terminal, and the red from the other half into channel 1's **NC**
   terminal. (NC means "normally closed" = sound flows when the relay is
   resting. That's on purpose: if the box ever crashes, your sound comes
   BACK, not stuck off.)
4. Do the same with the **white/green** wires on relay channel 2 (COM and NC).
5. Jumper wires from the Pi to the relay board:
   - Pi **pin 1 (3.3 V)** → relay **VCC**
   - Pi **pin 6 (GND)** → relay **GND**
   - Pi **pin 16 (GPIO 23)** → relay **IN1** — and also to **IN2** if your
     board doesn't gang the channels (a spare jumper split works).
   Look up "Raspberry Pi GPIO pinout" for a picture; count carefully.
6. Plug one half of the cut cable into the **extractor's audio out**, and
   the other half into your **speakers/soundbar**.

👨‍🔧 Have an adult check: ground joined, audio on COM+NC (not NO), 3.3 V (not
5 V) on VCC. Then power up. You should hear the TV through the speakers. Turn
the TV's own speakers all the way down — the relay path is now the sound.

## Step 4 — Wake it up

In the Pi's terminal, inside the `adhush` folder:

```
adhush probe
```

It should say `relay_hdmi ... ok`. Then, while a normal show (not a
commercial) is on, teach it what the channel's logo looks like:

```
adhush calibrate
```

And start it:

```
adhush run
```

Watch TV. When a commercial break starts you'll hear the relay give a tiny
*click* and the sound stops; when the show returns, *click*, sound is back.
The first day it mostly listens and learns; it gets noticeably smarter about
ads it has heard before.

**Bonus — phone remote control:** the settings file already turns on the
remote-control page. On your phone's browser go to `http://` + your Pi's
address + `:8675` — but the easy way is to open the file
`platforms/web/index.html` from the repo on any computer or phone on your
Wi-Fi. It shows PROGRAM or MUTED live, and has two important buttons: **"✗
Not an ad"** (if it muted your show by mistake — this also makes it forget
that mistake) and **"✓ Is an ad"** (to teach it one it missed).

**To make it start by itself** whenever it's plugged in:
`sudo systemctl enable --now adhush`

## If something's wrong

| Problem | Try |
|---|---|
| No picture on TV | Reseat HDMI cables; splitter power; swap splitter outputs |
| No sound at all | Cable halves swapped? Red/white on COM+**NC**? Grounds joined? |
| Sound never mutes | `adhush probe`; is `pigpiod` running? (`sudo systemctl start pigpiod`) |
| Mutes the show by mistake | Press "✗ Not an ad" on the phone page — it learns |
| `adhush run` errors | Read the message; `adhush doctor` lists what's missing |

One honest note: some sources copy-protect their HDMI signal so the capture
stick sees nothing. AdHush never tries to break that protection. If your
picture path won't capture, the box can still work in listen-only mode — see
`docs/hardware-passthrough-box.md`.
