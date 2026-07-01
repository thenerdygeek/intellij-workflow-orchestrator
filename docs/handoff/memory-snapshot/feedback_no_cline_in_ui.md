---
name: No "Cline" in any UI surface
description: The word "Cline" must not appear anywhere the user sees — settings labels/comments/tooltips, notifications, webview text, dialogs, or status strings. Code comments documenting port-provenance are fine.
type: feedback
originSessionId: ce1ef373-a40b-4315-b385-0edac045e40b
---
"Cline" must not appear in any user-facing string in this plugin. That covers:

- Settings page labels, checkbox/combo comments, tooltips, placeholder text (any `Configurable` file under `core/**/settings/` or `agent/**/settings/`)
- JCEF webview UI (any `.ts`/`.tsx`/`.html` under `agent/webview/src/` and compiled output in `agent/src/main/resources/webview/dist/`)
- Notifications (`WorkflowNotificationService` calls and their message args)
- Dialog titles, button labels, error messages shown to the user
- Status bar / tool-window status strings
- Agent chat UI rendered content

**Why:** the plugin is a standalone product, not a Cline fork. Even though `:agent` is partially ported from Cline (per `feedback_faithful_port_cline.md`), the user brand/identity should never bubble into the UI.

**How to apply:**
- When adding UI copy, never write phrases like "Cline-style", "like Cline", "ported from Cline" where the string renders in the UI.
- Source-code KDoc / inline `//` comments documenting port-provenance ARE allowed (they're developer-facing) and should be preserved per `feedback_faithful_port_cline.md`.
- If you're unsure whether a string is UI-visible, trace it: if it ends up in a `checkBox(...)`, `.comment(...)`, `.label(...)`, `JBLabel(...)`, React component text, notification title/body, or dialog text — it is UI.
- On cleanup passes, grep `rg -ni 'cline'` scoped to `**/settings/`, `webview/src/`, and `webview/dist/` — anything else is internal.

Originally enforced 2026-04-24 during the `refactor/cleanup-perf-caching` branch Commit-6 follow-up, when an `AgentAdvancedConfigurable` combo-box comment read "stream_interrupt: execute each tool as soon as it appears (Cline-style)."
