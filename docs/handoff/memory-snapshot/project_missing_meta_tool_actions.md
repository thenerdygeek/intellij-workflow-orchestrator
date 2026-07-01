---
name: Missing meta-tool actions to expose
description: Three service methods need meta-tool actions: JiraTool download_attachment + search_tickets (raw JQL), BambooBuildsTool download_artifact
type: project
---

Three core service methods need to be exposed as meta-tool actions (user requested 2026-04-02):

1. **JiraTool `download_attachment`** — `JiraService.downloadAttachment(issueKey, attachmentId)` → `ToolResult<AttachmentContentData>`. Returns filename, mimeType, content (text), filePath. Useful for reading specs/logs/configs attached to tickets.

2. **JiraTool `search_tickets`** (raw JQL) — `JiraService.searchTickets(jql, maxResults)`. Different from existing `search_issues` (which auto-builds JQL from text scoped to current user). This gives full JQL power for complex queries.

3. **BambooBuildsTool `download_artifact`** — `BambooService.downloadArtifact(artifactUrl, targetFile)` → `ToolResult<Boolean>`. Downloads build artifact to local file. Workflow: `get_artifacts` → `download_artifact` → `read_file`.

**Why:** These gaps were found during the dead code cleanup comparing service interface method counts vs meta-tool action counts.

Also noted: `:automation` module violates dependency rule by importing directly from `:bamboo` (BambooApiClient, DTOs, BambooServiceImpl). Should be refactored to go through `:core` service interfaces only.

**How to apply:** When implementing, add to existing meta-tool `when(action)` blocks. Detailed notes in `docs/TODO-expose-missing-meta-tool-actions.md`.
