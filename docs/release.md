# Release 0.7.3 — what runs where

The honest platform matrix for this release. "Core" is the Python engine
(`adhush run`); "UI" is what you look at and tap.

| Platform | Core runs here? | Always running | Always-on-top mini window | How to get it |
|---|---|---|---|---|
| **Windows** | yes | `adhush service install` (Task Scheduler, at logon) | `adhush overlay` (Tk) — also the Mini window button in Chrome/Edge | `pip install adhush-0.7.3-py3-none-any.whl` |
| **macOS** | yes | `adhush service install` (launchd agent) | `adhush overlay` (Tk); Mini window in Chrome/Edge | wheel; tkinter ships with python.org Python |
| **Linux / Raspberry Pi** | yes (reference) | desktop: `adhush service install` (systemd --user); headless box: `scripts/install-pi.sh` | `adhush overlay` (Tk; `apt install python3-tk`) | wheel or `pip install -e .` |
| **ChromeOS** | yes, in the Linux container | `adhush service install` inside the container | Chrome's Mini window (Document Picture-in-Picture) floats above everything; the Tk overlay works inside the container's window | wheel in Crostini, then open the served page in Chrome |
| **Web** | no — thin client | n/a | Mini window (Document PiP in Chromium 116+); popup elsewhere | open `http://<core>:8675/` — the core serves it |
| **Android** | **yes — on-device app** (since 0.7.0; Sharp LC-46LE830U via `VOLM` ducking) | microphone foreground service | notification + Quick Settings tile | `adhush-<ver>-android-debug.apk` from the release, sideloaded; or the thin-client web app as before |
| **iOS** | **no — thin client** | same | none; Safari has no PiP for documents | open the address in Safari → Share → *Add to Home Screen* |

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
python -m build                       # dist/adhush-0.7.3-py3-none-any.whl, .tar.gz
(cd platforms/web && zip -r ../../dist/adhush-web-0.7.3.zip .)
```

Pushing a `v*` tag runs `.github/workflows/release.yml`, which does the same
and attaches the results to the GitHub Release.
