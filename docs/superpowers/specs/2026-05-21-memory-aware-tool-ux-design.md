# Memory-aware tool UX — Design (2026-05-21)

**Branch:** `bugfix`
**Scope:** Make agent memory operations visible in the chat UI and reduce the LLM's two-step burden, without adding memory-specific tools to the registry.

## Problem

Today the agent uses generic `create_file` / `edit_file` / `read_file` to operate on `~/.workflow-orchestrator/{proj}/agent/memory/`. Two pain points result:

1. **UI is blind.** The chat renders every memory mutation as a plain "Editing file …memory/feedback_x.md" tool card. Users can't tell when the agent is curating its own memory vs. editing project source.
2. **LLM bears a two-step burden.** Saving a memory requires writing `<type>_<topic>.md` AND a second `edit_file` on `MEMORY.md` to append an index line. There is no plugin code that assists with the second step — `MemoryIndex.kt` only seeds the file on first session and truncates to 200 lines. Deletion has no tool at all today (only per-edit `revert_file`).

## Goals

1. Chat-UI tool cards for memory operations show a `MEMORY` badge and a verb-specific title (Creating / Reading / Updating / Deleting memory) with a lucide icon, replacing the generic file-edit chrome.
2. Plugin auto-maintains `MEMORY.md` on the create and delete edges of a memory file's lifecycle. Edits to existing memory files do **not** touch `MEMORY.md` so the user's hand-curated prefixes (`**SHIPPED:**`, `**⚠ HOOK:**`, dates, emojis) survive.
3. Add a generic `delete_file` tool so the LLM can retire stale memories without an `edit_file MEMORY.md` cleanup step.
4. Keep the tool surface flat — no `memory_create`, no `manage_memory`. Path detection layers semantic meaning onto the existing tools.

## Non-goals

- A dedicated memory tool family (`memory_create` / `manage_memory` / etc.). Considered and rejected in brainstorming — the 183→57 tool consolidation philosophy and the existing `PathValidator` memory-path special-case both point at "extend the existing surface" instead.
- Full reconciliation of `MEMORY.md` from frontmatter on every write. Rejected because the user's index has hand-curated prefixes the plugin can't derive.
- Garbage-collecting orphan memory files if the user deletes via Finder/Explorer (out-of-band, drift accepted).
- A janitor/cleanup pass for existing MEMORY.md drift.
- Editor-side affordances (gutter icons, Tools menu entries). Chat-UI only.

## Decisions (from brainstorm)

| Question | Choice | Rejected alternatives |
|---|---|---|
| Tool strategy | Keep file tools, add UI labels + auto-index | Dedicated `manage_memory` tool · Phase 1 UI now + tool later |
| Deletion path | Add a generic `delete_file` tool (Local-History-recoverable, NOT OS-trash) | LLM edits `MEMORY.md` only (file becomes tombstone) · Auto-detect empty edits as delete intent |
| Auto-sync scope | Append on create, remove on delete; never touch on edit | Full reconciliation · Create-only (LLM still cleans up on delete) |
| Icons | lucide-react inline + new `MEMORY` badge category | Emoji glyphs · `AllIcons` (not reachable from JCEF webview) |

## Architecture

```
┌──────────────────────────────────────────────────────────────────┐
│  AgentLoop                                                       │
│                                                                  │
│  LLM emits create_file / edit_file / delete_file / read_file     │
│  with path = ~/.workflow-orchestrator/{proj}/agent/memory/…      │
│                                                                  │
│         │                                                        │
│         ▼                                                        │
│  ┌──────────────────────────────────────────────┐                │
│  │ PathValidator.resolveAndValidateForWrite      │  ← unchanged  │
│  │ (memory dir already an allowed write root)    │                │
│  └──────────────────────────────────────────────┘                │
│         │                                                        │
│         ▼                                                        │
│  ┌──────────────────────────────────────────────┐                │
│  │ CreateFileTool / DeleteFileTool / EditFileTool│  ← only       │
│  │   1. perform I/O                               │    Create &  │
│  │   2. IF path under memoryDir AND not MEMORY.md│    Delete    │
│  │      → MemoryIndex.onMemoryFileCreated(path)  │    add the   │
│  │      OR MemoryIndex.onMemoryFileDeleted(path) │    hook       │
│  └──────────────────────────────────────────────┘                │
│         │                                                        │
│         ▼                                                        │
│  ┌──────────────────────────────────────────────┐                │
│  │ MemoryIndex (extended)                        │                │
│  │   • onMemoryFileCreated: parse frontmatter,   │                │
│  │     append "- [name](file.md) — desc" under   │                │
│  │     "## <Type>" section. Idempotent.          │                │
│  │   • onMemoryFileDeleted: remove matching line.│                │
│  │   • Atomic write (.tmp + ATOMIC_MOVE).        │                │
│  └──────────────────────────────────────────────┘                │
└──────────────────────────────────────────────────────────────────┘

┌──────────────────────────────────────────────────────────────────┐
│  Webview (React, JCEF)                                           │
│                                                                  │
│  ToolCallView receives { name, args }                            │
│         │                                                        │
│         ▼                                                        │
│  describeMemoryOp(name, args.path) → { icon, title } | null     │
│         │                                                        │
│  null   │ non-null                                               │
│   │     ▼                                                        │
│   │   ┌──────────────────────────────────────┐                   │
│   │   │ Render with category=MEMORY,         │                   │
│   │   │ title from describeMemoryOp,         │                   │
│   │   │ lucide icon inline.                  │                   │
│   │   └──────────────────────────────────────┘                   │
│   ▼                                                              │
│  existing CATEGORY_MAP path (READ/EDIT/WRITE/…)                  │
└──────────────────────────────────────────────────────────────────┘
```

## Implementation

### 1. `DeleteFileTool` (new)

**File:** `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/DeleteFileTool.kt`

**Schema:**

```kotlin
name = "delete_file"
parameters {
    string("path", required = true,
        description = "Absolute path or path relative to the project root.")
    string("description", required = true,
        description = "One-sentence reason this file is being deleted. Surfaced in the chat UI.")
}
```

**Behavior:**

- Resolve and validate via `PathValidator.resolveAndValidateForWrite(rawPath, project.basePath, memoryDir)` — same allowlist as create/edit.
- Refuse if path is a directory, refuse if file is missing (return `ToolResult.error` with the file path).
- Delete via VFS: `LocalFileSystem.getInstance().findFileByIoFile(file)?.delete(this)` inside a `WriteCommandAction` so the deletion is recorded in IntelliJ Local History — recoverable via `Edit → Local History → Show History` for ~5 days. The deletion is NOT moved to the OS trash. Fall back to `java.io.File.delete()` only if VFS lookup fails (no Local History entry in the fallback path).
- After successful delete, if the path is under `agentDir/memory/` and is NOT `MEMORY.md`, call `MemoryIndex.onMemoryFileDeleted(memoryDir, deletedFilename)`. Failures of the index update are logged and swallowed — the file delete itself succeeded.
- `ToolResult.summary` = `"Deleted <relative-path>"`.

**Registration:** Add to `ToolRegistry` alongside the other builtin file tools. Category in webview map → `WRITE` for non-memory paths (so the default badge says WRITE), `MEMORY` for memory paths (decided client-side by `describeMemoryOp`).

### 2. `MemoryIndex` extensions

**File:** `agent/src/main/kotlin/com/workflow/orchestrator/agent/memory/MemoryIndex.kt`

Two new functions:

```kotlin
fun onMemoryFileCreated(memoryDir: File, createdFile: File)
fun onMemoryFileDeleted(memoryDir: File, deletedFilename: String)
```

**`onMemoryFileCreated`:**

1. If `createdFile.name == "MEMORY.md"`, return (we never auto-edit the index because of an index write).
2. Read `createdFile`. Parse the YAML frontmatter block (between the leading `---` lines). Extract:
   - `name` — defaults to filename without `.md` if missing
   - `description` — defaults to empty string + WARN log if missing
   - `type` — one of `user`, `feedback`, `project`, `reference`; if missing or unrecognized, default to the bucket inferred from filename prefix (`feedback_*` → `feedback`, etc.); else `reference`.
3. Load `MEMORY.md` (create with seed header if missing — reuses `seedIfMissing`).
4. **Idempotency check:** if any existing line contains `](${createdFile.name})`, return without modifying.
5. Compute section heading: `"## ${type.replaceFirstChar { it.uppercase() }}"`.
6. Find the section heading line in `MEMORY.md`. If absent, append a blank line + the heading at end of file.
7. Append the entry under that section. Specifically, append after the last existing bullet beneath the section heading (or directly after the heading if no bullets yet). Entry format:
   ```
   - [<name>](<filename>) — <description>
   ```
8. Atomic write: write to `MEMORY.md.tmp` then `Files.move(ATOMIC_MOVE, REPLACE_EXISTING)` — same pattern used by session JSON persistence.

**`onMemoryFileDeleted`:**

1. Load `MEMORY.md`. If missing, return.
2. Filter out any line whose link target matches `(${deletedFilename})` — bullet lines only (must start with `- `). Preserve everything else (section headings, blank lines, comments).
3. If lines changed, atomic-write back. Else no-op.

**Concurrency:** Use a `Mutex` keyed by `memoryDir.absolutePath` so two simultaneous create_file calls on different memory files can't interleave their `MEMORY.md` edits. Reuses the pattern from `SessionStore`.

**Frontmatter parsing:** Inline, regex-based. Frontmatter is small (≤6 lines typically); no need to depend on a YAML library. Extract `^(name|description|type)\s*:\s*(.+)$` between the `---` delimiters.

### 3. Hook from existing tools

**Files:**
- `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/CreateFileTool.kt`
- `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/DeleteFileTool.kt` (new — see §1)

After a successful write/delete, both tools:

```kotlin
val memoryDirFile = project.basePath
    ?.let { File(ProjectIdentifier.agentDir(it), "memory") }
    ?: return successResult

if (memoryDirFile.exists() &&
    file.parentFile?.absolutePath == memoryDirFile.absolutePath &&
    file.name != "MEMORY.md") {
    try {
        MemoryIndex.onMemoryFileCreated(memoryDirFile, file)  // or Deleted
    } catch (t: Throwable) {
        LOG.warn("MemoryIndex auto-sync failed for ${file.name}", t)
    }
}
```

`EditFileTool` is deliberately not hooked. `read_file` does not need a hook.

### 4. Webview: `describeMemoryOp` + `MEMORY` category

**Files:**
- `agent/webview/src/components/agent/ToolCallView.tsx`
- `agent/webview/src/lib/describeMemoryOp.ts` (new helper for testability)

**Add to `CATEGORY_STYLES`:**

```tsx
MEMORY: {
  className: 'bg-[var(--badge-memory-bg,#2a1f3d)] text-[var(--badge-memory-fg,#b48fff)]',
  label: 'MEMORY',
}
```

CSS variables also added to the theme stylesheet alongside the existing `--badge-edit-bg`/`fg` pair so JetBrains light + dark themes can override.

**`describeMemoryOp(toolName, path): { icon: LucideIcon, title: string } | null`:**

```ts
const MEMORY_PATH_RE = /[\\/]\.workflow-orchestrator[\\/].+?[\\/]agent[\\/]memory[\\/](.+\.md)$/;

export function describeMemoryOp(toolName: string, path: string | undefined) {
  if (!path) return null;
  const match = path.match(MEMORY_PATH_RE);
  if (!match) return null;
  const filename = match[1];
  if (filename === 'MEMORY.md') {
    if (toolName === 'edit_file' || toolName === 'create_file')
      return { icon: ListTree, title: 'Curating memory index' };
    if (toolName === 'read_file')
      return { icon: ListTree, title: 'Reading memory index' };
    return null;
  }
  const slug = filename.replace(/\.md$/, '');
  const type = slug.includes('_') ? slug.split('_')[0] : null;

  switch (toolName) {
    case 'create_file':
      return { icon: BrainCircuit, title: `Creating memory · ${slug}${type ? ` (${type})` : ''}` };
    case 'read_file':
      return { icon: BookOpen, title: `Reading memory · ${slug}` };
    case 'edit_file':
      return { icon: Pencil, title: `Updating memory · ${slug}` };
    case 'delete_file':
      return { icon: Trash2, title: `Deleting memory · ${slug}` };
    default:
      return null;
  }
}
```

**`ToolCallView` integration:** before computing `category` from `CATEGORY_MAP`, call `describeMemoryOp(name, args?.path)`. If non-null, force `category = 'MEMORY'` and pass `{ icon, title }` into the header render, replacing the default `tool_name + extractTarget(...)` chrome. Output panel (diff / file contents) is unchanged.

**Function purity:** `describeMemoryOp` reads only `(toolName, path)`. It must NOT read `args.content`, response payloads, or status — so the header stays stable from `RUNNING` → `COMPLETED` and no chip flicker occurs mid-stream.

### 5. System prompt change

**File:** `agent/src/main/kotlin/com/workflow/orchestrator/agent/prompt/SystemPrompt.kt` (Section 10 — memory protocol)

Add after the existing "Saving a memory is a two-step process" paragraph:

> **The plugin auto-maintains `MEMORY.md` for you.** When you `create_file` a new `<type>_<topic>.md` under the memory directory, the plugin appends the corresponding index line under `## <Type>` automatically (read from your YAML frontmatter's `name`, `description`, `type`). When you `delete_file` a memory file, the matching index line is removed. Do not call `edit_file MEMORY.md` immediately after `create_file` to add the same entry — it will already be there.
>
> You may still `edit_file MEMORY.md` to curate entries (prefix with status like `**SHIPPED 2026-05-21:**`, reorder, add emojis, group). Those edits are preserved across future create/delete operations on other memory files.
>
> Use `delete_file` (not an edit-with-empty-content trick) to retire stale memories.

## Edge cases

| Case | Behavior |
|---|---|
| Frontmatter missing entirely | Append with `name = filename-without-ext`, empty description, type inferred from filename prefix or default `reference`. Log WARN. |
| Frontmatter has `name` but no `description` | Append with empty hook (`- [name](file.md) — `). Log INFO. |
| `## <Type>` section absent in `MEMORY.md` | Append blank line + `## <Type>` heading at end of file, then the entry. |
| `MEMORY.md` doesn't exist | `seedIfMissing` creates it; then continue. |
| `MEMORY.md` exists but has no frontmatter handling target | n/a — `MEMORY.md` itself has no frontmatter, only the per-topic files do. |
| Duplicate `create_file` on same memory file | Idempotent — the link-target check at step 4 prevents double append. |
| `delete_file` on non-existent file | Tool returns error before reaching the hook; `MEMORY.md` untouched. |
| `delete_file` on file with no matching index line | Index left as-is (no-op). |
| Memory file deleted via Finder / Explorer | Plugin not notified; index drifts. Acceptable. Out of scope. |
| `revert_file` on a memory file | Does not touch `MEMORY.md` (it's an edit-undo, not a delete). |
| User-curated prefixes on existing entries | Never touched on subsequent writes/deletes of OTHER memory files. Curation contract preserved. |
| Two concurrent `create_file` calls on different memory files | Serialized by `Mutex` keyed on `memoryDir`. No interleaving. |
| Frontmatter `type` field unrecognized (e.g., `type: foobar`) | Falls back to filename-prefix-derived bucket; if also unrecognized, defaults to `reference`. |
| Atomic-write fails mid-flight | `.tmp` left behind on disk; original `MEMORY.md` untouched. Logged. Tool itself still reports the file-create success. |

## Testing

**New / extended tests:**

- `MemoryIndexAutoSyncTest`
  - `appendsUnderCorrectSection` — `feedback_x.md` with `type: feedback` lands under `## Feedback`.
  - `createsMissingSection` — memory directory has no `## Project` header; auto-sync adds the heading.
  - `idempotentAppend` — calling `onMemoryFileCreated` twice on the same file appends once.
  - `deleteRemovesMatchingLine` — deletes only the line targeting that filename, leaves other entries intact.
  - `deletePreservesSectionHeadings` — empty sections remain after their last entry is removed.
  - `frontmatterMissingDefaults` — file with no frontmatter still produces an index line using filename slug.
  - `concurrentCreatesSerialized` — two coroutines append concurrently; both lines present, no corruption.
  - `atomicWriteRollback` — simulate I/O failure mid-write; `MEMORY.md` unchanged.

- `DeleteFileToolTest`
  - `pathValidatorRejectsOutsideAllowlist` — path outside project root + memory dir returns error.
  - `missingFileReturnsError`.
  - `memoryPathTriggersIndexRemoval` — verify `MemoryIndex.onMemoryFileDeleted` was called.
  - `nonMemoryPathDoesNotCallIndex`.
  - `directoryRejected`.

- `CreateFileToolTest` (extension to existing file)
  - `memoryPathTriggersIndexAppend`.
  - `nonMemoryPathDoesNotCallIndex`.
  - `creatingMemoryMdItselfDoesNotRecurse`.

- `describeMemoryOp.test.ts` (new, alongside `agent/webview/src/lib/__tests__/`)
  - All four verbs on a memory path produce the expected `{ icon, title }`.
  - Non-memory paths return `null`.
  - `MEMORY.md` paths produce the index-curation variant.
  - Windows-style paths (`\` separators) detected.

- Visual smoke: render `ToolCallView` with each variant in `playwright.html` harness; confirm badge + icon + title appear correctly in light and dark themes.

## Land order

| # | Item | Files touched | Risk |
|---|------|---------------|------|
| 1 | `describeMemoryOp` + MEMORY category (webview only) | 2 webview files + 1 test | Low — no agent runtime change |
| 2 | `MemoryIndex` extensions (no callers yet) | 1 Kotlin file + 1 test | Low — pure additive |
| 3 | `DeleteFileTool` (registers, validates, deletes, calls MemoryIndex.onDeleted) | 1 Kotlin file + 1 test + ToolRegistry | Medium — new write-side tool |
| 4 | `CreateFileTool` hook into MemoryIndex.onCreated | 1 Kotlin file edit + test extension | Low |
| 5 | System prompt §10 update | 1 Kotlin file | Trivial |

Each item lands as its own commit. The webview change is shippable independently — if the Kotlin side were never to land, users would still see correctly labeled memory operations (just without auto-index help).

## Rollout / kill-switch

- Add a hidden `PluginSettings.memoryAutoIndexEnabled` flag (default `true`). `CreateFileTool` and `DeleteFileTool` skip the `MemoryIndex` hook when false. Provides a fast rollback if auto-sync produces unwanted MEMORY.md edits in the wild.
- The UI relabel has no kill switch — it's purely cosmetic and safe to ship without one.

## Open questions

- Should `delete_file` cap at memory-dir + project-root, or also allow `tmp` paths the agent occasionally needs to clean? **Decision:** match `PathValidator.resolveAndValidateForWrite`'s existing allowlist exactly. No new write roots. Re-evaluate only if the agent starts asking for `/tmp` cleanup.
- Should `read_file` on `MEMORY.md` itself be relabeled, since the LLM does this on every system-prompt rebuild via the injected index (not via the tool)? **Decision:** yes — if a tool-call comes in, it means the LLM explicitly asked, which is rare and worth surfacing as "Reading memory index". The auto-injection path doesn't surface as a tool call so this doesn't add chrome to every turn.
