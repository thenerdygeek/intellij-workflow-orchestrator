# Visual Support — Master Kill Switch

**Status:** Design approved 2026-05-13. Awaiting implementation.
**Branch:** `fix/automation-handover-quality-tabs`
**Scope:** kill switch only. Underlying image-pipeline bugs are out of scope (see memory `project_image_attach_pipeline`).

## Problem

The image-attach pipeline is unstable in shipped builds (0.83.47–0.83.52, plus follow-ups). While it is debugged, the user needs a single setting that disables every visual / image surface in the plugin so the rest of the agent stays usable. Today there are six image-related fields in `PluginSettings`, but none of them gates all five surfaces that handle images (BrainRouter routing, BrainRouter stream encoding, `view_image` tool registration, JCEF upload, agent-loop tool-output autoload). The user wants one switch that silences all of them.

## Decision summary

| Question | Decision |
|---|---|
| Flag shape | Repurpose existing `PluginSettings.enableImageInput` as the master kill switch. No new field. UI label rewritten. |
| Default state | Flip from `true` to `false` (panic-button posture per user request). |
| Legacy image history | Strip `ContentPart.Image` blocks at the request boundary in `BrainRouter`. Disk history untouched. |
| `view_image` tool when OFF | Unregister entirely from `ToolRegistry` (mirrors plan-mode write-tool filtering). |
| Sub-flags when master OFF | `enableToolImageAutoload`, MIME whitelists, size cap, per-turn cap become no-ops at runtime; greyed out in Settings UI via `enabledIf`. |
| Plugin version | No bump in this PR; version bumps are user-triggered. |

## Settings model

File: `core/src/main/kotlin/com/workflow/orchestrator/core/settings/PluginSettings.kt:246`

```kotlin
@JvmField
var enableImageInput: Boolean = false   // was true; scope expanded to master kill switch
```

Field name is unchanged so existing `options/` XML stays valid. Users who explicitly enabled the field via the Settings UI keep their value on upgrade; users who never touched it inherit the new `false` default.

All five existing image sub-fields are retained unchanged:

- `enableToolImageAutoload`
- `imageMimeWhitelist`
- `toolImageAutoloadMimeWhitelist`
- `imageMaxBytes`
- `imagesPerTurnCap`

## Enforcement gates

Every gate reads `PluginSettings.getInstance().enableImageInput` directly. No derived state, no cached "is visual on" boolean.

### Gate 1 — Routing (`BrainRouter.kt:154-162`)

`hasImageParts()` returns `false` when master is OFF, regardless of actual content. Turns containing legacy image blocks route through `/messages` instead of the multimodal `/stream` path.

### Gate 2 — Stream body encoding (`BrainRouter.kt:312-347`)

In `buildStreamRequest()`, the base64-encoding loop skips `ContentPart.Image` parts when master is OFF. The resulting `StreamContentPart` list contains text only. This is the same boundary that handles legacy images already in session history — no separate history-strip code path exists.

### Gate 3 — `view_image` tool registration (`AgentService.kt:~850`)

Existing line:

```kotlin
safeRegisterDeferred("File") { ViewImageTool() }
```

becomes conditional:

```kotlin
if (settings.enableImageInput) {
    safeRegisterDeferred("File") { ViewImageTool() }
}
```

Belt-and-suspenders: also add a master check at the top of `ViewImageTool.execute()` (currently it only guards on `enableToolImageAutoload` at line 121). If the tool is somehow invoked when master is OFF — e.g. a hot-reload race in tests — `execute()` returns `ToolResult.Error("visual support disabled")` without touching the file. This mirrors how write-tools are filtered out in plan mode at registration time, with a body-level guard for defence in depth.

### Gate 4 — JCEF upload (`AttachmentUploadHandler.kt`)

**Already wired.** `AttachmentUploadHandler.validate()` at line 70 already returns `ValidationResult.Disabled` when `!settings.state.enableImageInput`, and the server responds with HTTP 200 + JSON `{error: "disabled"}` (per the doc comment at line 42 and the dispatch at line 179). The React client branches on the JSON `error` field, not HTTP status. **No code change needed in this gate** — the existing check picks up the expanded scope of `enableImageInput` for free when the default flips and the field is treated as the master.

### Gate 5 — Agent-loop tool-output autoload (`AgentLoop.kt`)

`AgentLoop.kt:1845` currently appends `toolImageRefs` **unconditionally** — neither the master flag nor the `enableToolImageAutoload` sub-flag is consulted. Both guards must be added from scratch:

```kotlin
val settings = com.workflow.orchestrator.core.settings.PluginSettings.getInstance(project).state
if (settings.enableImageInput && settings.enableToolImageAutoload) {
    val toolImageRefs = toolResult.imageRefs.map { ref -> ContentBlock.ImageRef(…) }
    // existing append into the core seam
}
```

When either flag is OFF, `toolImageRefs` is not built and `ImageRef` blocks are never appended.

### Gate 6 — Webview UI (`InputBar.tsx` + `AttachmentManager.ts`)

The bridge plumbing already exists: Kotlin pushes settings via `__applyImageSettings(json)` (received in `InputBar.tsx:731`), the payload's `enabled: boolean` field is honoured at line 739, and `AttachmentManager.ts:87` already rejects `attachFile()` when `!this.settings.enabled`. So the runtime mechanism is wired.

Three concrete changes needed in the webview:

1. `InputBar.tsx:37` — flip `IMAGE_DEFAULT_SETTINGS.enabled` from `true` to `false` to match the Kotlin default. Without this, JS starts `enabled=true` until the first `refreshImageSettings` round-trip — a small race window where the UI shows a working paperclip before being told otherwise.
2. `InputBar.tsx` — hide the paperclip / attach button when `!settings.enabled`.
3. `InputBar.tsx` lines 1108-1109 — `handleDrop` and `handleDragOver` currently fire unconditionally; guard them so `!settings.enabled` makes them no-ops (or detach the handlers conditionally).

The settings push propagates live, so the panel does not need to be reopened after a toggle change — `refreshImageSettings()` already covers this.

## Settings UI

File: `core/src/main/kotlin/com/workflow/orchestrator/core/settings/MultimodalSettingsConfigurable.kt`

The `enableImageInput` checkbox is promoted to a section header:

```
☐ Enable visual support (images, view_image tool, multimodal /stream)

    When disabled, the agent runs text-only:
      • view_image tool is removed from the LLM's tool list
      • image uploads in chat are blocked
      • images already in session history are stripped from requests

    Disable this if image handling is misbehaving.
```

Every sub-row (`enableToolImageAutoload`, both MIME whitelists, `imageMaxBytes`, `imagesPerTurnCap`) gets:

```kotlin
.enabledIf(masterCheckbox.selected)
```

Greyed-out rows retain their stored values; they reactivate when the master is re-checked.

## Testing strategy

Five unit tests, one per server-side gate. Manual smoke for gate 6.

| Test | File | Asserts |
|---|---|---|
| `BrainRouterRoutingDisabledTest` | `agent/src/test/kotlin/.../loop/` | Master OFF + image content → routes to `/messages` |
| `BrainRouterStreamSanitizeTest` | same | Master OFF + explicit `/stream` call → zero `StreamContentPart.Image` blocks in body |
| `ViewImageToolRegistrationTest` | `agent/src/test/kotlin/.../AgentServiceTest.kt` (or similar) | Registry contains `view_image` iff master ON |
| `AttachmentUploadHandlerDisabledTest` | `agent/src/test/kotlin/.../ui/` | POST to `/upload/*` returns 403 with body `visual support disabled` when OFF |
| `AgentLoopImageRefAutoloadTest` | `agent/src/test/kotlin/.../loop/` | Tool result that would normally append `ContentBlock.ImageRef` does not append when master OFF, regardless of `enableToolImageAutoload` |

Manual smoke (gate 6): flip toggle → reopen chat panel → confirm paperclip vanishes and drop zone is inert.

The history-strip path is intentionally not double-tested — it shares code with gate 2.

## Out of scope

- Fixing the underlying image-pipeline bugs (`project_image_attach_pipeline`).
- Live UI reactivity (panel must be reopened after toggle change).
- Migration of the field name `enableImageInput` to a friendlier identifier (would require XML migration; not worth the churn). The Kotlin field name stays; only the Settings UI label is rewritten to "Enable visual support".
- Plugin version bump is part of this release per the user's instruction (see Rollout).

## Rollout / rollback

- Ships on `fix/automation-handover-quality-tabs` alongside whatever else is on the branch.
- Plugin version: bump the patch segment of `pluginVersion` in `gradle.properties`. Tag + GitHub release with the ZIP from `build/distributions/` per the standard release process.
- Rollback path if visual support breaks again with master ON: `Settings → Tools → Workflow Orchestrator → Multimodal → uncheck "Enable visual support"`. No code rollback required.
