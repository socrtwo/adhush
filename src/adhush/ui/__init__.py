"""Local user-interface shells for the core: thin IPC clients, never pipeline code.

The overlay here and the web front end in ``platforms/web`` speak only the
``ipc/protocol.py`` wire schema (ADR 0006), so a UI bug can never reach a
detector or a controller — and a UI crash never stops detection, because
each runs in its own process.
"""
