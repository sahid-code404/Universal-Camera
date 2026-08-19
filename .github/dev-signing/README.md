# OmniCam development signing key

This directory contains a **public development-only signing key** encoded as base64 so hosted CI and local development builds use the same Android signature.

It exists only to let hardware testers install newer OmniCam development APKs over older ones without uninstalling the app each time.

**Never use this key for a production/Play Store release.** It is intentionally public and provides no production authenticity guarantee.
