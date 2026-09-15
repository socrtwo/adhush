# Build the AdHush TV listener — beginner's guide

This is the Raspberry Pi version of what the phone app does: a little
computer on a shelf that **watches and listens** to the TV, and turns the
TV down to a whisper over Wi-Fi when a commercial starts, then back up when
the show returns. No HDMI boxes, no relay, no scissors, no soldering: five
things plug together with USB cables.

It is written so a 12-year-old can follow it. Ask an adult for the two
steps marked 👨‍🔧. Everything runs on low-voltage USB power; nothing here
touches a wall outlet except the Pi's own power brick.

**Time:** an afternoon. **Difficulty:** if you can type commands carefully
and follow a recipe, you can do this.

## What you're building

```
   ┌──────────┐      Wi-Fi: "turn down to 4"   ┌────────────────┐
   │   TV     │ <──────────────────────────────│  Raspberry Pi  │
   │ (Sharp)  │                                 │  + webcam  👁  │
   │          │ ───── light and sound ────────> │  + microphone 👂│
   └──────────┘                                 └────────────────┘
```

The webcam looks at the TV and watches the little channel logo in the
corner (broadcasters call it the **bug**). The microphone listens for the
quiet gaps and loudness jumps around commercials, and remembers the sound
of every break you teach it. When it is sure, it tells the TV to turn down.

## The parts (about $80–110, less if you already own a Pi)

| # | Part | What it does | Rough price |
|---|------|--------------|-------------|
| 1 | Raspberry Pi 4 (2 GB or more) + official USB-C power supply | The brain | $45–60 |
| 2 | microSD card, 32 GB | The brain's memory | $8 |
| 3 | USB webcam, 1080p, with a **built-in microphone** (most have one) | The eyes and the ears | $20–30 |
| 4 | *(optional)* USB microphone, if the webcam has none or hears badly | Better ears | $10–15 |
| 5 | A shelf or small tripod 6–10 feet in front of the TV | Somewhere to stand | — |

You also need, just for setup (borrow them): a keyboard, a mouse, a monitor
with HDMI, and your home Wi-Fi password. And the TV must be a Sharp AQUOS
that is on the same Wi-Fi, with **IP Control** switched on (Step 3).

## Step 1 — Set up the Pi (👨‍🔧 with an adult)

1. On a computer, install **Raspberry Pi Imager** (from raspberrypi.com),
   put in the microSD card, and write **Raspberry Pi OS (64-bit)** to it —
   the plain, recommended one at the top of the list, **not** *Legacy* and
   **not** *Lite*. In the imager's settings gear, set a username/password
   and your Wi-Fi.
2. Put the card in the Pi, plug in keyboard, mouse, monitor and the webcam,
   then the power. Wait for the desktop.
3. Open the black **Terminal** window and type these lines, pressing Enter
   after each (typing must be exact — capital letters matter):

   ```
   git clone https://github.com/socrtwo/adhush
   cd adhush
   sudo scripts/install-pi.sh
   cp config/adhush-listener.example.toml config/adhush.toml
   ```

   The last line makes your settings file. It is *yours*: it is never sent
   anywhere, and git ignores it, so the TV password you will type into it
   stays on the Pi.

## Optional — put an SSD in the NESPi 4 cartridge (👨‍🔧 with an adult)

Building the Pi into a Retroflag NESPi 4 case? Its front slot takes a
"cartridge" that holds a 2.5-inch SATA SSD. AdHush does not need one: it
stores only numbers, and a microSD card has room for years of them. But an
SSD boots faster, shrugs off the thousands of tiny writes a box makes over
a year, and is the part most likely to survive the case's POWER button
cutting the power mid-write. If you have one, here is how to use it. Do
this **after** Step 1 has worked from the microSD card, so you always have
a card that boots. (The illustrated version is Step 2½ of
`docs/print/AdHush-beginner-guide-listener-NESPi4.pdf`.)

**You need:** a 2.5-inch **SATA** SSD, 7 mm thick (the normal laptop kind;
120 GB is plenty). Not an M.2 or NVMe stick — those do not fit the
cartridge. The cartridge shell comes in the case box.

1. **Put the SSD in the cartridge.** Open the cartridge shell (the small
   screws), slide the SSD onto the connector inside until it clicks, and
   screw the shell shut. Push the cartridge into the slot behind the big
   front flap. Nothing else to wire: the cartridge plugs into the Pi
   through the case's own connection.
2. **Copy the card onto the SSD.** With the Pi running from the microSD
   card (Step 1 done, AdHush installed), open the menu → **Accessories →
   SD Card Copier**. *Copy From Device:* the microSD card. *Copy To
   Device:* the SSD (it shows up as a USB drive with the SSD's name). Tick
   **New Partition UUIDs** and press **Start**. It takes a few minutes.
   Everything comes along — AdHush, your settings file, and any breaks it
   has learned.
3. **Tell the Pi to boot from USB.** In the Terminal:

   ```
   sudo raspi-config
   ```

   Go to **Advanced Options → Boot Order → NVMe/USB Boot**, then Finish.
   If it offers to update the bootloader, say yes. (Raspberry Pi 4 boards
   made before late 2020 need this update once; a fresh Raspberry Pi OS
   does it for you.)
4. **Switch over.** Type `sudo poweroff`, wait for the green light to stop
   blinking, press POWER, take the microSD card out of the slot on the
   right side, and press POWER again. The Pi now starts from the SSD.
   Keep the card somewhere safe: it is your spare.
5. **Check.** Type `findmnt /` — it should say `/dev/sda2`, not
   `/dev/mmcblk0p2`. Then carry on with Step 2 below.

Two things to know:

- The cartridge uses a USB 3.0 link, and USB 3.0 can put noise on 2.4 GHz
  Wi-Fi. If the TV becomes "unreachable" now and then after adding the
  SSD, join your router's 5 GHz network, or plug an Ethernet cable into
  the back of the case — the surest fix.
- With SAFE SHUTDOWN off, the POWER button cuts the power like pulling the
  plug. The listener is meant to stay on, but when you do want it off,
  type `sudo poweroff` first and press POWER when the green light has
  stopped blinking.

| Problem | Try |
|---|---|
| It still boots from the card | Take the card out; re-check Boot Order (step 3) |
| Rainbow screen or "no boot device" | Reseat the cartridge, then the SSD inside it; put the card back to prove the Pi is fine |
| Very slow or freezes with the SSD | A few USB-to-SATA bridges misbehave with the Pi 4. Ask an adult to search "Raspberry Pi 4 usb-storage quirks" for the one-line fix in `/boot/firmware/cmdline.txt` |

## Step 2 — Find the eyes and ears

Type:

```
adhush doctor
```

It prints a list of **video devices** (the webcam, usually `/dev/video0`)
and **sound cards** with numbers. Find the webcam's microphone (or the USB
microphone) in the sound-card list — it says something like
`card 1: Webcam`. Now open the settings file:

```
nano config/adhush.toml
```

and check two lines near the top:

```toml
device = "/dev/video0"             # the webcam
audio_device = "alsa:plughw:1,0"   # change the 1 to your microphone's card number
```

Save with **Ctrl+O**, Enter, then leave with **Ctrl+X**.

## Step 3 — Let the TV take orders (👨‍🔧 with an adult)

On the TV's remote press **MENU**, then go to **Initial Setup → Internet
Setup → Network Setup → IP Control Setup** and set it to **Enable**. Write
down three things the TV shows there: its **IP address** (four numbers with
dots, like 192.168.1.12), the **port** (usually 10002), and the **login ID
and password** if it asks for them (the default login is often `admin`).

Back on the Pi, open the settings file again (`nano config/adhush.toml`)
and fill in the `[control.network_ip]` part:

```toml
host = "192.168.1.12"     # the TV's address
port = 10002
login_id = "admin"
login_password = "the password from the TV menu"
duck_level = 4            # how quiet a commercial gets (4 is a whisper)
```

Now test it — this is the same thing the app does when a commercial comes on:

```
adhush probe --active
```

It connects to the TV, asks the volume, turns it down to 4, waits two
seconds, and turns it back up. **If the sound dipped and came back, the
TV path works.** If it says *"hung up before asking for a login"*, something
else is connected to the TV — the phone app, for instance. The Sharp only
allows one control connection at a time; stop the other one and try again.

## Step 4 — Point the eyes

Put the Pi and webcam on the shelf, six to ten feet from the TV, roughly
straight in front of it. The **whole TV must be in the webcam's picture**
with a little room around it. If the TV runs off the edge of the picture,
AdHush will not use the camera at all (it never guesses from half a TV),
so step back or turn the webcam.

With a **show** on — not a commercial — teach it what the channel's bug
looks like:

```
adhush calibrate
```

It watches for 20 seconds and saves the bug. Do it again if a commercial
came on in the middle. The bug is expected in the **bottom-right** corner;
if your channel keeps it somewhere else, change the `roi` line under
`[detect.logo_absence]` in the settings file (`x` and `y` are how far
across and down, from 0 to 1; `w` and `h` are its size). Set it up once per
channel.

## Step 5 — Wake it up

```
adhush run
```

Watch TV. When a commercial break starts, the sound drops to a whisper;
when the show returns, it comes back. The first day it mostly listens and
learns. It gets noticeably smarter about breaks it has heard before.

**On your phone:** open a browser and go to `http://` + the Pi's address +
`:8675` (the Pi prints its address when it starts, and `hostname -I` shows
it). The page shows SHOW / MUTED / TEACHING live, and has the three
buttons that make it smart:

- **✓ Is an ad** — press the moment a commercial break starts. The TV
  turns down and stays down.
- **▶ Show's back** — press the second your show returns. The sound comes
  back and everything in between is remembered as a break.
- **✗ Not an ad** — press if it turned your show down by mistake. The
  sound comes back at once, whatever caused it is dropped, the camera has
  to see the bug again before it may say the bug is gone, and for one
  minute nothing may turn the TV down.

Do the ✓ / ▶ pair for five or six breaks. After that it recognises those
commercials by itself within a few seconds.

**To make it start by itself** whenever it is plugged in:
`sudo systemctl enable --now adhush`

## The methods

The listener uses four of the phone app's six ways of spotting a commercial:

| Method | What it uses | Can turn the TV down alone? |
|---|---|---|
| Quiet gaps | microphone: the short silence between show and ad | no — needs a second opinion |
| Loudness jumps | microphone: the sound jumping up and staying up | no — needs a second opinion |
| Remembered breaks | microphone: fingerprints of the breaks you taught it | yes |
| Channel bug | webcam: the logo corner, whole TV in view | yes |

Spoken words and on-screen captions (the phone's methods 5 and 6) are not
on the Pi yet. Because the Pi stands still on a shelf, the camera method is
steadier here than in a hand.

## Words used here

- **Bug** — the small channel logo in a corner of the picture during a
  show. It is taken off the screen during commercials.
- **Duck / turn down** — the TV goes to volume 4 instead of muting, so the
  microphone can still hear when the show is back.
- **Remembered break** — the sound of a break you bracketed with ✓ and ▶,
  stored as numbers (never a recording).

## If something's wrong

| Problem | Try |
|---|---|
| `adhush probe --active` says *unreachable* | Is the TV on and on the same Wi-Fi? Is the address right? Is IP Control enabled on the TV? |
| It says *rejected IP control login* | The login ID or password in `config/adhush.toml` does not match the TV's menu |
| It says *hung up before asking for a login* | Something else is connected — stop the phone app (Stop button) and try again |
| The phone page says *camera: whole TV not in view* | Move the webcam back or turn it until the whole TV, with a border, is in the picture |
| The phone page says *camera: looking for the bug* for minutes | Redo `adhush calibrate` during a show, or fix the `roi` corner |
| It turns the show down by mistake | Press **✗ Not an ad**. If it keeps happening on one channel, recalibrate |
| It never turns anything down | Teach it five breaks with ✓ / ▶. Check the microphone card number (Step 2) |
| Sound stuck quiet after the Pi lost power | Start `adhush run` again: it puts the volume back on its own. Or use the remote |
| `adhush run` errors | Read the message; `adhush doctor` lists what is missing |
