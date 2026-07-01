---
name: Bump version before building release ZIP
description: Always bump pluginVersion in gradle.properties BEFORE running buildPlugin, so the ZIP filename matches the release tag
type: feedback
---

When releasing, bump `pluginVersion` in `gradle.properties` before `./gradlew buildPlugin`. Otherwise the ZIP will have the old version number and get attached to the wrong release tag.
