# Microcontroller builds (Arduino, ESP32, Pico, and friends)

People ask whether the passthrough box can be built on an Arduino instead of
a Raspberry Pi. Short, honest answer:

**A microcontroller cannot run the detection.** AdHush's core decodes video,
runs FFTs, perceptual hashes, and an SQLite fingerprint store in Python —
that needs a real computer (see minimum requirements at the bottom). What a
microcontroller does brilliantly is the **muscle**: the relay or IR LED that
actually mutes. Every build below is therefore *core + muscle*:

    capture ─▶ AdHush core (Pi / any PC) ─▶ microcontroller ─▶ relay or IR ─▶ silence

Two wire protocols make this work with **zero changes to AdHush**, because
the core's control backends are already generic:

- **USB serial**: the board pretends to be a Sharp AQUOS serial port and the
  `rs232_sharp` backend drives it. Commands arrive as 8 ASCII chars + CR:
  `MUTE1   ` (mute), `MUTE2   ` (unmute), `MUTE?   ` (query). Reply `OK`,
  `OK`, and `1`/`2`.
- **Wi-Fi TCP**: the board listens on a TCP port for one-line commands, and
  the `network_ip` backend is configured (in TOML, not code) to send them.

All boards drive the same relay wiring as the Pi build — audio through the
**normally-closed** contacts, coil energized only to mute, so every build
fails **unmuted** (docs/hardware-passthrough-box.md). Relay boards for 5 V
boards (classic Arduinos) must be 5 V modules; 3.3 V boards (ESP32, Pico,
ESP8266) need 3.3 V-logic modules — "opto-isolated with a jumper-selectable
JD-VCC" boards handle both.

## Shared parts (every variant)

| Part | Notes | ~Price |
|---|---|---|
| Relay module, 2-channel, opto-isolated | logic voltage to match your board | $7 |
| 3.5 mm audio cable to cut + screw-terminal wiring | same audio path as the Pi build | $6 |
| Jumper wires | VCC, GND, IN1/IN2 | $3 |
| The AdHush core machine | see minimum requirements below | — |

---

## Arduino Uno / Nano / Mega (USB serial, easiest)

**Extra parts:** the board ($10–25) and its USB cable. It stays tethered to
the core machine by USB — which also powers it.

**Wiring:** relay VCC→5V, GND→GND, IN1(+IN2)→**D7**. Audio through NC.

**Firmware** (paste into the Arduino IDE, board + port under Tools, Upload):

```cpp
// AdHush serial mute actuator: speaks the AQUOS MUTE subset.
const int RELAY_PIN = 7;
const bool ACTIVE_HIGH = true;   // module energizes on HIGH
bool muted = false;
char line[16]; int len = 0;

void apply() { digitalWrite(RELAY_PIN, (muted == ACTIVE_HIGH) ? HIGH : LOW); }

void setup() {
  pinMode(RELAY_PIN, OUTPUT);
  muted = false; apply();               // fail unmuted on reset
  Serial.begin(9600);
}

void loop() {
  while (Serial.available()) {
    char c = Serial.read();
    if (c == '\r' || c == '\n') {
      line[len] = 0;
      if      (!strncmp(line, "MUTE1", 5)) { muted = true;  apply(); Serial.print("OK\r"); }
      else if (!strncmp(line, "MUTE2", 5)) { muted = false; apply(); Serial.print("OK\r"); }
      else if (!strncmp(line, "MUTE?", 5)) { Serial.print(muted ? "1\r" : "2\r"); }
      else if (len) { Serial.print("ERR\r"); }
      len = 0;
    } else if (len < 15) { line[len++] = c; }
  }
}
```

**AdHush config** (on the core machine):

```toml
[control]
backend = "rs232_sharp"
verify_with_audio = false
[control.rs232_sharp]
port = "/dev/ttyACM0"   # or ttyUSB0; COM3 on Windows
baud = 9600
```

`adhush probe` should report `rs232_sharp ok — discrete`. Classic Arduinos
reset when the serial port opens; the firmware's reset state is *unmuted*,
so that's harmless.

## ESP32 / ESP8266 (Wi-Fi, no tether)

**Extra parts:** ESP32 DevKit (~$6, use 3.3 V relay module) + USB power.
The board can live at the speakers, across the room from the core.

**Firmware:**

```cpp
#include <WiFi.h>                 // ESP8266: <ESP8266WiFi.h>
const char* SSID = "your-wifi";
const char* PASS = "your-pass";
const int RELAY_PIN = 26;         // ESP8266: use D1 (GPIO5)
const bool ACTIVE_HIGH = true;
WiFiServer server(9760);
bool muted = false;

void apply() { digitalWrite(RELAY_PIN, (muted == ACTIVE_HIGH) ? HIGH : LOW); }

void setup() {
  pinMode(RELAY_PIN, OUTPUT); muted = false; apply();
  WiFi.begin(SSID, PASS);
  while (WiFi.status() != WL_CONNECTED) delay(250);
  server.begin();                 // give the board a DHCP reservation
}

void loop() {
  WiFiClient client = server.accept();   // older cores: server.available()
  if (!client) return;
  client.setTimeout(2);
  String cmd = client.readStringUntil('\n');
  cmd.trim();
  if      (cmd == "MUTE1") { muted = true;  apply(); client.print("OK\n"); }
  else if (cmd == "MUTE2") { muted = false; apply(); client.print("OK\n"); }
  else if (cmd == "MUTE?") { client.print(muted ? "1\n" : "2\n"); }
  else                     { client.print("ERR\n"); }
  client.stop();
}
```

**AdHush config** — pure TOML, no backend code needed:

```toml
[control]
backend = "network_ip"
verify_with_audio = false
[control.network_ip]
transport = "tcp"
host = "192.168.1.60"     # the board's reserved address
port = 9760
[control.network_ip.commands.mute_on]
send = "MUTE1\n"
expect = "OK"
[control.network_ip.commands.mute_off]
send = "MUTE2\n"
expect = "OK"
[control.network_ip.commands.state]
send = "MUTE?\n"
expect_on = "1"
expect_off = "2"
```

That gives discrete mute **with state readback** over Wi-Fi. Anyone on your
LAN can hit that port, so keep it on a trusted network.

**IR variant:** instead of a relay, drive an IR LED (through a 2N2222 + ~330 Ω
on the LED, never bare) with the IRremoteESP8266/IRremote library, mapping
`MUTE1`/`MUTE2` to your TV's discrete codes — or a toggle code with
`verify_with_audio = true`. Same TOML. This turns the ESP32 into a $6
network IR blaster aimed at any TV.

## Raspberry Pi Pico / Pico W (MicroPython)

Pico W behaves like the ESP32 build (same TCP protocol, `machine.Pin` on
GP16, `socket` server — ~25 lines of MicroPython). Plain Pico behaves like
the Arduino build over USB serial (`sys.stdin` line loop answering the MUTE
commands). Wiring: relay VCC→3V3, GND→GND, IN→**GP16**; the same
fail-unmuted rule: set the pin to the unmuted level first thing in `main.py`.

## Others

Teensy, STM32 "Blue Pill", Metro/Feather boards: use the Arduino sketch
unchanged (adjust the pin). Anything that can hold a TCP socket open can use
the ESP32 protocol. If your board can only *toggle* something (a Flirc-style
gadget, a wireless plug), set `verify_with_audio = true` so the core detects
desynchronization from the audio itself.

---

## Minimum requirements for the passthrough box

**The core** (runs capture + detection + control):

| Build | Minimum machine | Why |
|---|---|---|
| Full box (video + audio) | Raspberry Pi 4, **2 GB** (reference), or any x86 PC/laptop from ~2015 on | 720p30 decode + all detectors within the performance budget (CLAUDE.md); USB 3.0 for the capture stick |
| Audio-only box | Raspberry Pi 3B+/Zero 2 W, or any PC | only the silence/loudness/audio-fingerprint path; no capture stick needed |
| Software | Python 3.11+, numpy, ffmpeg; ~700 MB RAM free; ~1 GB disk | `scripts/install-pi.sh` installs it on a Pi |

**The signal path** (full box): powered 1×2 HDMI splitter, HDMI audio
extractor, USB 3.0 UVC capture device, and speakers/soundbar fed through the
relay. Audio-only build: just an audio tap (extractor or the TV's headphone
out) into the core's line-in, plus the relay in front of the speakers.

**The muscle:** either the Pi's own GPIO (the reference build — no
microcontroller at all), or any board from this page.

**Networking:** none required to mute. Wi-Fi/Ethernet is only for the ESP32
variant, the phone remote page (`[ipc]`), and installing software.

**Not sufficient, to save you the experiment:** Arduino-class boards as the
*core* (no video, no Python), Pi Zero 1 / Pi 1–2 for the full video box (CPU
short of the 720p30 budget — they're fine audio-only), and USB 2.0-only
capture sticks at 720p30 (choose MJPEG models if you must).
