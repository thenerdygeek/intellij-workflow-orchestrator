# `edit_file` — extended notes

## Why this exists

`edit_file` is the agent's primary code-modification primitive. Almost every "fix this
bug", "rename this method", "add this null-check" task ends with one or more
`edit_file` calls. Without it, the agent would have to overwrite whole files via
`create_file` (catastrophic loss of precision and approval-gate granularity) or
shell out to `sed`/`awk` (which bypasses VFS, undo, and the IDE's diagnostics view).

## The contract: exact-text, single match

This is a **Claude Code-style** `Edit` tool, not a Cline SEARCH/REPLACE block tool.
The matcher is `String.indexOf` / `String.replaceFirst` — it compares
byte-for-byte. Whitespace, indentation, and line endings must match the file on disk
character-for-character. There is no fuzzy match, no whitespace-tolerant fallback,
no re-indentation pass.

Two consequences flow from this:

1. **You must read the file first.** `read_file` returns the actual bytes — including
   tabs vs spaces, CRLF vs LF, trailing whitespace. Eyeballing the file or guessing
   the indentation produces an `old_string not found` error. The
   `read_file` → `edit_file` pairing is so common that the system prompt explicitly
   says "always read before editing".
2. **Line-number prefixes are not part of the file.** `read_file` returns lines as
   `42\tconst x = 1` (numbered for the LLM's later reference). Those `N\t` prefixes
   are metadata. Including them in `old_string` makes the match fail — there is no
   `42\t` in the actual file content. This is the single most common failure pattern
   we see, hardened only by the fact that the error message says "Verify the exact
   text including whitespace."

## The unique-match rule

If `old_string` matches more than once and `replace_all=false` (the default),
`edit_file` returns an error rather than guess which one to replace. The fix
is one of:

- Add 3-5 lines of surrounding context to the `old_string` to make it unique
  (the recommended approach — keeps the edit local and reviewable).
- Set `replace_all=true` if the goal is genuinely "rename every X to Y" — but be
  aware this is brittle: a typo'd identifier still matches every occurrence.

For symbol renames across many files, `refactor_rename` is almost always better —
it understands scope, references, and language semantics. `edit_file` with
`replace_all=true` is a string-level tool.

## Three-tier write strategy

Writes go through a fallback chain:

1. **Document API + WriteCommandAction** — the preferred path. Provides full undo
   support (Ctrl-Z works in the editor), immediate editor sync (the user sees the
   change in the open tab), and proper IntelliJ refactoring/diagnostics integration.
   This is the path used when the file is open in an editor.
2. **VFS `setBinaryContent` + WriteCommandAction** — used when the Document is null
   (file not currently loaded). Still inside a write action, so VFS listeners fire
   and indexing kicks off. Followed by an explicit `vFile.refresh(false, false)`
   to flush diagnostics within ~16ms instead of waiting on the VFS watcher.
3. **Direct `java.io.File.writeText`** — final fallback for unit-test environments
   without a running IntelliJ Application. No undo, no editor sync.

The agent doesn't pick — it tries (1), then (2), then (3), and reports success on
the first one that worked. Most production calls land in tier (1) or (2).

## Syntax validation: warn, don't block

For `.kt` and `.java` files, the new content is run through `SyntaxValidator`
after the edit is applied. If errors are detected, they are returned alongside
the success result as a `WARNING:` block — the edit is *not* rolled back.

This is intentional. Blocking on syntax errors creates a chicken-and-egg problem
when the edit is part of a multi-step refactor (e.g., delete an import in one
edit, then update the consumer in the next — the file is briefly broken in
between). Warning lets the LLM see the error and decide whether to immediately
follow up with another `edit_file` or call `diagnostics` for a richer report.

For other extensions, no validation is performed. Python typos, broken JSON,
malformed YAML — `edit_file` will happily write them. This is a real downside;
the LLM has to call `diagnostics` afterwards to catch language-server-detectable
errors.

## Where the file is allowed to live

`PathValidator.resolveAndValidateForWrite` accepts paths inside:

- The project root.
- `{agentDir}/memory/` — `~/.workflow-orchestrator/{slug}-{sha6}/agent/memory/`,
  the agent's persistent memory directory.

Any other path — `/etc/passwd`, `~/.ssh/`, `~/secrets/`, even
`{agentDir}/sessions/...` — is rejected before any I/O happens. The check is
canonical-path-prefix based, so `../../etc/passwd` traversal is caught.

## Approval and plan-mode behaviour

`edit_file` is in two enforcement sets:

- **`AgentLoop.WRITE_TOOLS`** — runs sequentially (never in parallel with other
  writes). Blocked outright when plan mode is active, both at the schema-filtering
  layer (LLM doesn't see the tool) and at the execution-guard layer (defence in
  depth against cached tool calls from before mode switch).
- **`ApprovalPolicy.SESSION_APPROVABLE`** — requires user approval the first time;
  the user can choose "approve for session" to skip the dialog on subsequent
  edits. This is the same gate as `create_file` and `revert_file`.

The approval dialog shows the `description` parameter, which is why it's required
even though the tool itself doesn't read it. A vague description ("edit file")
makes the approval dialog useless; a good one ("inline `getFoo()` into `bar`
since it's only called once") gives the user something actionable.

## What the result looks like

A successful edit returns:

- A 1-line summary: `Replaced 47 chars with 52 chars in src/Foo.kt`.
- A "Context after edit" block with 3 lines before and after the edited region,
  line-numbered so the LLM can verify the change landed where it expected.
- Optionally a `WARNING:` block if syntax validation found issues.
- A unified diff (in `ToolResult.diff`) for the UI to render side-by-side.

The unified diff and the artifacts list (`ToolResult.artifacts`) are how the
chat UI shows the edit as a clickable, expandable diff bubble — the LLM doesn't
see those, but the user does.

## Known failure modes

- **`old_string` not found** — almost always because the LLM included line-number
  prefixes from `read_file` output, or guessed the whitespace, or is matching
  against a stale read (the file was changed by another tool/user since). Recovery:
  re-read the file, paste raw content into `old_string`.
- **`old_string` matches multiple times** — recovery: add more context lines, or
  `replace_all=true` if intentional.
- **Path outside project** — recovery: check the path is relative to the project
  root, or absolute and inside it. Memory writes need to go to
  `{agentDir}/memory/<file>.md`.
- **VFS unavailable in unit tests** — falls through to `java.io.File`, which works
  but loses undo support. Tests that need undo must set up a real `IdeaTestFixture`.
