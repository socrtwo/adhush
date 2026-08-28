# 6. IPC surface is HTTP plus Server-Sent Events

Date: 2026-08-28

## Status

Accepted

## Context

Phase 4 exposes the core over `ipc/api.py` so platform shells stay thin (ADR
0002). The scaffold docstring anticipated "HTTP/WebSocket". Every feature the
API needs — status, override, confirm/reject ad, live detector trace — is
request/response plus one-directional server-to-client streaming. WebSocket
would add a hand-rolled RFC 6455 implementation (the core is stdlib-only by
policy) to gain bidirectional framing nothing currently uses.

## Decision

`ipc/api.py` serves plain HTTP (`GET /status`, `POST /command`) plus a
Server-Sent Events stream (`GET /events`). Payloads on both are the versioned
JSON messages defined in `ipc/protocol.py`, so a later WebSocket transport —
if a front end ever needs client streaming — carries the identical schema.

Security posture: binds `127.0.0.1` by default; an optional bearer token
gates every request when the core is deliberately exposed on a trusted LAN
(the mobile front-end pattern); CORS is permissive so a `file://` or
LAN-hosted page can connect. The API never serves media, only state — no
captured audio or video leaves the device, per the repo's out-of-scope rules.

## Consequences

- Browser front ends need only `EventSource` and `fetch`; `platforms/web`
  is a single static HTML file.
- The stdlib `ThreadingHTTPServer` suffices; no new dependencies.
- Client-to-server streaming (if ever needed) means adding a transport, not
  changing the schema.
