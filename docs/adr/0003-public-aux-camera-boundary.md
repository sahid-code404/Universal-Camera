# ADR 0003 — Public Auxiliary Camera Boundary

## Status
Accepted

## Decision
OmniCam supports every auxiliary camera exposed to a normal third-party application through public Android camera APIs.

It will not bypass OEM/system-camera restrictions using root, private APIs, signature permissions, or hidden-API hacks.

## Consequences
Some devices may physically contain more sensors than OmniCam can access. Diagnostics must report this limitation clearly and the app must degrade gracefully.
