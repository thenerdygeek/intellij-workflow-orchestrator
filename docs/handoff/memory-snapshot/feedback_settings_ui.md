---
name: Always add settings UI for new config fields
description: When adding new fields to PluginSettings, always add corresponding UI in the settings page — don't leave config fields without a way to set them
type: feedback
---

When adding new fields to PluginSettings (or any persisted state), always verify there's a corresponding UI element in the settings pages (ConnectionsConfigurable, WorkflowSettingsConfigurable, etc.) so the user can actually configure them. Don't just use them in code and assume they'll be set.
