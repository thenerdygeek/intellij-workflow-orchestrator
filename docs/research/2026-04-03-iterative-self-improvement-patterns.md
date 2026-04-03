# Iterative Self-Improvement Patterns — Cross-Tool Analysis

**Date:** 2026-04-03
**Tools Analyzed:** Plandex, Sweep, AutoCodeRover, Devon, Mentat (archived)
**Purpose:** Identify patterns for iterative plan execution, build/verify cycles, and self-correction applicable to our `:agent` module

---

## Executive Summary

Five tools were analyzed for iterative self-improvement patterns. The findings reveal **four distinct architectural patterns** used across the industry:

| Pattern | Tools Using It | Description |
|---|---|---|
| **Build-Validate-Fix Loop** | Plandex, Sweep | Apply changes → validate syntax/correctness → LLM-fix → retry |
| **Generate-Test-Fix Loop** | AutoCodeRover, Sweep (GHA) | Generate patch → run tests → feed errors back → retry |
| **Race-Based Redundancy** | Plandex | Run multiple strategies in parallel → first valid result wins |
| **Event-Driven Retry** | Devon | Event log with state transitions → rate limit recovery → checkpoint rollback |

**Key finding:** No tool implements a fully closed-loop "plan → execute all steps → verify → re-plan" cycle. All operate at the **per-file or per-patch** level, not at the whole-plan level.

---

## 1. Plandex — AI Coding Engine

**Repository:** `github.com/plandex-ai/plandex` (Go, 93.4%)
**Architecture:** Client-server, queue-based build system

### 1.1 Build-Validate-Fix Loop (Primary Pattern)

**File:** `app/server/model/plan/build_validate_and_fix.go`

Plandex implements a **3-attempt validation loop** per file:

```
buildValidateLoop() {
    for attempt := 0; attempt < MaxValidationFixAttempts; attempt++ {
        1. validateSyntax(updatedContent) → []syntaxErrors
        2. ModelRequest(original, proposed, diffs) → XML response
        3. Parse response:
           - <PlandexCorrect/> → success, break
           - <PlandexReplacements> → apply fixes, continue loop
        4. On attempt 3+: escalate to stronger model
    }
}
```

**Key details:**
- `MaxBuildErrorRetries = 3` (constant in `build_state.go`)
- Exponential backoff on transient failures: `time.Duration(numRetry*numRetry) * 200ms`
- Model escalation: switches to stronger model after first failure
- Two validation layers: syntax parsing + LLM correctness check
- Cumulative problem tracking: `problems = append(problems, res.problem)`

### 1.2 Race-Based Redundancy

**File:** `app/server/model/plan/build_race.go`

When primary validation detects `<PlandexIncorrect/>`, Plandex races **three parallel strategies**:

```
buildRace() {
    Strategy 1: Validation/Replacement loop (primary, already running)
    Strategy 2: Fast Apply (pre-computed hook-based merge)
    Strategy 3: Whole File Build (complete file reconstruction)

    select {
    case result := <-resCh:  // first success wins
    case <-errCh (3 times): // all failed → abort
    case <-ctx.Done():       // timeout
    }
}
```

**Pattern:** Channel-based result collection with shared cancellation context. Detection happens during streaming — fallbacks trigger without abandoning the primary attempt.

### 1.3 Structured Edit Application with Validation

**File:** `app/server/model/plan/build_structured_edits.go`

```
buildStructuredEdits() {
    1. syntax.ApplyChanges(original, proposed, parser) → NewFile + NeedsVerifyReasons
    2. validateSyntax(result) → hasSyntaxErrors?
    3. autoApplyIsValid = !hasSyntaxErrors && !hasNeedsVerifyReasons
    4. If invalid AND fast apply not tried → callFastApply()
    5. If still invalid → buildRace() between strategies
}
```

### 1.4 Plan Execution (Subtask Level)

**File:** `app/server/model/plan/tell_exec.go`, `tell_subtasks.go`

- Plans execute as sequential subtasks with iteration counter
- `execTellPlan()` tracks `iteration` field across attempts
- `unfinishedSubtaskReasoning` carries context from incomplete work
- Subtask re-planning: `checkNewSubtasks()` dynamically adds/removes subtasks mid-execution
- Auto-fallback on current subtask removal: picks next unfinished subtask
- Provider fallback: `modelConfig.GetFallbackForModelError(numErrorRetry, didProviderFallback, modelErr)`

### 1.5 Queue-Based Build Sequencing

**File:** `app/server/model/plan/build_exec.go`, `build_finish.go`

```
queueBuild(activeBuild) {
    if IsBuildingByPath[filePath]:
        BuildQueuesByPath[filePath].enqueue(activeBuild)
    else:
        IsBuildingByPath[filePath] = true
        go execPlanBuild(activeBuild)
}

onFinishBuild() {
    1. Mark descriptions as built
    2. GitAddAndCommit() pending changes
    3. buildNextInQueue() → dequeue and execute next build for same file
    4. Check if entire plan build is complete
}
```

**No whole-plan retry exists.** If a file build fails after all attempts, problems are reported but the plan continues with remaining files.

---

## 2. Sweep — AI Code Review & PR Agent

**Repository:** `github.com/sweepai/sweep` (Python, 44.8%)
**Architecture:** GitHub webhook handlers, agent-based modification

### 2.1 Multi-Attempt Modification Loop (Primary Pattern)

**File:** `sweepai/agents/modify.py`

Sweep's main modification engine runs a **15x iteration loop per file change request (FCR)**:

```python
for i in range(len(fcrs) * 15):
    # Stage 1: Lazy attempt (compile from template)
    if llm_state["attempt_lazy_change"]:
        compiled_fcr = compile_fcr(fcrs[current_fcr_index], change_in_fcr_index)

    # Stage 2: LLM generation with model escalation
    model = MODEL if llm_state["attempt_count"] < 3 else SLOW_MODEL

    # Stage 3: Validate and parse
    function_call = validate_and_parse_function_call(function_calls_string, chat_gpt)

    # Stage 4: Duplicate detection
    if function_calls_string in llm_state["visited_set"]:
        llm_state["attempt_count"] = 3  # force model escalation

    # Stage 5: Error checking
    error_messages_dict = get_error_message_dict(fcrs, cloned_repo, modify_files_dict)

    # Stage 6: State reconstruction for next iteration
    user_message = "REVIEW THIS CAREFULLY!\n" + create_user_message(...)

    # Stage 7: After 5 failures, skip and move to next FCR
    if llm_state["attempt_count"] > 5:
        fcr.is_completed = True  # mark as done even if failed
```

**Key self-correction mechanisms:**
- **Model escalation:** Default model for first 3 attempts, stronger (SLOW_MODEL) for attempts 4+
- **Duplicate detection:** Visited set prevents identical retry loops
- **State reset:** Clears conversation history after successful changes to prevent context pollution
- **Format validation:** Post-modification formatting via `format_file()`
- **Fuzzy matching:** `find_best_match(original_code, file_contents)` with "Did you mean?" suggestions

### 2.2 Plan-Level Validation Loop

**File:** `sweepai/core/sweep_bot.py`

```python
# 3-attempt generate-validate-fix loop for file change requests
for error_resolution_count in range(3):
    if not error_message:
        break
    # Fix attempt via LLM
    continuous_llm_calls(fix_files_to_change_prompt)
    # Re-validate
    error_message, error_indices = get_error_message(file_change_requests, cloned_repo)
```

**Validation checks:**
- File existence verification
- Original code location via fuzzy matching (50% threshold)
- Parentheses balance across multiple languages
- Code block uniqueness detection
- Indentation consistency

### 2.3 CI Failure Fix Loop (Generate-Test-Fix)

**File:** `sweepai/handlers/on_failing_github_actions.py`

```python
while GITHUB_ACTIONS_ENABLED and main_passing:
    # 1. Poll for CI completion (5-second intervals, 120-minute timeout)
    should_exit, total_poll_attempts = poll_for_gha(total_poll_attempts)

    # 2. Collect failure logs from GitHub Actions / CircleCI / Docker
    failing_logs = download_logs() or get_failing_circleci_logs() or get_failing_docker_logs()

    # 3. Clean and parse error locations
    cleaned_logs = clean_gh_logs(raw_logs)  # extract ##[error] markers

    # 4. Generate fix via LLM
    file_changes = get_files_to_change_for_gha(cleaned_logs, branch_diff, gha_history)

    # 5. Commit fix to PR branch
    commit_changes(file_changes)

    # 6. Track history for context in next iteration
    gha_history.append(current_attempt)

    # 7. Limit: max 10 edit attempts
    total_edit_attempts += 1
    if total_edit_attempts >= GHA_MAX_EDIT_ATTEMPTS:  # = 10
        break
```

**This is the most complete generate-test-fix loop found across all tools.**

### 2.4 Consensus-Based Self-Review

**File:** `sweepai/core/review_utils.py`

```python
def group_vote_review_pr():
    # Run 5 independent review passes with different random seeds
    all_issues = [review_pr(seed=i) for i in range(5)]

    # Embed all issues as vectors
    embeddings = embed_issues(all_issues)

    # Cluster similar issues using DBSCAN (eps=0.375)
    clusters = dbscan_cluster(embeddings)

    # Filter: only issues appearing >= 4 out of 5 times
    confident_issues = [c for c in clusters if c.count >= LABEL_THRESHOLD]  # = 4
```

**Pattern:** Multi-pass voting with embedding-based deduplication. Not iterative improvement per se, but a self-verification mechanism through redundancy.

### 2.5 Reflection/Evaluation Agents

**File:** `sweepai/core/reflection_utils.py`

Two evaluation agents review agent work with categorical decisions:
- `evaluate_state()` → reviews research/file-identification tasks
- `evaluate_patch()` → reviews code implementation tasks
- Output: `COMPLETE` | `CONTINUE` | `REJECT`
- Includes `previous_attempt` parameter for iterative refinement
- Explicitly harsh: "Do not give any positive feedback unless the contractor literally achieved perfection"

---

## 3. AutoCodeRover — AST-Aware Bug Fixing

**Repository:** `github.com/nus-apr/auto-code-rover` (Python)
**Architecture:** Multi-agent pipeline (search → reproduce → write patch → review → select)

### 3.1 Two-Tier Retry Architecture (Primary Pattern)

**File:** `app/inference.py`

**Outer loop:** Retries the entire workflow with model cycling:

```python
def run_one_task(task, output_dir, model_names):
    model_name_cycle = cycle(model_names)
    for idx in range(config.overall_retry_limit):
        model_name = next(model_name_cycle)
        set_model(model_name)
        out_dir = Path(output_dir, f"output_{idx}")
        if _run_one_task(out_dir, api_manager, issue_statement):
            break

    # After all attempts: select best patch
    selected, details = select_patch(task, output_dir)
```

**Inner loop:** Per-attempt patch generation with feedback:

```python
def write_patch_iterative_with_review(task, output_dir, review_manager, retries=3):
    patch_gen = review_manager.generator()
    eval_summary = None

    for _ in range(retries):
        patch_handle, patch_content = patch_gen.send(eval_summary)
        eval_passed, eval_summary = validation.evaluate_patch(
            task, patch_handle, patch_content, output_dir
        )
        if eval_passed:
            patch_gen.close()
            return True
    return False
```

### 3.2 Generator-Based Validation with Feedback

**File:** `app/agents/agent_write_patch.py`

```python
def generator(self):
    while True:
        # Generate patch
        applicable, response, diff_content, thread = self._write_patch(history_handles)

        if extract_status == ExtractStatus.APPLICABLE_PATCH:
            # Yield to caller for external validation
            validation_msg = yield True, "written an applicable patch", patch_content
            # Caller sends back validation feedback
            new_prompt = f"Your patch is invalid. {validation_msg}. Please try again..."
        else:
            _ = yield False, "failed to write an applicable patch", ""
            new_prompt = "Your edit could not be applied to the program..."

        new_thread.add_user(new_prompt)
```

**This is a Python generator-based coroutine pattern** — the patch agent yields results to an external validator, receives feedback via `send()`, and incorporates it into the next attempt. Elegant separation of generation and validation.

### 3.3 Multi-Agent Patch Selection with Regression Testing

**File:** `app/inference.py` → `select_patch()`

After all retry attempts, patches are selected by priority:
1. **Reviewer-approved patches** (LLM review marked `patch-correct: yes`)
2. **Regression-passing patches** selected by agent (`agent_select.run()`)
3. **Single regression-passing patch** (no selection needed)
4. **All patches** with agent selection fallback (none passed regression)

Regression testing: `may_pass_regression_tests()` runs the actual test suite against each candidate patch and checks `no_additional_failure`.

### 3.4 Conditional Workflow Branching

**File:** `app/inference.py` → `_run_one_task()`

```python
if config.reproduce_and_review and reproduced:
    # Full pipeline: reproduce → search → patch with test feedback
    write_patch_iterative_with_review(task, output_dir, review_manager)
else:
    # Fallback: search → patch without test feedback
    write_patch_iterative(task, output_dir, review_manager)
```

**The review-enabled path is significantly more powerful** — it feeds actual test execution results back to the patch generator, creating a true generate-test-fix loop.

---

## 4. Devon — Interactive AI Agent

**Repository:** `github.com/entropy-research/Devon` (Python, 82.7%)
**Architecture:** Event-driven agent with session management

### 4.1 Event-Driven Execution Loop

**File:** `devon_agent/session.py`

```python
def run_event_loop(self, action, revert=False):
    # Git setup with retry
    while True:
        result = self.git_setup(action)
        if result == "success": break
        if result == "retry": continue
        if result == "disabled": break
        if result == "corrupted":
            self.status = "terminating"
            break

    # Main event processing loop
    while True and not (self.event_id == len(self.event_log)):
        if self.status == "terminating": break
        if self.status == "paused":
            time.sleep(2)
            continue

        event = self.event_log[self.event_id]
        events = self.step_event(event)
        self.event_log.extend(events)
        self.event_id += 1
```

### 4.2 State Machine with Event Types

**File:** `devon_agent/session.py` → `step_event()`

Event types form a cyclic state machine:
```
ModelRequest → ModelResponse → ToolRequest → ToolResponse → ModelRequest
```

### 4.3 Rate Limit Recovery

```python
case "RateLimit":
    for i in range(60):
        if self.status == "terminating": break
        time.sleep(1)
    # Re-queue the original request
    new_events.append({
        "type": "ModelRequest",
        "content": event["content"],
        "producer": self.agent.name,
        "consumer": event["producer"],
    })
```

**Pattern:** 60-second cooldown then automatic re-queue of the failed request.

### 4.4 Tool Execution Fallback

```python
try:
    env = self.environments[tool_name]
    response = env.tools[tool_name](...)
except ToolNotFoundException:
    # Fallback to default shell tool
    response = self.default_environment.default_tool(...)
except Exception as e:
    new_events.append({"type": "ToolResponse", "content": f"Error: {e.args[0]}"})
```

### 4.5 Checkpoint-Based Rollback

```python
def revert(self, event_id):
    checkpoint = self.checkpoints[event_id]
    event_log = self.event_log[:event_id + 1]
    self.config.agent_configs[0].chat_history = list(checkpoint.agent_history)
    self.start()
```

**Pattern:** Replay from checkpoint with restored agent history. Enables exploratory branching.

### 4.6 Limitations

Devon's `TaskAgent.predict()` is **single-shot per call** — it executes one thought/action cycle and returns. There is **no built-in self-correction** within the agent itself. The session's event loop provides the iteration, but error feedback goes through the standard `ToolResponse → ModelRequest` cycle without special retry logic.

---

## 5. Mentat (AbanteAI)

**Repository:** `github.com/AbanteAI/mentat`
**Status:** Returns 404 — **repository appears to be archived/deleted.**

No analysis possible.

---

## Cross-Cutting Patterns & Recommendations

### Pattern 1: Layered Retry with Model Escalation

Used by: **Plandex, Sweep, AutoCodeRover**

```
Attempt 1-2: Fast/default model
Attempt 3+:  Stronger/slower model
After max:   Report failure, continue with next task
```

**Recommendation for our agent:** Implement model escalation in `SingleAgentSession` — start with the configured model, escalate to a stronger variant after 2 failed attempts.

### Pattern 2: Generate-Validate-Fix with Feedback Loop

Used by: **AutoCodeRover** (strongest), **Sweep** (GHA handler)

```
generate_patch() → run_tests() → feed_errors_back() → retry
```

**AutoCodeRover's generator pattern is the cleanest implementation** — the patch agent yields to an external validator and receives feedback via `send()`. This separates concerns elegantly.

**Recommendation:** Our agent should support a `verify_and_fix` meta-tool action that: (1) runs specified verification command, (2) feeds output back to the agent as context, (3) loops up to N times.

### Pattern 3: Parallel Strategy Racing

Used by: **Plandex**

```
race(
    strategy_1: incremental_fix,
    strategy_2: fast_apply_hook,
    strategy_3: whole_file_rebuild,
) → first_valid_wins
```

**Recommendation:** Consider racing different fix strategies when a single approach fails repeatedly. Applicable to our build/test fix loop.

### Pattern 4: Consensus Voting for Self-Review

Used by: **Sweep**

```
run N independent reviews → embed → cluster → keep only majority findings
```

**Recommendation:** Interesting for code review but too expensive for iterative coding. May be useful for our agent's final verification step.

### Pattern 5: Checkpoint-Based Exploration

Used by: **Devon**

```
checkpoint_state() → try_approach() → if_failed: rollback_to_checkpoint()
```

**We already have this** via our LocalHistory/Git checkpointing. Devon's is simpler (event log truncation + history restore).

### What Nobody Does (Gap/Opportunity)

1. **Whole-plan re-planning on failure:** All tools operate per-file or per-patch. None re-plan the entire approach when multiple files fail.
2. **Cross-file dependency validation:** No tool validates that changes to file A are consistent with changes to file B.
3. **Progressive verification:** No tool starts with fast checks (syntax) and escalates to slow checks (tests) only on success.
4. **Budget-aware retry:** No tool adjusts retry strategy based on remaining context window or token budget.

---

## Summary Table

| Capability | Plandex | Sweep | AutoCodeRover | Devon |
|---|---|---|---|---|
| Per-file validation loop | 3 attempts | 15x iterations | 3 retries | N/A |
| Syntax validation | Tree-sitter | Parentheses + format | Diff applicability | N/A |
| LLM-based validation | XML correct/incorrect | Fuzzy match + review | Generator yield | N/A |
| Model escalation | After attempt 1 | After attempt 3 | Model cycling | N/A |
| Test execution | No | GHA polling (10 max) | Regression suite | No |
| Generate-test-fix loop | No | Yes (CI-based) | Yes (reproducer) | No |
| Parallel strategies | 3-way race | No | No | No |
| Self-review | No | 5-pass voting | Reviewer agent | No |
| Checkpoint rollback | Git commit per build | No | No | Event log truncation |
| Whole-plan re-planning | Dynamic subtask add/remove | No | Outer retry loop | No |
| Rate limit recovery | Exponential backoff | N/A | N/A | 60s cooldown + re-queue |
