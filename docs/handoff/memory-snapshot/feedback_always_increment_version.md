---
name: Always increment version, never update existing release
description: When releasing, always bump to a new version number. Never delete and recreate a release with the same version tag.
type: feedback
---

Always increment the version number for each release. Never delete an existing release and recreate with the same version tag.

**Why:** The user may have already downloaded and installed a previous version. If the tag is reused, there's no way to distinguish builds. Each release must have a unique version.

**How to apply:** If the previous release was v0.57.0-beta, the next one should be v0.57.1-beta or v0.58.0-beta, not v0.57.0-beta again.
