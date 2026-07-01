---
name: Release means GitHub Release with ZIP
description: When asked to release, create a GitHub release with gh release create, attaching the built ZIP
type: feedback
---

When asked to release:
1. Bump `pluginVersion` in `gradle.properties`
2. `./gradlew clean buildPlugin`
3. Push commits
4. Create GitHub release: `gh release create v{version} build/distributions/*.zip --title "v{version}" --notes "..."`

**Why:** User wants installable ZIPs published in GitHub Releases, not just pushed commits.

**How to apply:** Every time the user says "release" or "release it", do all 4 steps including the `gh release create`.
