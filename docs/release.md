# Release 0.26.0 — what runs where

The honest platform matrix for this release. "Core" is the Python engine
(`adhush run`); "UI" is what you look at and tap.

| Platform | Core runs here? | Always running | Always-on-top mini window | Download |
|---|---|---|---|---|
| **Windows 10/11 (x64)** | yes | `adhush service install` (Task Scheduler, at logon; uses the silent `adhushw.exe`) | `adhush overlay` (Tk) — also the Mini window button in Chrome/Edge | `adhush-0.26.0-windows-x64.zip`; or the wheel |
| **macOS (Apple silicon)** | yes | `adhush service install` (launchd agent) | `adhush overlay` (Tk); Mini window in Chrome/Edge | `adhush-0.26.0-macos-arm64.zip`; Intel Macs: the wheel with python.org Python |
| **Linux desktop (x64 / arm64)** | yes (reference) | `adhush service install` (systemd --user) | `adhush overlay` (Tk; `apt install python3-tk` if the overlay says so) | `adhush-0.26.0-linux-x64.tar.gz` / `-linux-arm64.tar.gz`; needs glibc 2.35+ (Ubuntu 22.04, Debian bookworm) |
| **Raspberry Pi 4 / 5** | yes (reference) | `adhush service install`; headless box: `scripts/install-pi.sh` | n/a | `adhush-0.26.0-raspberry-pi.zip` — the arm64 binary, the listener config and the PDF guides; 64-bit Pi OS bookworm |
| **ChromeOS** | yes, in the Linux container | `adhush service install` inside the container | Chrome's Mini window (Document Picture-in-Picture) floats above everything | the Linux binary for the Chromebook's chip (x64 or arm64) in Crostini, then open the served page in Chrome |
| **Web** | no — thin client | n/a | Mini window (Document PiP in Chromium 116+); popup elsewhere | open `http://<core>:8675/` — the core serves it; the page itself is `adhush-web-0.26.0.zip` |
| **Android** | **yes — on-device app** (since 0.7.0; ten methods, stream learning, the remote, the set-up wizard, any brand of TV) | microphone foreground service | notification + Quick Settings tile | `adhush-0.26.0-android-debug.apk`, sideloaded; or the web app |
| **iOS** | **no — thin client**; there is no native app and none is planned without an Apple developer account | same | none; Safari has no PiP for documents | open the core's address in Safari → Share → *Add to Home Screen*: the page is a PWA and installs as an app |

Every binary is smoke-tested on its own runner before it is published:
`--version`, `init`, `doctor`, and an offline replay of a synthesised
fixture. `ffmpeg` on PATH is the one thing every desktop needs for live
capture (`adhush doctor` says so). Unsigned binaries: Windows SmartScreen
and macOS Gatekeeper will ask once (More info → Run anyway; right-click →
Open, or `xattr -d com.apple.quarantine adhush`).

## What "always running" means

Once installed as a service the core starts at login, restarts on failure,
and keeps going until you stop it. Stopping is deliberate and explicit:

- the ■ button on the mini window, or ■ **Stop AdHush** on the web page,
  sends the `shutdown` command — the core unmutes (the controller releases on
  close) and exits; the service supervisor does **not** restart it, because a
  clean exit is a successful exit;
- `adhush service uninstall` removes the login entry.

Closing the mini window with the right-click menu's *Hide* does not stop the
core. Only ■ does.

## New in 0.26.0

Any brand of TV from the phone (ADR 0025): the wizard finds Sharp,
Samsung, LG, Sony, Roku TV, Vizio and DLNA sets and proves each way in;
infrared for seven brands.

## New in 0.25.0

The wizard's *Find my TV*: Wi-Fi scan, serial cable and infrared, each
tried and reported, the first that works chosen (ADR 0022, amended).

## New in 0.24.0

Nine more ways to know (ADR 0023, 0024): the rating box, ad-unit
lengths, uniform separators, captured cutscenes, the stereo switch, the
ATSC/DVB watermark probe, an XMLTV schedule, SCTE-35 cues from a
transport stream (`ts_stream`: HDHomeRun, DVB) and an opt-in crowd feed
(`adhush crowd serve`). On the phone, ad units ride on Quiet gaps and the
rating box on the camera.

## New in 0.23.0

The Android set-up wizard: three minutes of questions, listening and
looking, then a suggested configuration with reasons, applied and opened
for review (ADR 0022). Method counts corrected everywhere.
`docs/ideas-backlog.md` lists what the next releases could take.

## New in 0.22.0

`aspect_change` on the HDMI and screen paths — a spot that arrives in
another picture shape votes for as long as it holds it — and a crest-factor
cue inside loudness, so a spot compressed flat shows even when it is no
louder (ADR 0021).

## New in 0.21.0

Method 10, the channel's break jingle, learned from the breaks the other
methods end; the ad badge read off a stream's player; learned break lengths
that cap every mute and say how long is left; manual ducks up to 300 s with
a `+30 s` countdown everywhere (ADR 0020).

## New in 0.20.0

One-file binaries for Windows, macOS, Linux (x64 and arm64) and a Raspberry
Pi bundle, next to the APK, the web app and the wheel (ADR 0019);
`adhush init` for a first config.

## New in 0.19.0

Android learns the commercials from a channel's live stream by hearing its
own playback (ADR 0018), and its memory (breaks, scripts, clock) can be
shared and imported between phones. `docs/print/AdHush-guide-stream-learning.pdf`.

## New in 0.18.0

The break clock (method 9, a learned minute-of-hour prior), timed manual
ducks (30/60/90/120 s) and a remote control with every TV key and every
AdHush control on one screen — phone, web page and Python core (ADR 0017).

## New in 0.17.0

Duck compensation for room microphones (ADR 0016, after admuffs) in both
the Python listener and the phone, and three sizes of local AI on Android
(`docs/print/AdHush-guide-local-AI-OnePlus.pdf`).

## What this release does not include

- **No native Android or iOS binaries.** The Android on-device app is
  designed (`docs/android-app-design.md`, ADR 0007) but not implemented; iOS
  has no on-device design yet. On both, this release is the installable web
  app talking to a core elsewhere on the network. That is a real, working
  remote control and status display — it is not the phone doing the
  detecting.
- **No packaged desktop installers** (`.msi`, `.dmg`, `.deb`). This is a
  Python wheel plus a service installer; it assumes Python 3.11+ on the box.
- The Tk overlay was exercised under Xvfb on Linux (render, live update,
  drag-and-remember, ■ stops the core). It was **not** run on a real Windows
  or macOS desktop in this release. Report anything odd.

## Reproducing the artifacts

```
python -m build                       # dist/adhush-0.17.0-py3-none-any.whl, .tar.gz
(cd platforms/web && zip -r ../../dist/adhush-web-0.26.0.zip .)
```

Pushing a `v*` tag runs `.github/workflows/release.yml`, which does the same
and attaches the results to the GitHub Release.
