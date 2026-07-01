---
name: Release timing and versioning
description: Only create releases when explicitly asked; always bump patch version
type: feedback
originSessionId: 729fdfed-bfc2-49ec-abd0-edd3bda0ded5
---
Do NOT create a pre-release or release automatically after a build. Only release when the user explicitly says "pre-release" or "release."

**Why:** User wants control over when a release is cut, not auto-release after every fix.

**How to apply:** After a clean build, stop. Do not push tags or create GitHub releases unless the user says so. When a release IS requested, increment the patch segment of the version (e.g. 0.83.0-beta → 0.83.1-beta) rather than reusing the existing tag.
