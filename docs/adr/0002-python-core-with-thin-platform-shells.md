# 2. Python core with thin platform shells

Status: accepted

## Decision
One Python core does capture, detection, fingerprinting, and control. Platform
front ends are thin clients over `ipc/api.py` rather than reimplementations.

## Consequences
Mobile and Web need either an embedded runtime or a networked core on the LAN.
Web additionally needs WASM ports of the hot detector paths. Accepted: a single
detection codebase is worth more than native purity on every platform.
