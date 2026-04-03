# Iterative Self-Improvement Patterns — Cross-Tool Research Synthesis

**Date:** 2026-04-03
**Tools Analyzed:** 10 (OmX, Aider, OpenHands, SWE-agent, Goose, Plandex, Sweep, AutoCodeRover, Devon, Mentat)

## Key Patterns Found

### 1. Aider Reflection Loop (Simplest)
- `while reflected_message` loop, max 3 reflections
- 3 triggers: malformed edits, lint errors, test errors
- Each trigger sets `reflected_message` to the error output
- Commit-before-verify pattern (each attempt preserved in git)
- No external judge, no config diversity

### 2. SWE-agent RetryAgent + Chooser (Most Sophisticated)
- Outer `RetryAgent.run()` loop, max 10 attempts
- Config diversity: rotates through 3 different agent configs per attempt (filemap on/off, diff in context)
- Full environment reset (`hard_reset()`) between attempts — clean slate each time
- Two selection strategies:
  - **ScoreRetryLoop**: Separate reviewer LLM scores each attempt (multi-sample, averaged)
  - **ChooserRetryLoop**: o1 with high reasoning picks best patch from all attempts at the end
- Budget-aware exit: `total_cost > cost_limit`, `remaining_budget < min_budget_for_new_attempt`
- Sentinel tokens (`###SWE-AGENT-RETRY-WITH-OUTPUT###`) force self-review before submission
- Action-level best-of-N: tournament-style per-step selection

### 3. OmX Ralph (Wrapper Pattern)
- NOT a code-level retry loop — wraps Codex CLI via skill template + notify hook + MCP state
- Skill template re-injected each turn with `{{ITERATION}}/{{MAX}}`
- Notify hook increments iteration counter after each agent turn
- Auto-expand: when hitting max_iterations in active phase, extends by 10
- Phases: starting → executing → verifying → fixing → complete
- Agent self-declares completion via MCP `state_write`
- No cross-session context summarization — works within single Codex session

### 4. Goose Recipe Retry (Stateless)
- Recipe-driven: `task + success_checks + max_retries + on_failure`
- Shell-based success checks (e.g., `cargo test`)
- On failure: runs cleanup command, **wipes conversation** back to initial_messages
- Each retry is stateless — agent has zero memory of prior failures
- RepetitionInspector blocks identical consecutive tool calls

### 5. Sweep CI Fix Loop (History-Accumulated)
- 15x per-file modification loop with lazy-first strategy + model escalation at attempt 3
- CI failure fix loop: polls GitHub Actions, collects error logs, generates fixes, up to 10 iterations
- **History accumulated**: prior attempts and errors stay in context (unlike Goose's wipe)
- 5-pass consensus review with DBSCAN clustering (keeps issues appearing 4+ times)
- Reflection agents output COMPLETE/CONTINUE/REJECT

### 6. OpenHands Stuck Detection (Recovery, Not Retry)
- 5 stuck scenarios: identical pairs (4x), identical errors (3x), monologue (3x), A-B oscillation (6 steps), condensation loop (10+)
- Recovery: truncate memory to before loop, optionally replay last user message
- Error-as-feedback: LLM errors become ErrorObservation, agent self-corrects
- Temperature escalation on retry (0 → 1.0)
- No outer retry loop, no judge, no "run until tests pass"

### 7. AutoCodeRover Generator-Based Validation
- Two-tier: outer loop retries entire workflow with model cycling, inner loop 3 retries per patch
- Generator pattern: patch agent `yield`s, validator `send()`s feedback back
- Cleanest separation of generation and validation
- Patch selection: reviewer-approved > regression-passing > agent fallback

### 8. Plandex Build-Validate-Fix
- 3 attempts per file, tree-sitter syntax + LLM correctness check
- Model escalation to stronger model after first failure
- Race-based redundancy: 3 strategies race in parallel (incremental fix, fast-apply, whole-file rebuild)
- Dynamic subtask management mid-execution

## Pattern Comparison Matrix

| Pattern | Loop Type | Context | Judge | Reset | Budget |
|---------|-----------|---------|-------|-------|--------|
| Aider | while reflected_msg (3) | Retained | None | No | No |
| SWE-agent | Outer loop (10) | Clean slate | o1 Chooser | Full env | Yes ($) |
| OmX | Skill re-injection | Single session | Self-declare | No | Iterations only |
| Goose | Recipe retry | **Wiped** | Shell checks | Conversation | No |
| Sweep | CI loop (10) | **Accumulated** | Consensus (5x) | No | Attempts |
| OpenHands | Stuck recovery | Truncated | None | Memory | No |
| AutoCodeRover | 2-tier (outer+inner) | Test feedback | Regression suite | Model cycle | Attempts |
| Plandex | Per-file (3) | Error feedback | tree-sitter+LLM | No | Per-file |

## Key Architectural Insights

1. **Context strategy is the fundamental design choice**: wipe (Goose), retain (Aider), accumulate (Sweep), or clean-slate-with-judge (SWE-agent). Each has different failure modes.

2. **No tool implements whole-plan re-planning.** All operate per-file or per-patch. Cross-file dependency validation doesn't exist.

3. **Budget-awareness is rare.** Only SWE-agent tracks cost across attempts. Most use iteration limits only.

4. **Config diversity is unique to SWE-agent** and measurably improves results on SWE-bench.

5. **Auto-expand (OmX) prevents premature termination** — the biggest failure mode of fixed iteration limits.

6. **History accumulation (Sweep) enables learning from failures** — the agent knows what it tried and why it failed.

7. **Reviewer-as-judge (SWE-agent) provides objective quality** — self-reported completion is unreliable.
