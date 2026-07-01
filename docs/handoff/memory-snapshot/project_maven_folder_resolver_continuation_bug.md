---
name: maven-folder-resolver-continuation-bug
description: refresh_external_project mode=generate_sources silently fails — invokeFolderResolver reflects on suspend fun without Continuation arg
metadata: 
  node_type: memory
  type: project
  originSessionId: ff6bd0e4-ef75-4344-ba3f-639fd86eab98
---

**BUG (2026-05-18, queued):** `RefreshExternalProjectAction.kt:369-380` (`invokeFolderResolver`) calls `MavenFolderResolver.resolveFoldersAndImport(List)` via plain `Method.invoke`, but that method is now a `suspend fun` in current JetBrains source — its JVM signature is `(List, Continuation): Object`. The reflection lookup throws `NoSuchMethodException`, the catch swallows it, a warning fires, `false` returns. The agent's `generate_sources` mode is silently broken (for both the all-projects and module-paths paths).

**Why:** Surfaced during 2026-05-18 audit of existing IDE-level Maven action implementation (saved to `docs/research/2026-05-18-maven-ide-actions-audit.md`). User opted to queue per `feedback_queue_bugs_during_implementation.md` — address after Maven goal executor lands and is verified, not inline.

**How to apply:** When the Maven goal executor work (spec `2026-05-18-maven-goal-executor-design.md` / plan `2026-05-18-maven-goal-executor.md`) is shipped and verified, return to this bug. Fix path: replace `invokeFolderResolver` with a call to `MavenAsyncFacade.invokeSuspendObj` (already exists at `MavenAsyncFacade.kt:255`) OR launch the resolver via a coroutine scope the way `UpdateFoldersAction.kt` in JetBrains source does. Related: see `project_tool_documentation_initiative.md` and `project_write_ops_ux_audit.md` for similar queued audits.
