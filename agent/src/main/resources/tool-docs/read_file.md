# `read_file` — extended notes

## Why this exists

Every other tool that touches a file (`edit_file`, `find_definition`, `format_code`,
`run_inspections`, `search_code` with content output) presumes the agent has seen the
file content. `read_file` is the only entry point that reliably puts file text into the
LLM's context with line numbers attached.

The line numbers are not cosmetic — they are the contract `edit_file` uses for its
SEARCH/REPLACE matching. Without them the LLM would routinely propose edits that
fail because the indentation it imagined doesn't match what's on disk.

## Reading through the IDE, not the filesystem

The tool prefers `FileDocumentManager.getCachedDocument(vFile)` over a raw file read.
This means:

- **Unsaved changes are visible.** If the user has an open editor with unsaved edits,
  the agent sees those edits — not the on-disk version. Without this, agents would
  recommend changes against stale content the user already moved on from.
- **Encoding follows IDE detection.** The `VirtualFile.charset` is honoured, falling
  back through UTF-8 → ISO-8859-1 (which is byte-lossless for legacy European/Windows
  files) → UTF-8 with replacement.
- **Long lines are line-truncated, not file-truncated.** A single 50KB line — say a
  minified JS bundle — gets truncated at 2KB with a marker. The rest of the file is
  still readable.

## What the offset/limit window achieves

Without `offset`/`limit`, reading a 10K-line file would either OOM the agent's context
window or have to silently truncate (which the LLM hates — it doesn't know what it
missed). With them, the agent does its own paging:

```
read_file(path="big.kt", offset=1, limit=200)        # see the imports and class header
read_file(path="big.kt", offset=540, limit=80)       # read the function it cares about
```

This is much cheaper than reading the whole file and asking the LLM to "find the part
that matters" — which costs tokens AND gets it wrong.

## Things that look like bugs but aren't

- **Reading a file with no trailing newline** still returns each line numbered. The
  output is `lines.joinToString("\n")` — single trailing newline is preserved if it
  was in the source.
- **`offset=1, limit=200` on a 100-line file** reads all 100 lines without error.
  No `IndexOutOfBounds` — `drop(offset).take(limit)` is bounded.
- **A binary file with a `.txt` extension** is read as text. The binary-rejection list
  is extension-driven, not content-sniffing. This is intentional — sniffing would
  add latency to every read.

## Performance posture

- Reads happen inside `readAction { }` so they don't block writers but are scheduled
  alongside other PSI/VFS work. On large indexed projects, this can briefly queue.
- `FileDocumentManager.getCachedDocument` returns null for files the IDE hasn't loaded
  yet — falling back to raw bytes via `vFile.contentsToByteArray()`. That second path
  is JVM-heap-allocating; for a 9MB file you allocate 9MB transiently.
- The 200-line default keeps each call's tool result around 7-15KB — well under the
  30K spill threshold, so reads are never sent to disk.

## Failure-mode philosophy

Errors return `ToolResult(isError = true)`, not exceptions. The LLM sees a structured
message and can recover (try a different path, drop offset/limit, swap to search_code
for binary). Crashing would terminate the iteration without giving the loop a chance
to course-correct.
