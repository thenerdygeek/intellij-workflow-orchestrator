# Agentic Pipeline Adaptation Plan

**Date:** 2026-03-26
**Status:** IN PROGRESS — Phases 3E, 3A, 3B implemented. Phase 3C in progress.
**Context:** Based on comprehensive research of 23+ enterprise frameworks, 40+ courses, and 27 GitHub tools

---

## Executive Summary

Our current architecture is **production-aligned** — the ReAct loop, tool system, approval gates, compression, and subagent delegation all match industry best practices. The research reveals **5 high-impact gaps** and **3 additive features** that would elevate our pipeline from "solid single-agent" to "enterprise-grade agentic platform."

---

## Current Architecture vs Industry Standard

### What We Already Have (Validated)

| Capability | Our Implementation | Industry Standard | Status |
|---|---|---|---|
| ReAct loop | `SingleAgentSession` — 50-iteration while-loop | Universal (Claude SDK, OpenAI, LangGraph) | **Aligned** |
| Tool system | 98 tools, hybrid 3-layer selection, read-only parallel | MCP + dynamic tool loading | **Ahead** (98 tools is massive) |
| Streaming | Token streaming via JCEF + `onStreamChunk` | SSE/WebSocket token streaming | **Aligned** |
| Approval gates | Risk-based (NONE→DESTRUCTIVE), async UI | Hook-based PreToolUse/PostToolUse | **Aligned** |
| Context compression | 2-threshold (93%/70%), anchored summaries, tiered pruning | LLM-powered compaction at 70-85% | **Ahead** (more sophisticated) |
| Subagents | `SpawnAgentTool` — background/foreground, resume, kill | Claude SDK Agent tool, LangGraph subgraphs | **Aligned** |
| Plans | `PlanManager` — create/approve/revise, compression-proof | LangGraph state, CrewAI task plans | **Aligned** |
| Guardrails | `GuardrailStore` — learned constraints, doom loop detection | OpenAI guardrails, Claude hooks | **Aligned** |
| Security | Input sanitization, output validation, credential redaction, path validation | Standard practice | **Aligned** |
| Budget enforcement | 4-status (OK→TERMINATE), graceful degradation | Claude SDK budget tracking | **Aligned** |
| Rollback | `AgentRollbackManager` + `CheckpointStore` | LangGraph time-travel, LocalHistory | **Partial** |
| Skills | User-extensible markdown skills, activation via tool | Claude SDK skills, Letta tools | **Aligned** |

### Critical Gaps (Ordered by Impact)

| Gap | Current State | Industry Standard | Impact |
|---|---|---|---|
| **1. Durable Execution** | JSONL persistence, basic checkpoint.json | Temporal: survives crashes, auto-resume. LangGraph: PostgreSQL checkpoints, time-travel | **CRITICAL** — IDE crashes lose agent state |
| **2. Cross-Session Memory** | `AgentMemoryStore` — markdown files, no semantic search | Letta: 3-tier self-editing memory. Mem0: vector search + relationships | **HIGH** — Agent forgets everything between sessions |
| **3. Structured Evaluation** | `SessionTrace` + `AgentEventLog` (raw logs) | LangSmith: traces + scoring. Braintrust: automated evals. Google ADK: built-in eval | **HIGH** — No way to measure agent quality over time |
| **4. Agent Teams / Workflows** | Single-agent + subagents (flat hierarchy) | LangGraph: graph workflows. CrewAI: role-based teams. OpenAI: handoff chains | **MEDIUM** — Complex multi-step workflows need orchestration |
| **5. Self-Correction Loop** | Backpressure gates (nudge after N edits) | Reflexion pattern: run → evaluate → reflect → retry. LangGraph: conditional retry edges | **MEDIUM** — Agent doesn't systematically verify its own work |

---

## Adaptation Roadmap

### Phase 3A: Durable Execution & Checkpointing (CRITICAL)

**Goal:** Agent survives IDE crashes, network failures, and process restarts. Resume any session from where it left off.

**What the industry does:**
- **Temporal:** Every function call is checkpointed. On failure, replay from last checkpoint. 99.99% SLA.
- **LangGraph:** PostgreSQL/SQLite checkpointer saves state at every graph node. Thread-based sessions.
- **Claude SDK:** `session_id` resume restores full context. Session forking for branching.

**What we need to change:**

| Component | Current | Target | Effort |
|---|---|---|---|
| `CheckpointStore` | Single `checkpoint.json` per session | **Per-iteration checkpoints** with full conversation state | Medium |
| `ConversationStore` | JSONL append-only, loaded fully on resume | **Indexed JSONL** with iteration markers for partial loading | Medium |
| Session resume | Manual (context rotation creates new session) | **Automatic resume** — on IDE restart, offer to continue last session | Low |
| Crash recovery | Lost (process dies = session dies) | **Coroutine checkpoint** — save state before each tool call, resume after | High |
| Session management | `GlobalSessionIndex` (flat list) | **Thread-based** — sessions organized by task/thread (LangGraph pattern) | Medium |

**Architecture:**

```
Before each tool call:
  1. Save iteration state (messages, plan, facts, guardrails, working set)
  2. Save tool call intent (what tool, what params)
  3. Execute tool
  4. Save tool result
  5. Continue loop

On IDE restart:
  1. Load GlobalSessionIndex
  2. Find incomplete sessions
  3. Show "Resume session?" dialog with session summary
  4. Load checkpoint → reconstruct ConversationSession
  5. Resume ReAct loop from saved iteration
```

**Key files to modify:**
- `agent/runtime/CheckpointStore.kt` — Per-iteration state snapshots
- `agent/runtime/ConversationStore.kt` — Indexed loading with resume points
- `agent/runtime/SingleAgentSession.kt` — Checkpoint before each tool call
- `agent/service/GlobalSessionIndex.kt` — Thread-based organization
- `agent/ui/AgentController.kt` — Resume dialog on startup

---

### Phase 3B: Three-Tier Memory System (HIGH)

**Goal:** Agent remembers project context, past decisions, and user preferences across sessions. Reduces repetitive re-discovery.

**Constraint:** No local ML models (HuggingFace, ONNX, etc.). All search must use traditional IR or LLM-assisted tagging.

**What the industry does:**
- **Letta (MemGPT):** Core memory (always in prompt, 2KB), archival memory (vector store, unlimited), recall memory (conversation search). Agent decides what to remember via `core_memory_append`, `archival_memory_insert`, `conversation_search` tools.
- **Mem0:** Multi-level (user/session/agent), semantic search, relationship graphs. 186M+ API calls.
- **Claude SDK:** CLAUDE.md files + auto-memory markdown files. Simple but effective.

**What we have vs what we need:**

| Layer | Current | Target |
|---|---|---|
| **Core Memory** (always in prompt) | `AgentMemoryStore` loads MEMORY.md index + recent topic files | **Structured core memory** — user profile, project context, active constraints. Self-editable by agent via tools. Size-capped (2-4KB). |
| **Archival Memory** (searchable long-term) | None | **Lucene full-text index** with LLM-generated tags — past decisions, code patterns, resolved issues. Hybrid BM25 + tag-boosted search via `archival_memory_search` tool. |
| **Recall Memory** (conversation history) | `ConversationStore` JSONL (no search) | **Searchable conversation index** — agent can search past sessions by keyword. `conversation_search` tool. |

**New tools to add:**
```
core_memory_read()       → Read current core memory block
core_memory_append()     → Add to core memory (agent decides what's important)
core_memory_replace()    → Update existing core memory entry
archival_memory_insert() → Store long-term knowledge (LLM generates tags at insert time)
archival_memory_search() → BM25 + tag-boosted search over archival store
conversation_search()    → Keyword search over past session transcripts
```

**Search Architecture: Hybrid Lucene BM25 + LLM Tags (No ML Models)**

Apache Lucene is already bundled with IntelliJ IDEA (used for Find in Files, code indexing). We reuse it — zero additional dependencies.

```
INSERT FLOW:
  Text: "Fixed EDT freeze in BambooService by replacing runBlocking with coroutineScope"
      ↓ LLM call (Cody, at insert time only)
  Tags: ["edt", "freeze", "bamboo", "runblocking", "coroutine", "threading", "deadlock"]
      ↓ Lucene IndexWriter
  Stored: { content: "Fixed EDT...", tags: "edt freeze bamboo...", type: "resolution", timestamp: ... }

SEARCH FLOW (instant, local, no LLM call):
  Query: "threading crash bamboo"
      ↓ Lucene BooleanQuery
  Search: content:"threading crash bamboo" (1x weight) + tags:"threading crash bamboo" (3x weight)
      ↓ BM25 scoring + tag boost
  Result: "Fixed EDT freeze in BambooService..." (score: 8.4)
```

**Why Lucene BM25 + LLM Tags instead of vector embeddings:**

| Aspect | Vector Embeddings | Lucene BM25 + LLM Tags |
|---|---|---|
| Dependency | Requires ML model (30MB+) or external API | Zero — Lucene is in IntelliJ's classpath |
| Semantic gap | "crash" finds "freeze" natively | LLM tags bridge the gap at insert time |
| Search speed | ~5ms (brute force) to ~1ms (HNSW) | ~1ms (inverted index) |
| Insert cost | Embedding computation | 1 LLM call for tags (~50 tokens) |
| Search cost | Vector similarity computation | Zero (local index lookup) |
| Debuggability | Opaque float arrays | Human-readable tags and content |
| Plugin size impact | +30MB (ONNX model) | +0MB (already bundled) |

**Lucene features we use:**
- `BM25Similarity` — relevance ranking (term frequency, inverse document frequency)
- `FuzzyQuery` — typo tolerance ("bambo" finds "bamboo")
- `BoostQuery` — tags weighted 3x over content for precision
- `StandardAnalyzer` — tokenization, lowercasing, stop word removal
- `FSDirectory` — persistent index on disk

**Implementation sketch:**

```kotlin
class ArchivalMemory(
    private val indexDir: Path,       // {projectBasePath}/.workflow/agent/archival/index
    private val brain: LlmBrain       // For tag generation at insert time
) {
    private val analyzer = StandardAnalyzer()
    private val directory = FSDirectory.open(indexDir)

    // INSERT — 1 LLM call to generate tags, then instant Lucene write
    suspend fun insert(text: String, metadata: Map<String, String>) {
        val tags = generateTags(brain, text)  // LLM: "extract 5-10 searchable keywords"
        val doc = Document().apply {
            add(TextField("content", text, Field.Store.YES))
            add(TextField("tags", tags.joinToString(" "), Field.Store.YES))
            add(StringField("type", metadata["type"] ?: "general", Field.Store.YES))
            add(StringField("sessionId", metadata["sessionId"] ?: "", Field.Store.YES))
            add(LongField("timestamp", System.currentTimeMillis(), Field.Store.YES))
        }
        IndexWriter(directory, IndexWriterConfig(analyzer)).use { it.addDocument(doc) }
    }

    // SEARCH — instant, local, no LLM call
    fun search(query: String, topK: Int = 5): List<MemoryEntry> {
        val escaped = QueryParser.escape(query)
        val contentQuery = QueryParser("content", analyzer).parse(escaped)
        val tagQuery = BoostQuery(QueryParser("tags", analyzer).parse(escaped), 3.0f)
        val combined = BooleanQuery.Builder()
            .add(contentQuery, BooleanClause.Occur.SHOULD)
            .add(tagQuery, BooleanClause.Occur.SHOULD)
            .build()
        DirectoryReader.open(directory).use { reader ->
            val searcher = IndexSearcher(reader)
            val results = searcher.search(combined, topK)
            return results.scoreDocs.map { hit ->
                val doc = searcher.storedFields().document(hit.doc)
                MemoryEntry(
                    content = doc.get("content"),
                    tags = doc.get("tags").split(" "),
                    type = doc.get("type"),
                    score = hit.score,
                    timestamp = doc.getField("timestamp").numericValue().toLong()
                )
            }
        }
    }

    // TAG GENERATION — LLM extracts searchable keywords at insert time
    private suspend fun generateTags(brain: LlmBrain, text: String): List<String> {
        val response = brain.chat("""
            Extract 5-10 searchable keywords/tags from this text.
            Include: concepts, tools, error types, file names, patterns, synonyms.
            Return ONLY a comma-separated list, nothing else.
            Text: $text
        """.trimIndent())
        return response.split(",").map { it.trim().lowercase() }.filter { it.isNotBlank() }
    }
}
```

**What gets stored automatically:**

| Trigger | What Gets Indexed | Type |
|---|---|---|
| Session completion | Session summary (task + outcome + key decisions) | `session_summary` |
| GuardrailStore save | Learned constraint with context | `guardrail` |
| FactsStore consolidation | Verified discoveries (file reads, patterns, errors) | `fact` |
| Plan completion | Plan steps + outcomes | `plan_outcome` |
| Agent `save_memory` tool | User-directed memory (agent decides what's important) | `agent_memory` |
| Error resolution | What failed + how it was fixed | `error_resolution` |

**Storage location:**
- Index: `{projectBasePath}/.workflow/agent/archival/index/` (Lucene index files)
- Cap: 5,000 entries maximum, oldest evicted when full
- Lifecycle: tied to `Disposable` — IndexWriter closed on project close

**Key files to create/modify:**
- `agent/context/CoreMemory.kt` — Fixed-size, always-in-prompt memory block
- `agent/context/ArchivalMemory.kt` — Lucene-backed full-text search with LLM tags
- `agent/context/RecallMemory.kt` — Conversation index with keyword search
- `agent/tools/builtin/MemoryTools.kt` — 6 new memory tools
- `agent/orchestrator/PromptAssembler.kt` — Inject core memory into system prompt

---

### Phase 3C: Structured Evaluation & Observability (HIGH)

**Goal:** Measure agent quality over time. Know when it's improving or degrading. Catch regressions.

**What the industry does:**
- **LangSmith:** Traces every LLM call + tool execution. Scoring rubrics. Dataset-driven evals.
- **Braintrust:** Automated eval pipelines. A/B testing prompts. Cost tracking.
- **Google ADK:** Built-in `AgentEvaluator` with configurable metrics.
- **AgentOps:** Session replays, cost dashboards, compliance tracking.

**What we need:**

| Component | Description | Effort |
|---|---|---|
| **Session Scorecard** | After each session: task completion %, tool efficiency (calls vs needed), error rate, compression count, total cost | Low |
| **Quality Metrics** | Track per session: hallucination flags (output validator catches), self-correction count, plan adherence (steps completed vs planned) | Medium |
| **Cost Dashboard** | Token usage per session, per tool, per model. Trend over time. | Low |
| **Session Replay** | Visual timeline of session: each iteration, tool calls, approvals, compressions. Clickable. | Medium |
| **Eval Framework** | Define test scenarios (prompt → expected outcome). Run automatically. Score results. | High |

**Session Scorecard schema:**

```kotlin
SessionScorecard {
  sessionId: String
  taskDescription: String
  completionStatus: COMPLETED | FAILED | CANCELLED | ROTATED
  metrics: {
    totalIterations: Int
    toolCallCount: Int
    errorCount: Int
    compressionCount: Int
    totalInputTokens: Long
    totalOutputTokens: Long
    estimatedCost: Double
    planAdherence: Float       // steps completed / steps planned
    selfCorrectionCount: Int   // backpressure triggers
    approvalCount: Int
    durationMs: Long
  }
  qualitySignals: {
    halluccinationFlags: Int   // from OutputValidator
    credentialLeaks: Int       // from CredentialRedactor
    doomLoopTriggers: Int      // from LoopGuard
    guardrailHits: Int         // from GuardrailStore
  }
}
```

**Key files to create/modify:**
- `agent/runtime/SessionScorecard.kt` — Computed at session end
- `agent/runtime/AgentMetrics.kt` — Extend with quality signals
- `agent/ui/SessionReplayPanel.kt` — Visual timeline (JCEF)
- `agent/service/MetricsStore.kt` — Persist scorecards for trend analysis

---

### Phase 3D: Agent Teams & Workflow Orchestration (MEDIUM)

**Goal:** Enable complex multi-step workflows with specialized agents that communicate and hand off work.

**What the industry does:**
- **LangGraph:** Graph-based. Nodes = agents/functions. Edges = state transitions. Conditional routing. Human approval at any node.
- **OpenAI:** Handoff pattern — agent transfers conversation to another agent with full context.
- **CrewAI:** Role-based teams. Manager agent delegates to specialists. Sequential or hierarchical.
- **Google ADK:** SequentialAgent, ParallelAgent, LoopAgent composites.

**What we have vs what we need:**

| Capability | Current | Target |
|---|---|---|
| Subagent spawning | `SpawnAgentTool` — flat, parent-child only | **Named agent teams** with defined roles and communication channels |
| Inter-agent communication | Parent gets child's final result only | **Shared state** — agents can read/write to a shared workspace |
| Workflow definition | Implicit (LLM decides via tools) | **Explicit workflows** — define sequences like: Research → Plan → Implement → Test → Review |
| Handoff | None (subagent returns, parent continues) | **Context-preserving handoff** — transfer full conversation to specialist agent |
| Parallel execution | Background agents (`run_in_background=true`) | **Coordinated parallel** — multiple agents work on different parts, results merge |

**Proposed Architecture: Hybrid (Subagent + Lightweight Workflow)**

Rather than building a full graph engine (LangGraph), extend our existing subagent model:

```kotlin
// Define a workflow
val workflow = AgentWorkflow(
    name = "feature-implementation",
    steps = listOf(
        WorkflowStep("research", agentType = "explorer", prompt = "Research the codebase for..."),
        WorkflowStep("plan", agentType = "general", prompt = "Create plan based on research"),
        WorkflowStep("implement", agentType = "coder", prompt = "Implement the plan", approval = true),
        WorkflowStep("test", agentType = "coder", prompt = "Write and run tests"),
        WorkflowStep("review", agentType = "reviewer", prompt = "Review all changes")
    ),
    sharedState = WorkflowState()  // Shared context between steps
)
```

**Key additions:**
```
agent/workflow/AgentWorkflow.kt        — Workflow definition and execution
agent/workflow/WorkflowStep.kt         — Individual step with agent config
agent/workflow/WorkflowState.kt        — Shared state between steps
agent/workflow/WorkflowExecutor.kt     — Runs steps sequentially/parallel
agent/tools/builtin/WorkflowTools.kt   — create_workflow, run_workflow, workflow_status
```

**Inter-agent communication via shared state:**
```kotlin
SharedWorkflowState {
  findings: Map<String, String>     // Research results
  plan: AgentPlan                   // Implementation plan
  files: Set<String>                // Files touched
  decisions: List<Decision>         // Key decisions made
  blockers: List<String>            // Issues found
}
```

---

### Phase 3E: Self-Correction & Reflection (MEDIUM)

**Goal:** Agent systematically verifies its own work and corrects mistakes before presenting results.

**What the industry does:**
- **Reflexion pattern:** Execute → Evaluate → Reflect → Retry (with reflection as additional context)
- **LangGraph:** Conditional edges — if test fails, loop back to implementation node
- **CrewAI:** Task `output_validator` checks agent output meets criteria
- **Anthropic guidance:** "Start with simple prompts over complex multi-agent. Let LLM decide."

**What we need:**

| Component | Description |
|---|---|
| **Post-action verification** | After edit: run relevant tests. After command: check exit code + output for errors. |
| **Reflection tool** | Agent explicitly reflects on what worked/didn't before retrying |
| **Conditional retry** | If verification fails, agent gets structured feedback and retries (max 3) |
| **Quality gate** | Before marking task complete, run checklist: tests pass? no new errors? plan steps done? |

**Implementation — extend existing `BackpressureGate`:**

```kotlin
class SelfCorrectionLoop(
    private val verifiers: List<Verifier>,  // test runner, linter, type checker
    private val maxRetries: Int = 3
) {
    suspend fun verifyAndCorrect(action: AgentAction, result: ToolResult): VerificationResult {
        for (attempt in 1..maxRetries) {
            val verification = verifiers.map { it.verify(action, result) }
            val failures = verification.filter { !it.passed }

            if (failures.isEmpty()) return VerificationResult.Passed

            // Inject reflection prompt
            val reflection = buildReflectionPrompt(action, failures)
            return VerificationResult.NeedsRetry(reflection)
        }
        return VerificationResult.Failed(reason = "Max retries exceeded")
    }
}
```

**Key files to create/modify:**
- `agent/runtime/SelfCorrectionLoop.kt` — Verify-reflect-retry loop
- `agent/runtime/Verifier.kt` — Interface for test/lint/type-check verifiers
- `agent/runtime/BackpressureGate.kt` — Extend with structured verification
- `agent/runtime/SingleAgentSession.kt` — Integrate verification after tool execution

---

## Priority Matrix

| Phase | Impact | Effort | Dependencies | Recommendation |
|---|---|---|---|---|
| **3E: Self-Correction** | MEDIUM | Low | None (extend existing) | **Do first** — smallest, highest ROI |
| **3A: Durable Execution** | CRITICAL | High | None | **Do second** — foundation for everything else |
| **3B: Three-Tier Memory** | HIGH | High | 3A (checkpoint resume) | **Do third** — biggest UX improvement |
| **3C: Evaluation** | HIGH | Medium | None (additive) | **Do in parallel** with 3B |
| **3D: Agent Teams** | MEDIUM | High | 3A + 3B | **Do last** — builds on durable execution + memory |

**Recommended execution order:** 3E → 3A → 3B + 3C (parallel) → 3D

Start with 3E (self-correction) because it's low effort and immediately improves quality. Then 3A (durability) as the critical foundation. Then 3B (memory) and 3C (evaluation) in parallel since they're independent. Finally 3D (agent teams) which builds on the infrastructure from 3A and 3B.

---

## What NOT To Do (Anti-Patterns from Research)

1. **Don't build a full graph engine** — LangGraph exists because it's a platform. We're a plugin. Use lightweight workflows on top of our subagent model.
2. **Don't add conversational multi-agent** — AutoGen's approach (agents chatting with each other) is expensive and declining in adoption. Single-agent-with-tools is the production standard.
3. **Don't over-index on agent count** — Context engineering (what the agent knows) matters more than how many agents you have. Invest in memory, not more agents.
4. **Don't use local ML models** — No HuggingFace, ONNX, or any embedded ML models. Use Lucene BM25 (already in IntelliJ) + LLM-generated tags for search. Zero additional dependencies.
5. **Don't make evaluation blocking** — Scorecards should be computed post-session, not gating execution.
6. **Don't abandon the ReAct loop** — It's the universal standard. Enhance it, don't replace it.

---

## Success Metrics

| Metric | Current Baseline | Target | How to Measure |
|---|---|---|---|
| Session crash recovery | 0% (lost on crash) | 95% (resume from checkpoint) | Count resumed vs lost sessions |
| Cross-session context reuse | 0% (starts fresh) | 60% (archival memory hits) | Memory search hit rate |
| Task completion rate | Unknown | Track and improve | Session scorecards |
| Average tool calls per task | Unknown | Reduce by 20% (memory reduces re-discovery) | Metrics store |
| Self-correction success | 0% (manual only) | 70% (auto-verify catches issues) | Backpressure + verification logs |

---

## References

- Research: `docs/research/2026-03-26-enterprise-agentic-ai-tools.md` (844 lines)
- Research: `docs/research/2026-03-26-github-agentic-ai-tools.md` (628 lines)
- Research: `docs/research/2026-03-26-agentic-ai-courses.md` (605 lines)
- Existing architecture: `agent/CLAUDE.md`
- Existing research: `docs/research/` (prior research files)
