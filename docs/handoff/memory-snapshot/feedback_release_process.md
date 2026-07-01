---
name: Release means build ZIP + GitHub Release
description: ALWAYS create GitHub Release with ZIP attached — release is NOT complete until gh release create is done. Never skip this step.
type: feedback
---

When the user says "release" or "commit and release":
1. Bump the version in gradle.properties
2. Commit and push to GitHub (`git push`)
3. Build the plugin ZIP (`./gradlew clean buildPlugin`)
4. **MANDATORY: Create a GitHub Release** (`gh release create vX.Y.Z build/distributions/*.zip --title "vX.Y.Z" --notes "changelog"`) with the ZIP attached — a release is NOT done without this step
5. Report the release URL

**Do NOT consider a release complete until the GitHub Release is created with the ZIP attached.** This was explicitly corrected by the user.
