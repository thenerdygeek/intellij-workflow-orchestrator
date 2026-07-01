---
name: Release means clean build + GitHub Release, tested on Windows
description: When asked to release, do a clean build and create a GitHub Release with the ZIP attached. User tests the plugin on a Windows machine.
type: feedback
---

When the user says "release":
1. Bump `pluginVersion` in `gradle.properties`
2. Run `./gradlew clean buildPlugin`
3. Push commits to GitHub
4. Create a GitHub Release with `gh release create` attaching the built ZIP from `build/distributions/`

**Why:** The user develops on macOS but tests/runs the plugin on a Windows machine. The release ZIP is what they install on Windows.

**How to apply:** Always do a clean build (not incremental). Always push. Always create the GitHub Release with the ZIP attached. Don't ask — just do it.
