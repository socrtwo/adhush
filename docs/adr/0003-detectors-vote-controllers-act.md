# 3. Detectors vote, controllers act

Status: accepted

## Decision
Detectors never mute. They emit confidence votes; `fusion` and `state` decide;
controllers execute. Device quirks live in profiles, not detector code.

## Consequences
Adding a detector cannot break device support, and adding a device cannot break
detection. Cross-cutting hacks are forced into fusion weights where they are
visible and tunable.
