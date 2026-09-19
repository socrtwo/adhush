# ADR 0019 — One-file binaries for every desktop, and a release per platform

Status: Accepted. 2026-09-15.

## Context

Until 0.19.0 a release carried a wheel, the Android APK and the web page.
Windows, macOS, Linux, ChromeOS and the Raspberry Pi all meant "install
Python 3.11, then the wheel", which is a real barrier for the people the
build guides are written for. The owner asked for a release version of
AdHush for each platform.

## Decision

- **PyInstaller one-file binaries** of the Python core, built by the release
  workflow on GitHub's own runners: Windows x64, macOS arm64, Linux x64 and
  Linux arm64 (Ubuntu 22.04 runners, so the Linux binaries run on any
  distribution with glibc 2.35 or newer: Ubuntu 22.04+, Debian bookworm,
  Raspberry Pi OS bookworm, the ChromeOS Linux container). The web front
  end, the device profiles and the example configs travel inside the
  binary (`util/resources.bundled()` finds them in a binary or a
  checkout). ffmpeg stays a runtime dependency on PATH; `adhush doctor`
  says so when it is missing. Windows also gets `adhushw.exe`, a
  windowless twin, which `adhush service install` uses for the logon task.
- **`adhush init`** writes a starter config and the profile library into
  the current folder, so a binary is usable without a checkout:
  `init`, edit, `doctor`, `probe`, `run`, `service install`.
- **A Raspberry Pi bundle**: the arm64 binary, the listener config and the
  PDF guides in one zip, with a six-line README. `scripts/install-pi.sh`
  (the pip route) stays for people who want a venv.
- **iOS** has no native app and will not get one without an Apple developer
  account; the web page is a PWA (manifest, service worker, icons) that
  installs from Safari's Add to Home Screen, and the release notes say so
  plainly.
- **Every binary is smoke-tested on its runner** before publishing:
  `--version`, `init`, `doctor`, and an offline `replay` of a synthesised
  fixture, which exercises numpy, the detectors and the bundled profiles.
- **Intel Macs** get the wheel; GitHub's Intel macOS runners are being
  retired and a build nobody can verify is worse than an honest table row.

## Consequences

- One tag produces nine downloads. The release notes carry a table of
  which file is for which platform; `docs/release.md` has the matrix.
- Release builds take longer (four extra runners) and can fail on a
  runner AdHush's own CI never exercises; the smoke tests are there to
  catch that before a broken binary is published.
- Tags with a hyphen (`v0.20.0-rc1`) publish as pre-releases, so a
  workflow change can be tried without a real release.
