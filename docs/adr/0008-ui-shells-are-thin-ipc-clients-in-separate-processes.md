# 8. UI shells are thin IPC clients in separate processes; only they may stop the core

Date: 2026-09-08

## Status

Accepted.

## Context

0.6.0 adds two things every platform wants: the core should stay running
once started, and there should be a small always-on-top window to see the
state and stop it. Both are UI concerns, and both sit next to a detection
loop whose whole design (ADR 0003, ADR 0004) is that nothing outside the
pipeline can perturb it.

The tempting shortcut is a window inside `adhush run`'s process, sharing the
event loop. That couples a Tk main loop to the detection loop, so a UI hang
delays a mute, and a UI crash kills detection.

## Decision

1. **Every UI is a thin client over the IPC surface (ADR 0006), in its own
   process.** The desktop overlay (`adhush overlay`) and the web front end
   speak only `ipc/protocol.py`. `adhush run` spawns the overlay as a
   subprocess and terminates it on exit; a UI failure is invisible to the
   pipeline.
2. **The overlay is standard library only** (tkinter + urllib). No GUI
   dependency enters the project; the overlay is skipped, with a message,
   where tkinter is absent or no display exists.
3. **Stopping the core is a protocol command, `shutdown`**, handled by a
   callback the CLI supplies. It is the only command that reaches outside the
   pipeline, and the only way a UI can end detection. Hiding or closing a
   window never does.
4. **"Always running" is the platform's own job**, not a daemon of ours:
   `adhush service` writes a systemd user unit, a launchd agent, or a Task
   Scheduler logon task. The supervisor restarts on failure and not on a
   clean exit, so ■ means stopped.
5. **The core serves the web front end** from `[ipc] web_root`, from a fixed
   allow-list of file names. Static files skip the token; the API keeps it.

## Consequences

- One more process per desktop session, and an IPC round trip (tens of
  milliseconds on loopback) between a button and the pipeline. Acceptable:
  the buttons are corrections, not the mute path.
- The overlay's styling logic is a pure function and its client is testable
  against a live `ApiServer` without a display; the window itself is
  exercised under Xvfb, not in unit tests.
- `[ipc]` must be enabled for any UI to exist. The passthrough example
  already enables it; the desktop examples now do too.
- Mobile remains a thin client until ADR 0007's on-device core is built.
