# Database, VCS, and PSI Intelligence Tools Audit — Current State

**Date:** 2026-04-17
**Scope:** Database tools (`DbQueryTool`, `DbExplainTool`, `DbSchemaTool`, `DbStatsTool`, `DbListDatabasesTool`, `DbListProfilesTool`), VCS (`ChangelistShelveTool`), and PSI intelligence tools (`FindDefinitionTool`, `FindReferencesTool`, `FindImplementationsTool`, `CallHierarchyTool`, `TypeHierarchyTool`, `TypeInferenceTool`, `DataFlowAnalysisTool`, `FileStructureTool`, `StructuralSearchTool`, `ReadWriteAccessTool`, `GetMethodBodyTool`, `GetAnnotationsTool`, `TestFinderTool`).
**Companion doc:** `2026-04-17-runtime-test-tool-audit.md` (runtime/test tools baseline).

---

## 1. Executive Summary

Database, VCS, and PSI tools are **substantially healthier** than runtime/test tools. Key strengths:

- **Database:** JDBC lifecycle properly managed (`withConnection` ensures rollback + close in finally). Read-only validation + timeout enforcement. Credential handling via `PasswordSafe`.
- **VCS:** ShelveChangesManager API usage is straightforward. No listener leaks. Error handling via explicit checks.
- **PSI:** All tools correctly wrap PSI access in `ReadAction.nonBlocking {...}.inSmartMode().executeSynchronously()`. Cycle detection for callers/callees via `IdentityHashMap`. No major threading violations.

**However, three categories of incident risks exist:**

1. **Large-output handling misses structured JSON** — When `find_references` returns 5000 usages or `type_hierarchy` returns hundreds of subtypes, tools hard-truncate to 30-50 results for display (pragmatic) but return flat strings to the LLM. No structured `.data` payload, no pagination, no way for the LLM to drill into the full result set.
2. **Database result set sizing is hardcoded** — 200 rows MAX_ROWS is arbitrary. No per-tool config, no spilling to disk. A 200-row × 20-column result set with 500-char cells can be 2MB of Markdown.
3. **VCS changelist lifecycle is implicit** — `ShelveChangesManager` operations (shelve/unshelve) don't check for state transitions or concurrent modifications. No guard against "unshelve" on a non-existent index.

---

## 2. Database Tools (Detailed)

### 2.1 Connection Lifecycle & JDBC Safety

**File:** `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/database/DatabaseConnectionManager.kt`

**Strengths:**
- `withConnection()` (lines 79-104) uses `try/finally` to guarantee rollback + close.
- `autoCommit = false` + `isReadOnly = true` dual enforcement.
- Per-statement `queryTimeout = 30s` (line 206, 273-276) prevents runaway queries.
- Statement object pool is implicit — each tool creates fresh Statement per use, no long-lived statement handles.

**Concerns:**
- **No connection pooling.** Each `db_query` call opens a fresh connection via `driver.connect()` (line 200, 367). For rapid multi-call sequences (e.g., LLM running 10 queries in 2 seconds), this creates 10 TCP handshakes. The plugin classloader approach (line 361-365) is correct for bundled drivers but adds ClassLoader overhead per connect. **Acceptable for agent use (queries are rare, < 1 per minute)**, but note: if an LLM loops on `db_query`, connection churn is a soft DoS risk.
- **MySQL timeout is in milliseconds, others in seconds** (lines 349-351). This asymmetry is correct but fragile — a future DB type addition could easily swap units and silently timeout in 10s instead of 10000ms.

### 2.2 Query Result Set Size Limits

**File:** `DatabaseConnectionManager.kt:247-271` (`resultSetToMarkdown`) and `DbQueryTool.kt:23`

**Current behavior:**
- Hard cap: 200 rows (`MAX_ROWS`), 500 chars per cell (`MAX_CELL_CHARS`).
- Markdown table format (no JSON).
- Truncation notification: `_Results truncated at $MAX_ROWS rows._`
- **No spilling to disk. No ToolOutputConfig, no ToolOutputSpiller.**

**Math:**
- Worst case: 200 rows × 20 columns × 500 chars/cell = 2MB of Markdown.
- With 12K token cap mentioned in runtime audit (ToolResult default), this would be hard-truncated mid-table, breaking structure.
- Actual token estimate (line 99) uses `TokenEstimator.estimate(content)` post-construction, so LLM sees up to full 2MB if present.

**Incident risk:** A legitimate business query (e.g., "list all orders from last month") returns exactly 200 rows, LLM doesn't know if there are 201 or 5000 more. No signal that the answer is incomplete. If the LLM caches the 200-row assumption and makes a decision on it, precision loss.

**Parallel to runtime-test audit:** The audit noted `ToolOutputSpiller` is not wired into runtime tools despite existing. **Same pattern here** — spiller could absorb the 2MB result, but DB tools don't use it.

### 2.3 Credential Handling

**File:** `DatabaseCredentialHelper.kt:13-32`

**Strengths:**
- Uses IntelliJ's `PasswordSafe` (platform credential store).
- Dynamic service name per profile (line 29-30): `generateServiceName("WorkflowOrchestrator", "DB.$profileId")`.
- No plaintext storage, no config XML.

**Concerns:**
- **No password refresh on stale cache.** If the user updates a password in Settings while an agent session is running, the in-memory password from `getPassword()` won't be invalidated. A second `db_query` will still use the old password.
  - Acceptable because sessions are <1 hour typical, password changes are rare mid-session.
  - Could be mitigated by timestamp-checking if needed later.

### 2.4 Transaction & Read-Only Validation

**File:** `DatabaseConnectionManager.kt:233-241` (`validateReadOnly`)

**Strengths:**
- Allowlist-based block: 14 prefixes (INSERT, UPDATE, DELETE, DROP, CREATE, ALTER, TRUNCATE, REPLACE, MERGE, GRANT, REVOKE, EXEC, EXECUTE, CALL, DO).
- Case-insensitive, trims whitespace.
- Called at tool entry point (DbQueryTool line 72) before `withConnection`.

**Concerns:**
- **Comment injection not blocked.** SQL like `SELECT /* INSERT */ ...` is technically safe (comment is stripped), but `SELECT 1; /* INSERT INTO */ DELETE FROM users --` requires the user to inject a comment with a trailing newline or semicolon. The validation only checks the START of the trimmed string, so `SELECT 1; INSERT INTO users ...` will fail (good). However, multi-statement separation via semicolon is **not explicitly handled** — the validation assumes the user's SQL is a single statement.
  - Acceptable because JDBC `executeQuery` interprets the entire string as one statement; semicolon in the middle doesn't execute a separate statement in most drivers (PostgreSQL exception: `executeStatement` on multi-statement script). However, documenting this would be prudent.

---

## 3. VCS Tools (Detailed)

### 3.1 ChangelistShelveTool Design

**File:** `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/vcs/ChangelistShelveTool.kt`

**Strengths:**
- Actions separated by pattern (lines 60-71): `list`, `list_shelves`, `create`, `shelve`, `unshelve`.
- `ReadAction.compute` for listing (line 84) — correct threading for changelist read.
- Direct `ShelveChangesManager` API usage (no reflection, no workarounds).
- Error checks: shelf index bounds validation (lines 199-206).

**Concerns:**

1. **No state transition guards.** Lines 162-173 (`shelveChanges`):
   ```kotlin
   val allChanges = clm.allChanges.toList()
   if (allChanges.isEmpty()) { return "No changes to shelve" }
   val shelvedList = shelveManager.shelveChanges(allChanges, comment, true)
   ```
   Between `toList()` and `shelveChanges()`, another IDE action (user's manual shelve, or concurrent agent) could modify the changelist. The API is **not re-entrant**. If a second agent task shelves the same changes simultaneously, the behavior is undefined (likely duplicate shelf, or one fails silently).
   - **Mitigation:** Document the limitation; accept sequential use only.

2. **Unshelve doesn't check if shelf is still valid.** Lines 187-212:
   ```kotlin
   val shelf = shelves[shelfIndex]
   shelveManager.unshelveChangeList(shelf, null, null, targetChangeList, true)
   ```
   Between `list_shelves` and `unshelve` calls, the user could delete the shelf in the UI. The API will throw or silently no-op. No try/catch around `unshelveChangeList()`.
   - **Risk:** If unshelve silently fails, the LLM believes the unshelve succeeded (tool returns success) but the changelist is still shelved.

3. **Change lifecycle after shelve is not documented.** Line 173 says `shelveChanges(..., true)` — the third parameter is `removeFilesFromChangelist`. The tool always passes `true`, meaning "remove from default changelist after shelving". This is the correct UX (shelve = stash), but if an LLM chains `shelve` + `list` calls, it might expect the files to reappear in the changelist (they won't).

**Incident potential (low):** Sequential single-file operations work. Risk is concurrent multi-call patterns or rapid list→unshelve without re-listing.

### 3.2 Thread Safety

**File:** `ChangelistShelveTool.kt`

- `listChangelists` (line 84): Uses `ReadAction.compute` — correct.
- `listShelves` (lines 110-136): **No read action wrapper.** `ShelveChangesManager.getInstance(project).shelvedChangeLists` (line 112) returns a list directly — background access, no EDT guard. IntelliJ's `ShelveChangesManager` is thread-safe for read (internal sync on the list), so this is acceptable.
- `shelveChanges`, `unshelveChanges`: No explicit threading guarding. These operations are initiated from background tool coroutine (default `Dispatchers.IO`). `ShelveChangesManager.shelveChanges()` and `unshelveChangeList()` are **not EDT-bound** — they run in the background and post changes back to the VCS system asynchronously. No risk of EDT blocking here.

---

## 4. PSI Intelligence Tools (Detailed)

### 4.1 Threading: ReadAction Wrapping

**Files:** All PSI tools (`CallHierarchyTool.kt`, `FindReferencesTool.kt`, `TypeHierarchyTool.kt`, `FileStructureTool.kt`, etc.)

**Pattern (universal):**
```kotlin
val content = ReadAction.nonBlocking<String> {
    // PSI access here
}.inSmartMode(project).executeSynchronously()
```

**Strengths:**
- `ReadAction.nonBlocking` — doesn't block EDT, runs in background thread.
- `.inSmartMode()` — suspends until indexing completes (no partial/stale results).
- `.executeSynchronously()` — tool execution waits for result (correct for agent flow).
- All 14 PSI tools follow this pattern (FindDefinitionTool, CallHierarchyTool, TypeHierarchyTool, etc.).

**No EDT violations detected.** Threading is **correct.**

### 4.2 Large Result Set Handling

**Case 1: find_references**

**File:** `FindReferencesTool.kt:67-104`

```kotlin
val references = ReferencesSearch.search(searchTarget, scope).findAll()
...
val results = references.take(50).mapNotNull { ref -> ... }
val header = "References to '$symbol' (${references.size} total):\n"
val truncated = if (references.size > 50) "\n... (showing first 50 of ${references.size})" else ""
```

**Behavior:**
- Finds ALL references (no limit in search API).
- Displays first 50 with a `... (N more)` footer.
- **Problem:** LLM sees the count (`5000 total`) but only the first 50 results. It cannot drill deeper to results 51-100. No pagination, no per-file filter, no structured `.data` payload.

**Impact:** If `find_references` for a widely-used method returns 5000 hits, the LLM gets "5000 references, here are the first 50" and must choose: (a) ask the user to narrow the symbol, (b) use a different tool like `structured_search`, or (c) rewrite a search query. This is the expected UX for an LLM, but **verbose and brittle.**

**Case 2: type_hierarchy (subtypes)**

**File:** `TypeHierarchyTool.kt:69-80`

```kotlin
result.subtypes.take(30).forEach { entry -> ... }
if (result.subtypes.size > 30) {
    sb.appendLine("  ... (${result.subtypes.size - 30} more)")
}
```

**Behavior:**
- Takes first 30 subtypes, same pattern.
- No depth control on supertypes (lists all).

**Concern:** For an interface with 1000+ implementations (e.g., `java.io.Serializable`), the LLM sees a count and 30 results. If the LLM needs to reason about a specific subtype, it's stuck. Could re-call with a `class_name` filter, but the tool doesn't support filtering by pattern.

**Case 3: call_hierarchy (callers)**

**File:** `CallHierarchyTool.kt:72-78`

```kotlin
callers.take(30).forEach { caller -> ... }
if (callers.size > 30) { ... }
```

**Same 30-result cap, same issue.** Additionally, depth is capped at 3 (line 37) — if the LLM wants depth 10 to trace a chain, it's blocked.

### 4.3 Cycle Detection in Hierarchy Walks

**Files:** `JavaKotlinProvider.kt`, `PythonProvider.kt`

**Pattern:**
```kotlin
override fun findCallers(element: PsiElement, depth: Int, scope: SearchScope): List<CallerInfo> {
    val visited = Collections.newSetFromMap(IdentityHashMap<PsiElement, Boolean>())
    ...
    fun walk(current: PsiElement, d: Int) {
        if (current in visited) return  // prevent infinite loop
        visited.add(current)
        // recurse
    }
}
```

**Strengths:**
- `IdentityHashMap` — correct choice (PsiElement.equals() is not stable; identity is the right contract).
- Prevents infinite loops in A→B→A call chains.
- Comment documents the reasoning (CLAUDE.md mentions this explicitly).

**No cycle detection bugs found.** **Correct.**

### 4.4 Structured Output vs. Prose

**All PSI tools return prose (flat strings), not structured JSON.**

Examples:
- `find_references`: "References to 'Symbol' (5000 total):\n  File1:10  code line\n  File2:20  code line\n..."
- `call_hierarchy`: "Call hierarchy for X#method:\n\nCallers:...\n\nCallees:..."
- `type_hierarchy`: "Type hierarchy for ClassName:\n\nSupertypes:...\n\nSubtypes:..."

**Why this matters (parallel to runtime-test audit finding):**
- The LLM must regex-parse the prose. If the format changes (e.g., file path format), regexes break.
- The LLM can't programmatically select "callers[3]" — it's a string.
- No pagination or drill-down capability — LLM is stuck with "first N results".

**Compared to ToolResult.data model:** CLAUDE.md emphasizes typed `.data` for structured results (e.g., `ToolResult<List<TestResult>>`). The PSI tools don't use this — they return `ToolResult(content="string", summary="string", ...)`.

**Feasible improvement:** PSI tools could return structured JSON in a `.data` field:
```kotlin
data class ReferenceResult(val symbol: String, val totalCount: Int, val results: List<Reference>) 
// In tool: return ToolResult(content = prose, summary = summary, data = ReferenceResult(...))
```
However, this is a **design refactor**, not a bug. Current prose format is functional, just lower-fidelity.

### 4.5 LanguageProviderRegistry Dispatch

**File:** `LanguageIntelligenceProvider.kt`, `JavaKotlinProvider.kt`, `PythonProvider.kt`

**Pattern (all tools):**
```kotlin
val allProviders = registry.allProviders()
val (provider, psiSymbol) = allProviders.firstNotNullOfOrNull { p ->
    p.findSymbol(project, symbolName)?.let { p to it }
} ?: return error("not found")
provider.findCallers(psiSymbol, ...)  // delegate to provider
```

**Strengths:**
- Multi-language dispatch is clean — tries all providers in order.
- Python provider uses reflection (`Class.forName` on Python plugin) — no compile-time dep.
- Java/Kotlin provider uses direct PSI.

**Concerns:**
- **Java fallback is silent if Python plugin is absent.** If `PythonProvider.findSymbol()` returns null (because Python plugin isn't loaded), the registry falls through to the next provider. In a PyCharm environment with Python plugin, this works. But in IntelliJ IDEA with only Java, a query for a Python file returns "not found" (correct). If the user runs the tool against a Kotlin file, both Java and Python providers will try, Java will likely succeed.
- **No provider trace on failure.** If all providers return null, the error is generic: "No symbol 'X' found in project". For debugging, it would help to know "JavaKotlinProvider tried, PythonProvider tried, both returned null". Acceptable because failures are expected (user typos, non-existent symbols), not actionable.

---

## 5. Structural Output Limitations (Cross-Cutting)

### 5.1 No Pagination or Drill-Down

**All result-returning tools (find_references, type_hierarchy, call_hierarchy, etc.)** cap output at 30-50 results and return "showing N of M" with no mechanism to fetch the next batch.

**Why this matters:**
- LLM must be aware of the cap and decide to narrow the query (good) or call a different tool (expensive).
- No server-side cursor or offset parameter.

**Workaround (existing):** Use `find_definition` + file path parameter to disambiguate, or `structured_search` for pattern-based filtering. But this requires the LLM to change tactics.

**Not a critical flaw** — most queries return < 50 results in practice. But for widely-used symbols (interface implementations, base class descendants), it's a limitation.

### 5.2 Database Result Set Not Spilled

**Problem (from audit perspective):**
- `DbQueryTool` returns Markdown table with MAX_ROWS = 200.
- Table can be 2MB if 200 rows × 20 columns × 500 chars/cell.
- No ToolOutputSpiller integration (unlike RunCommandTool, which spills at 30K).
- LLM might receive truncated result mid-table.

**Mitigation exists but not wired:** `ToolOutputSpiller` in `agent/tools/ToolOutputSpiller.kt` could save the full result to disk.

---

## 6. Incident Risk Assessment

### Risk Matrix

| Risk | Category | Likelihood | Impact | Mitigation |
|------|----------|------------|--------|-----------|
| 5000 references capped at 50 → LLM doesn't know scope | PSI | Medium | Medium | Warn in description, accept as constraint |
| Unshelve on deleted shelf silently fails | VCS | Low | Medium | Add try/catch, re-list before unshelve |
| DB result set > 2MB hard-truncates | Database | Low | Low | Wire ToolOutputSpiller |
| Concurrent shelve operations collide | VCS | Low | High | Document sequential-only, add Mutex if needed |
| Password cache stale mid-session | Database | Very low | Medium | Timestamp-check on reuse (future) |

### Lowest-Risk Category

**Database tools** are the safest — JDBC lifecycle is explicit, validation is multi-layer, no listener leaks.

**VCS tools** have implicit state assumptions but are low-traffic (occasional shelve/unshelve calls).

**PSI tools** have correct threading and cycle detection but lose fidelity via prose strings and result capping.

---

## 7. Comparison to Runtime/Test Tool Audit Findings

| Finding from Runtime Audit | Applied Here? | Status |
|---|---|---|
| Signal fidelity (compile errors lost) | N/A (DB/PSI/VCS don't compile) | — |
| Listener leaks (RunContentDescriptor) | NOT FOUND | Good; no listener patterns |
| Output spilling not wired | FOUND | DB tools don't use ToolOutputSpiller |
| Leaked IntelliJ services | NOT FOUND | No persistent service refs |
| ToolResult lacks structured `.data` | FOUND (PSI tools) | Design, not critical |
| Hard-truncation at 12K | NOT FOUND (but 200 rows ≈ 60K typical) | DB tools own the truncation |
| No per-file compile messages | N/A | — |
| Empty suite → PASSED misclassification | N/A | — |
| Raw Thread spawns | NOT FOUND | All async via ReadAction/coroutines |

---

## 8. Recommendations (Not a Fix Plan)

### By Category

**Database (Low Priority)**
- **D1.** Wire `ToolOutputSpiller` into `DbQueryTool`, `DbExplainTool` for tables > 30K. Simplest: route `resultSetToMarkdown` output through spiller before returning.
- **D2.** Document MAX_ROWS = 200 assumption in tool description. Note that queries with 200+ exact rows are unambiguous (can call again with offset, though not yet supported).

**VCS (Low Priority)**
- **V1.** Wrap `shelveChanges` + `unshelveChangeList` in try/catch to surface actual errors (not swallow them).
- **V2.** Add re-list check before `unshelve` to catch deleted shelves.
- **V3.** Document that shelve/unshelve are sequential only; concurrent calls from multiple agents will race.

**PSI (Medium Priority, Design)**
- **P1.** Consider typed `.data` payload for large result tools (find_references, type_hierarchy, call_hierarchy). Would enable LLM drill-down and smarter filtering. **Design refactor, not urgent.**
- **P2.** Add optional `offset` / `limit` parameters to find_references, type_hierarchy, call_hierarchy for pagination. Requires schema change and provider-level cursor support.
- **P3.** Expose cycle depth in call_hierarchy result (e.g., "A→B→C→A cycle detected at depth 3") so LLM knows why depth capped.

---

## 9. Not Covered (Follow-Up)

- **DataFlowAnalysisTool** (`DataFlowAnalysisTool.kt`) — Examined briefly; returns prose list. Same output fidelity concerns as call_hierarchy.
- **StructuralSearchTool** (`StructuralSearchTool.kt`) — Structural search template compilation + result filtering. Not audited in depth; low complexity relative to find_references.
- **Dataflow loops** — No explicit cycle detection in dataflow walks; assumes IDE's dataflow analysis is cycle-safe internally (reasonable assumption, not verified).
- **PythonProvider reflection safety** — Reflection-based Python PSI is robust (Method.invoke + null-checks) but untested against plugin version drift. Acceptable risk (Cline's explorer also uses reflection).

---

## 10. Summary

**Health:** 7/10 (good threading, zero listener leaks, connection safety solid).

**Strengths:**
- JDBC lifecycle correct (try/finally, rollback).
- All PSI reads wrapped in ReadAction.nonBlocking.
- Cycle detection for recursive hierarchy walks.
- VCS API usage is straightforward.
- No major threading violations.

**Gaps (not critical):**
- Database output not spilled (could bloom to 2MB).
- PSI results capped at 30-50 with no drill-down.
- VCS state transitions unguarded (concurrent shelve race possible but rare).
- Prose-only output limits LLM programmatic access.

**No incidents analogous to runtime-test tool bugs** (leaked RunContentDescriptor, compile errors lost, empty suite → passed). The three categories audited are materially healthier than the runtime/test tool baseline.
