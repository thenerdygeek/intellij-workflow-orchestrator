# Core Tool Implementations Across Agentic AI Coding Tools

**Date:** 2026-03-21
**Purpose:** Research how enterprise-grade agentic AI tools implement search, read, edit, and run command tools
**Status:** Complete

## Table of Contents

1. [Tool Comparison Matrix](#tool-comparison-matrix)
2. [Code Search Tools](#1-code-search-tools)
3. [Read File Tools](#2-read-file-tools)
4. [Edit File Tools](#3-edit-file-tools)
5. [Run Command Tools](#4-run-command-tools)
6. [Cross-Cutting Patterns](#5-cross-cutting-patterns)
7. [Design Insights & Recommendations](#6-design-insights--recommendations)

---

## Tool Comparison Matrix

| Tool | Search | Read | Edit | Run Command | Architecture |
|------|--------|------|------|-------------|--------------|
| **Claude Code** | Grep (ripgrep) + Glob | Read (offset/limit) | Edit (string replace) + Write | Bash (persistent) | Purpose-built tools |
| **Codex CLI** | Shell `grep`/`find` | Shell `cat` | apply_patch (unified diff) | Shell (single tool) | Shell-centric unified |
| **Cursor** | Semantic (embeddings) + grep | IDE-integrated | Apply model (full rewrite) | Terminal | Two-stage AI apply |
| **Cline** | search_files (regex) | read_file | replace_in_file (SEARCH/REPLACE) | execute_command | XML-structured tools |
| **OpenHands** | Bash `grep`/`find` | str_replace_editor view | str_replace_editor str_replace | execute_bash | Action-Observation pattern |
| **Gemini Code Assist** | grep + find_files + resolve_symbol | read_file | write_file | git CLI | IDE-native (PSI-aware) |
| **oh-my-pi** | GrepTool + FindTool | ReadTool (hash-anchored) | EditTool (hash-anchored) | BashTool | Hash-anchored lines |

---

## 1. Code Search Tools

### Claude Code: Grep + Glob (Separate Tools)

**Design decision:** Two distinct tools — Glob for file discovery by name, Grep for content search.

#### Grep Tool
- **Backend:** Built on ripgrep (rg)
- **Parameters:**
  - `pattern` (string, required) — regex pattern
  - `path` (string, optional) — file or directory to search, defaults to cwd
  - `output_mode` (enum, optional) — `"files_with_matches"` (default) | `"content"` | `"count"`
  - `glob` (string, optional) — file filter pattern (e.g., `"*.js"`, `"*.{ts,tsx}"`)
  - `type` (string, optional) — file type shorthand (js, py, rust, go, java)
  - `-i` (boolean, optional) — case insensitive
  - `-n` (boolean, optional) — show line numbers (requires content mode, default true)
  - `-A` (number, optional) — lines after match
  - `-B` (number, optional) — lines before match
  - `-C` (number, optional) — context lines (before + after)
  - `multiline` (boolean, optional) — enable multiline mode (default false)
  - `head_limit` (number, optional) — limit output to first N entries
  - `offset` (number, optional) — skip first N entries before applying head_limit
- **Response:** Depends on output_mode:
  - `files_with_matches` — sorted file paths
  - `content` — matching lines with line numbers and optional context
  - `count` — match counts per file
- **Key design:** Three output modes let the LLM choose appropriate detail level. Pagination via head_limit + offset.

#### Glob Tool
- **Parameters:**
  - `pattern` (string, required) — glob pattern (e.g., `"**/*.kt"`, `"src/**/*.ts"`)
  - `path` (string, optional) — directory to search, defaults to cwd
- **Response:** File paths sorted by modification time (newest first)
- **Pattern support:** `*` (single level), `**` (recursive), `?` (single char), `{a,b}` (alternation), `[abc]` (character class)
- **Key design:** Modification-time sorting means recently changed files appear first — very useful for the LLM to find relevant code.

### Codex CLI: Shell-Based Search

**Design decision:** No dedicated search tools. Model uses shell commands (`grep`, `find`, `ls`, `cat`) through the single `shell` tool.

- No structured parameters — the model writes raw shell commands
- Results are raw terminal output (stdout/stderr)
- Relies on LLM's training-data knowledge of grep/find flags
- Advantage: Zero tool overhead, infinite flexibility
- Disadvantage: No structured output, no pagination, can generate huge outputs

### Cursor: Semantic Search + Grep Hybrid

**Design decision:** Dual-mode search combining semantic embeddings and regex.

- **Indexing:** Files are chunked via AST (tree-sitter) into semantic units (functions, classes, blocks)
- **Embeddings:** Custom-trained embedding model (improved 12.5% over baseline)
- **Storage:** Vector embeddings stored in Turbopuffer (remote vector DB); source code stays local
- **Search flow:**
  1. Query converted to vector embedding
  2. Nearest-neighbor search in vector DB
  3. Results decrypted client-side
- **Hybrid:** Agent automatically selects grep for symbols/identifiers, semantic for concepts/behaviors
- **Privacy:** Only embeddings stored remotely, never source code
- **Reindexing:** Auto-sync every 5 minutes, processes only changed files

### Cline: search_files + list_files + list_code_definition_names

**Design decision:** Three separate XML-structured tools for different search types.

#### search_files
```xml
<search_files>
  <path>directory/to/search</path>
  <regex>pattern to match</regex>
  <file_pattern>*.ts</file_pattern>
</search_files>
```
- `path` (required) — directory to search
- `regex` (required) — regex pattern for content matching
- `file_pattern` (optional) — glob filter for file types
- Returns: matching lines with file paths and line numbers

#### list_files
```xml
<list_files>
  <path>directory/to/list</path>
  <recursive>true</recursive>
</list_files>
```
- `path` (required) — directory path
- `recursive` (optional) — whether to list recursively
- Returns: file and directory listing

#### list_code_definition_names
```xml
<list_code_definition_names>
  <path>src/</path>
</list_code_definition_names>
```
- `path` (required) — file or directory
- Returns: code definitions (functions, classes, methods) using tree-sitter AST parsing
- **Key design:** AST-based code intelligence without full file reads

### OpenHands: Shell-Based + Optional Grep Tool

- Primary search via `execute_bash` with grep/find commands
- Optional `grep` and `glob` tools available in SDK V1
- `str_replace_editor` with `view` command for reading specific ranges

### Gemini Code Assist (IntelliJ): IDE-Native Search

**Design decision:** Leverages IntelliJ PSI (Program Structure Interface) for code intelligence.

- `grep` — text/regex pattern search across project files
- `find_files` — find files by name or partial path
- `resolve_symbol` — resolve a symbol reference to its declaration (PSI-based)
- `find_usages` — find all references to a symbol (PSI-based)
- **Key design:** `resolve_symbol` and `find_usages` are unique — they use IDE code intelligence rather than text search, providing semantic accuracy no regex tool can match.

---

## 2. Read File Tools

### Claude Code: Read

- **Parameters:**
  - `file_path` (string, required) — absolute path only
  - `offset` (number, optional) — starting line number (1-indexed)
  - `limit` (number, optional) — number of lines to read
- **Defaults:** First 2000 lines if no offset/limit specified
- **Line truncation:** 2000 characters per line
- **Output format:** `cat -n` style with line numbers (1-indexed)
- **Multimodal:** Images (PNG, JPG) rendered visually, PDFs with page selection (`pages` parameter), Jupyter notebooks with all cells + outputs
- **Binary detection:** Binary files detected and handled appropriately (images rendered, others rejected)
- **Validation:** Cannot read directories (must use Bash `ls`). Relative paths rejected.
- **Key design:** Read-before-edit enforcement — the system tracks which files have been read and rejects edits to unread files. This prevents blind edits.

### Codex CLI: Shell cat

- No dedicated read tool — uses `cat`, `head`, `tail` via shell
- No built-in line numbering or pagination
- No multimodal support
- No read-before-edit enforcement

### Cline: read_file

```xml
<read_file>
  <path>src/main.ts</path>
</read_file>
```
- `path` (required) — file path (relative to workspace)
- Returns: complete file contents with line numbers
- No offset/limit parameters — always reads the full file
- **Key limitation:** No pagination for large files

### OpenHands: str_replace_editor view

```
command: view
path: /workspace/src/main.py
view_range: [1, 50]
```
- `command` = `"view"` (required)
- `path` (string, required) — absolute path
- `view_range` (array, optional) — `[start_line, end_line]` for partial reading
- Returns: file contents with line numbers
- **Key design:** Same tool handles both reading and editing — `str_replace_editor` with different `command` values

### Gemini Code Assist: read_file

- `read_file` — retrieves text content using absolute path
- IDE-integrated — leverages IntelliJ's VFS (Virtual File System)
- `analyze_current_file` — separate tool for getting errors/warnings from the open file

### oh-my-pi: ReadTool (Hash-Anchored)

- Returns file contents where **every line has a short content-hash anchor**
- Hash format: short hash prefix on each line
- **Key innovation:** When editing, the model references hash anchors instead of reproducing text verbatim. If the file changed since last read, hashes won't match and the edit is rejected — preventing stale-edit corruption.

---

## 3. Edit File Tools

### Claude Code: Edit (String Replace) + Write (Full Replace)

#### Edit Tool (Preferred for Existing Files)
- **Parameters:**
  - `file_path` (string, required) — absolute path
  - `old_string` (string, required) — exact text to find
  - `new_string` (string, required) — replacement text (must differ from old_string)
  - `replace_all` (boolean, optional, default false) — replace all occurrences
- **Matching:** Exact string matching, no regex. Whitespace-sensitive (indentation must match exactly).
- **Uniqueness requirement:** `old_string` must appear exactly once in file, OR `replace_all=true`. Fails on zero or multiple matches.
- **Validation:** Read-before-edit enforcement — file must have been read first. System tracks read files per session.
- **Response:** Success/failure indication. Implicit diff via old_string/new_string.
- **Key design:** "Edit is the most important tool for code quality" — precision (exact match prevents wrong-location edits), reviewability (clear diff), safety (fails on ambiguity), efficiency (transmits only the change, not the whole file).

#### Write Tool (For New Files or Full Rewrites)
- **Parameters:**
  - `file_path` (string, required) — absolute path
  - `content` (string, required) — complete file content
- **Validation:** Must read existing file first (enforced by system). Relative paths rejected.
- **Use case:** New file creation, or when >50% of content changes.

#### MultiEdit Tool
- Applies multiple Edit operations to the same file in a single call
- Avoids intermediate file states between related changes

### Codex CLI: apply_patch (Unified Diff)

- **Format:** Model emits a structured patch inside a shell command:
  ```
  {"cmd": ["apply_patch", "*** Begin Patch\n*** Update File: path/to/file\n@@...@@\n-old line\n+new line\n*** End Patch"]}
  ```
- **Operations:** `Add File`, `Update File`, `Delete File`
- **Context anchoring:** Uses `@@` markers with surrounding code context — **avoids line numbers** intentionally
- **Line prefixes:** `-` for deletion, `+` for addition (standard unified diff)
- **Matching:** Progressive fuzzy matching: exact match → whitespace-tolerant → fully trimmed
- **Error feedback:** JSON error response detailing specific mismatches
- **Implementation:** CLI intercepts the command, parses the patch, applies via `fs.writeFileSync` (not actual shell execution)
- **Key design:** Single tool for all file mutations. Heavy model training on this specific format (GPT-4.1 trained extensively on apply_patch). Avoids line numbers because they're fragile.

### Cursor: Two-Stage AI Apply

- **Stage 1 (Sketching):** Primary LLM generates intended changes in rough format
- **Stage 2 (Applying):** Custom-trained "Apply" model integrates changes into the actual file
- **Full-file rewrite:** The apply model generates the complete rewritten file (not a diff)
- **Speculative edits:** For speed, uses speculative decoding — since most output matches existing code, the system predicts large unchanged chunks. Achieves ~1000 tokens/sec (~13x speedup over vanilla inference).
- **Threshold:** Full-file rewrite outperforms diff-based edits for files under ~400 lines
- **Key design:** Separates "what to change" from "how to integrate it reliably." The apply model is specifically trained for merge reliability.

### Cline: replace_in_file (SEARCH/REPLACE Blocks)

```xml
<replace_in_file>
  <path>src/main.ts</path>
  <diff>
<<<<<<< SEARCH
function oldCode() {
  return "old";
}
=======
function newCode() {
  return "new";
}
>>>>>>> REPLACE
  </diff>
</replace_in_file>
```
- `path` (required) — file path
- `diff` (required) — one or more SEARCH/REPLACE blocks
- **Matching:** Exact character-by-character match required for SEARCH block
- **Ordering:** Multiple SEARCH/REPLACE blocks must appear in file order (top to bottom)
- **Known issues:** Strict matching causes frequent failures. Whitespace/line-ending mismatches break edits. Model sometimes includes the `>>>>>>> REPLACE` delimiter in output.
- **Fallback:** When replace_in_file fails, falls back to write_to_file (full rewrite), which can corrupt content.

### OpenHands: str_replace_editor (Multi-Command)

Single tool with multiple commands:
- `str_replace` — find and replace text
  - `path` (required) — absolute path
  - `old_str` (required) — text to find
  - `new_str` (optional) — replacement (empty = delete)
- `create` — create new file
  - `path` (required)
  - `file_text` (required) — complete content
- `insert` — insert text at a line
  - `path` (required)
  - `insert_line` (required) — line number after which to insert
  - `new_str` (required) — text to insert
- `undo_edit` — revert last edit
  - `path` (required)
- **Key design:** Unified tool for all file operations (read + edit + create + undo). The `undo_edit` command is unique — provides built-in rollback.

### oh-my-pi: Hash-Anchored EditTool

- Model references content-hash anchors instead of reproducing exact text
- Edit operations use `op: "replaceText"` with hash references
- **Key innovation:** No "string not found" errors, no whitespace reproduction issues, no ambiguous matches. If the file changed since last read, the hash mismatch causes the edit to be rejected before corruption occurs.
- **Tradeoff:** Requires custom output format — standard LLMs need adaptation/fine-tuning

### Aider: Multi-Format Architecture (Reference)

Supports pluggable edit formats:
- **EditBlock Format:** `<<<<<<<`/`>>>>>>>` delimiters (similar to Cline)
- **Unified Diff Format:** Standard `diff -U0` style
- **OpenAI Patch Format:** Codex-compatible apply_patch
- **Whole file mode:** Complete file replacement
- **Key innovation:** Layered matching: exact → whitespace-insensitive → indentation-preserving → fuzzy. Error messages suggest actual file content to help the model self-correct.

---

## 4. Run Command Tools

### Claude Code: Bash

- **Parameters:**
  - `command` (string, required) — shell command to execute
  - `description` (string, optional) — 5-10 word summary for UI/logging
  - `timeout` (number, optional) — milliseconds (default 120,000 = 2min, max 600,000 = 10min)
  - `run_in_background` (boolean, optional) — execute asynchronously
- **Output:** Captures both stdout and stderr
- **Truncation:** Output truncated at 30,000 characters
- **State:** Working directory persists between calls, but shell state does not (no persistent env vars)
- **Shell:** Initialized from user profile (bash or zsh)
- **Background execution:** `run_in_background=true` returns immediately; results retrieved via `BashOutput` tool
- **Restrictions:** System prompt instructs to avoid `find`, `grep`, `cat`, `head`, `tail`, `sed`, `awk`, `echo` in favor of dedicated tools
- **Permission model:** Commands require approval unless auto-approved by user configuration
- **Additional tools:**
  - `BashOutput` — retrieve incremental output from background shells (with optional regex filter)
  - `KillShell` — terminate background shells

### Codex CLI: Shell (Single Tool)

- **Tool name:** `shell` (or `container.exec` on Linux)
- **Parameters:** Single command string
- **Sandbox:**
  - macOS: Apple Seatbelt profile — read-only filesystem except project dir, blocked network (except OpenAI API)
  - Linux: Docker container with iptables firewall
- **Approval modes:** `suggest` (always ask), `auto-edit` (auto-approve safe commands), `full-auto` (approve all)
- **Auto-approval:** `canAutoApprove` function classifies commands as safe/unsafe
- **Key design:** Everything goes through shell — reading, searching, testing, building. The shell IS the tool. apply_patch is intercepted before reaching the actual shell.

### Cline: execute_command

```xml
<execute_command>
  <command>npm test</command>
  <requires_approval>true</requires_approval>
</execute_command>
```
- `command` (required) — CLI command to run
- `requires_approval` (optional) — whether to prompt user
- Returns: stdout + stderr
- Runs in VS Code's integrated terminal
- Human-in-the-loop: every command requires explicit user approval

### OpenHands: execute_bash

- Executes commands in sandboxed Docker container
- Returns stdout + stderr as observation
- Persistent shell state within session
- Also has `IPythonTool` for Python execution in Jupyter kernel

### Gemini Code Assist: git Tool

- `git` — runs Git CLI commands specifically
- No general-purpose shell execution in IntelliJ agent mode
- Relies on IDE actions for non-git operations

---

## 5. Cross-Cutting Patterns

### Result Truncation

| Tool | Strategy | Limit |
|------|----------|-------|
| Claude Code Read | Line limit | 2000 lines default, 2000 chars/line |
| Claude Code Bash | Character limit | 30,000 characters |
| Claude Code Grep | head_limit + offset | Configurable pagination |
| Codex CLI | Raw output | No documented limit (sandbox restricts) |
| Cline | Full output | No documented limit |
| OpenHands | Observation wrapper | Handled by conversation memory |

### Error Handling

| Tool | Error Response |
|------|---------------|
| Claude Code Edit | Fails with message if old_string not found or not unique |
| Codex apply_patch | JSON error with specific mismatch details |
| Cline replace_in_file | Diff edit failed error; falls back to write_to_file |
| OpenHands str_replace | Error message if old_str not found |
| Aider | Suggests actual file content in error message |

### Path Handling

| Tool | Path Type | Validation |
|------|-----------|------------|
| Claude Code | Absolute only | Rejects relative paths |
| Codex CLI | Relative to project | Sandbox restricts to project dir |
| Cline | Relative to workspace | Resolved by VS Code |
| OpenHands | Absolute only | Must start with / |
| Gemini Code Assist | Absolute only | IDE-resolved |

### Exclusion Patterns

- **Claude Code:** Grep uses ripgrep defaults (respects .gitignore, skips binary, skips hidden dirs)
- **Codex CLI:** Sandbox restricts to project directory only
- **Cursor:** Indexing respects .gitignore; manual exclude patterns in settings
- **Cline:** Follows .gitignore; manual exclusion via file_pattern parameter

### Read-Before-Edit Enforcement

| Tool | Enforced? | Mechanism |
|------|-----------|-----------|
| Claude Code | Yes | System tracks read files per session; Edit/Write fail if file not read |
| Codex CLI | No | "Don't re-read files after patching" — opposite philosophy |
| Cline | No | No enforcement; model can write_to_file without reading |
| OpenHands | No | Same tool does read (view) and edit (str_replace) |

---

## 6. Design Insights & Recommendations

### Insight 1: Minimal Tool Sets Win

> "Agents work best when they have a short-but-sweet set of tools; too many tools can use too much of the context window and end up confusing the model." — Sean Goedecke

Two philosophies:
- **Minimal (Codex):** Shell + apply_patch. Two tools. Maximum flexibility, zero overhead.
- **Structured (Claude Code):** 6+ purpose-built tools. Type-safe, paginated, permission-aware.

**Recommendation for IntelliJ plugin:** Start with structured tools (we have IDE APIs), but keep the count low. 5-7 tools is the sweet spot.

### Insight 2: String Replace > Diff > Full Rewrite (for Reliability)

Edit approach reliability ranking (based on real-world failure rates):
1. **String replace** (Claude Code, OpenHands) — most reliable, fails explicitly on ambiguity
2. **Hash-anchored** (oh-my-pi) — most innovative, eliminates stale-edit corruption
3. **Unified diff/patch** (Codex) — good with model training, fragile without
4. **SEARCH/REPLACE blocks** (Cline, Aider) — frequently fails due to strict matching
5. **Full file rewrite** (Cursor Apply) — reliable but expensive, works best under 400 lines

**Recommendation:** Use string-replace as primary edit mechanism. Simple, reliable, LLMs handle it well.

### Insight 3: Avoid Line Numbers in Edit Anchoring

Every successful tool avoids or minimizes dependence on line numbers:
- Claude Code uses exact string matching (no line numbers)
- Codex uses surrounding code context in @@ markers
- oh-my-pi uses content hashes
- Cline uses verbatim SEARCH blocks

Line numbers are fragile — they change with every edit. Context-based anchoring is universally preferred.

### Insight 4: IDE-Native Tools Are a Competitive Advantage

Gemini Code Assist's `resolve_symbol` and `find_usages` are uniquely powerful because they use IntelliJ's PSI. No regex-based search can match the accuracy of:
- Resolving a method call to its declaration across modules
- Finding all usages including renamed imports
- Understanding type hierarchies

**Recommendation:** Our IntelliJ plugin should expose PSI-based tools. This is a differentiator no CLI tool can offer.

### Insight 5: Search Should Have Multiple Output Modes

Claude Code's Grep with three output modes (files_with_matches, content, count) is a strong pattern:
- **files_with_matches** — for broad discovery (which files contain X?)
- **content** — for targeted inspection (what does the code look like?)
- **count** — for estimation (how widespread is this pattern?)

**Recommendation:** Implement all three modes in our search tool.

### Insight 6: Pagination Is Essential for Large Outputs

Claude Code implements pagination at multiple levels:
- Read: offset + limit (line-based)
- Grep: head_limit + offset (entry-based)
- Bash: 30K character truncation

Without pagination, large outputs consume context window and degrade LLM performance.

**Recommendation:** Every tool that can produce unbounded output needs truncation + pagination.

### Insight 7: Read-Before-Edit Is a Safety Feature Worth Having

Claude Code's read-before-edit enforcement prevents blind edits. Codex explicitly skips this ("don't re-read after patching") for speed. The tradeoff:
- With enforcement: Slower (extra API call), but prevents edits to wrong files or stale content
- Without enforcement: Faster, but risks corruption if file changed between reads

**Recommendation:** Implement read-before-edit enforcement. Safety > speed for our use case.

### Insight 8: Background Execution and Async Patterns

Only Claude Code has explicit background execution support:
- `run_in_background` parameter on Bash
- `BashOutput` tool for incremental output retrieval
- `KillShell` for termination

This enables long-running operations (builds, tests) without blocking the agent loop.

**Recommendation:** Essential for IntelliJ where builds can take minutes.

---

## Raw Tool Schema Summary

### Claude Code Complete Tool Parameters

```
Read:
  file_path: string (required, absolute)
  offset: number (optional, 1-indexed line)
  limit: number (optional, line count)
  pages: string (optional, PDF only, e.g. "1-5")

Edit:
  file_path: string (required, absolute)
  old_string: string (required, exact match)
  new_string: string (required, must differ)
  replace_all: boolean (optional, default false)

Write:
  file_path: string (required, absolute)
  content: string (required, full file content)

Grep:
  pattern: string (required, regex)
  path: string (optional, default cwd)
  output_mode: "files_with_matches" | "content" | "count" (optional, default files_with_matches)
  glob: string (optional, file filter)
  type: string (optional, file type)
  -i: boolean (optional, case insensitive)
  -n: boolean (optional, line numbers, default true)
  -A: number (optional, lines after)
  -B: number (optional, lines before)
  -C: number (optional, context lines)
  multiline: boolean (optional, default false)
  head_limit: number (optional, limit results)
  offset: number (optional, skip results)

Glob:
  pattern: string (required, glob)
  path: string (optional, default cwd)

Bash:
  command: string (required)
  description: string (optional, 5-10 words)
  timeout: number (optional, ms, default 120000, max 600000)
  run_in_background: boolean (optional)
```

### Cline Complete Tool Parameters

```
read_file:
  path: string (required, relative to workspace)

write_to_file:
  path: string (required)
  content: string (required, full file content)

replace_in_file:
  path: string (required)
  diff: string (required, SEARCH/REPLACE blocks)

search_files:
  path: string (required, directory)
  regex: string (required, pattern)
  file_pattern: string (optional, glob filter)

list_files:
  path: string (required)
  recursive: boolean (optional)

list_code_definition_names:
  path: string (required)

execute_command:
  command: string (required)
  requires_approval: boolean (optional)
```

### OpenHands str_replace_editor Parameters

```
str_replace_editor:
  command: "view" | "create" | "str_replace" | "insert" | "undo_edit" (required)
  path: string (required, absolute)

  # For view:
  view_range: [start, end] (optional)

  # For create:
  file_text: string (required)

  # For str_replace:
  old_str: string (required)
  new_str: string (optional, empty = delete)

  # For insert:
  insert_line: number (required)
  new_str: string (required)
```

### Codex CLI Tool Parameters

```
shell:
  command: string (single shell command)
  # apply_patch intercepted from shell commands matching the pattern:
  # {"cmd": ["apply_patch", "*** Begin Patch\n..."]}

apply_patch format:
  *** Begin Patch
  *** Add File: path/to/new/file
  +line1
  +line2
  *** Update File: path/to/existing/file
  @@ context_before @@
  -removed line
  +added line
  *** Delete File: path/to/file
  *** End Patch

update_plan:
  steps: array of {text, status: "pending"|"in_progress"|"completed"}
```

---

## Sources

- [Cline Tools Reference Guide](https://docs.cline.bot/exploring-clines-tools/cline-tools-guide)
- [Claude Code Internal Tools Implementation (GitHub Gist)](https://gist.github.com/bgauryy/0cdb9aa337d01ae5bd0c803943aa36bd)
- [Claude Code Tools and System Prompt (GitHub Gist)](https://gist.github.com/wong2/e0f34aac66caf890a332f7b6f9e2ba8f)
- [Claude Code Built-in Tools Reference](https://www.vtrivedy.com/posts/claudecode-tools-reference)
- [Claude Code Tool System Explained (CallSphere)](https://callsphere.tech/blog/claude-code-tool-system-explained)
- [Claude Agent Skills Deep Dive](https://leehanchung.github.io/blogs/2025/10/26/claude-skills-deep-dive/)
- [OpenHands Tool System & MCP](https://docs.openhands.dev/sdk/arch/tool-system)
- [OpenHands GitHub](https://github.com/OpenHands/OpenHands)
- [Codex CLI GitHub](https://github.com/openai/codex)
- [Codex CLI Features](https://developers.openai.com/codex/cli/features)
- [How OpenAI Codex Works Behind the Scenes](https://blog.promptlayer.com/how-openai-codex-works-behind-the-scenes-and-how-it-compares-to-claude-code/)
- [OpenAI Codex CLI Technical Analysis](https://www.philschmid.de/openai-codex-cli)
- [Codex Prompt Definition](https://github.com/openai/codex/blob/main/codex-rs/core/prompt.md)
- [Code Surgery: How AI Assistants Make Precise Edits](https://fabianhertwig.com/blog/coding-assistants-file-edits/)
- [How Cursor Indexes Codebases Fast](https://read.engineerscodex.com/p/how-cursor-indexes-codebases-fast)
- [How Cursor Actually Indexes Your Codebase](https://towardsdatascience.com/how-cursor-actually-indexes-your-codebase/)
- [Cursor Semantic & Agentic Search](https://cursor.com/docs/context/semantic-search)
- [Fast Apply Architectures (Morph)](https://www.morphllm.com/cursor-fast-apply)
- [Cursor Instant Apply Blog](https://cursor.com/blog/instant-apply)
- [Gemini Code Assist Agent Mode](https://developers.google.com/gemini-code-assist/docs/agent-mode)
- [oh-my-pi GitHub](https://github.com/can1357/oh-my-pi)
- [Ideas in Agentic AI Tooling](https://www.seangoedecke.com/ideas-in-agentic-ai-tooling/)
- [AI Agentic Programming Survey](https://arxiv.org/html/2508.11126v1)
