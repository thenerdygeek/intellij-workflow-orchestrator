# Meta-Tool Schema Redesign — Action-Grouped Sub-Tools + Structured Descriptions

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking.

**Goal:** Eliminate LLM confusion from fat parameter schemas by splitting the 4 worst meta-tools into semantically grouped sub-tools and restructuring all meta-tool descriptions with per-action parameter signatures.

**Architecture:** Split `bitbucket` (26 actions, 25 params) → 3 tools, `debug` (24 actions) → 3 tools, `runtime` (9 actions, 23 params) → 2 tools, `bamboo` (18 actions) → 2 tools. Keep `jira`, `git`, `spring`, `build`, `sonar` as-is but restructure descriptions. Every tool gets function-signature-style descriptions so the LLM knows exactly which params go with which action. Final state: 15 focused meta-tools (max 14 actions, max 14 params each), all with structured descriptions.

**Tech Stack:** Kotlin, `AgentTool` interface, `ToolRegistry`, `DynamicToolSelector`, `ToolCategoryRegistry`, `SingleAgentSession`, `PromptAssembler`, JUnit 5 + MockK

---

## Before/After Summary

| Before | After | Actions | Max Params |
|--------|-------|---------|-----------|
| `bitbucket` | `bitbucket_pr` | 14 | 12 |
| | `bitbucket_review` | 6 | 10 |
| | `bitbucket_repo` | 6 | 8 |
| `debug` | `debug_breakpoints` | 8 | ~17 (but each action uses 2-5) |
| | `debug_step` | 8 | 3 |
| | `debug_inspect` | 8 | 14 (but each action uses 2-4) |
| `runtime` | `runtime_config` | 4 | 14 |
| | `runtime_exec` | 5 | 10 |
| `bamboo` | `bamboo_builds` | 10 | 10 |
| | `bamboo_plans` | 8 | 7 |
| `jira` | `jira` (unchanged) | 15 | 16 |
| `git` | `git` (unchanged) | 11 | 18 |
| `spring` | `spring` (unchanged) | 15 | 10 |
| `build` | `build` (unchanged) | 11 | 10 |
| `sonar` | `sonar` (unchanged) | 11 | 11 |

**Registered tool count:** 61 → 67 (+6 net from splits). DynamicToolSelector still limits visible tools to ~25-30 per turn.

---

## File Map

### New files (10 tool classes)
- `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/integration/BitbucketPrTool.kt`
- `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/integration/BitbucketReviewTool.kt`
- `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/integration/BitbucketRepoTool.kt`
- `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/integration/BambooBuildsTool.kt`
- `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/integration/BambooPlansTool.kt`
- `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/runtime/RuntimeConfigTool.kt`
- `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/runtime/RuntimeExecTool.kt`
- `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/debug/DebugBreakpointsTool.kt`
- `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/debug/DebugStepTool.kt`
- `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/debug/DebugInspectTool.kt`

### Deleted files (4 old meta-tools)
- `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/integration/BitbucketTool.kt`
- `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/integration/BambooTool.kt`
- `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/runtime/RuntimeTool.kt`
- `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/debug/DebugTool.kt`

### Modified files (registry, selection, filtering, prompt, docs)
- `agent/src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt` — tool registration
- `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/DynamicToolSelector.kt` — tool groups + keywords
- `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/ToolCategoryRegistry.kt` — categories
- `agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/SingleAgentSession.kt` — READ_ONLY_TOOLS, META_TOOLS_WITH_WRITE_ACTIONS, PLAN_MODE_BLOCKED_ACTIONS
- `agent/src/main/kotlin/com/workflow/orchestrator/agent/orchestrator/PromptAssembler.kt` — integration rules tool name matching
- `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/integration/JiraTool.kt` — description only
- `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/integration/SonarTool.kt` — description only
- `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/vcs/GitTool.kt` — description only
- `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/framework/SpringTool.kt` — description only
- `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/framework/BuildTool.kt` — description only

### Test files (new + modified)
- `agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/integration/BitbucketPrToolTest.kt`
- `agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/integration/BitbucketReviewToolTest.kt`
- `agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/integration/BitbucketRepoToolTest.kt`
- `agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/integration/BambooBuildToolTest.kt`
- `agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/integration/BambooPlansToolTest.kt`
- `agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/runtime/RuntimeConfigToolTest.kt`
- `agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/runtime/RuntimeExecToolTest.kt`
- `agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/debug/DebugBreakpointsToolTest.kt`
- `agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/debug/DebugStepToolTest.kt`
- `agent/src/test/kotlin/com/workflow/orchestrator/agent/tools/debug/DebugInspectToolTest.kt`
- Modify existing: `NewToolsRegistrationTest.kt`, `PlanModeToolFilterTest.kt`, `ToolCategoryRegistryTest.kt`, `DynamicToolSelectorTest.kt`

### Docs
- `agent/CLAUDE.md` — tool table, tool count
- `CLAUDE.md` (root) — tool count in architecture table

---

## Action Groupings (Definitive)

### Bitbucket Split

**`bitbucket_pr`** — PR lifecycle, status, listing (14 actions):
| Action | Required Params | Optional Params |
|--------|----------------|-----------------|
| `create_pr` | `title`, `pr_description`, `from_branch` | `to_branch`, `repo_name` |
| `get_pr_detail` | `pr_id` | `repo_name` |
| `get_pr_commits` | `pr_id` | `repo_name` |
| `get_pr_activities` | `pr_id` | `repo_name` |
| `get_pr_changes` | `pr_id` | `repo_name` |
| `get_pr_diff` | `pr_id` | `repo_name` |
| `check_merge_status` | `pr_id` | `repo_name` |
| `approve_pr` | `pr_id` | `repo_name` |
| `merge_pr` | `pr_id` | `strategy`, `delete_source_branch`, `commit_message`, `repo_name` |
| `decline_pr` | `pr_id` | `repo_name` |
| `update_pr_title` | `pr_id`, `new_title` | `repo_name` |
| `update_pr_description` | `pr_id`, `pr_description` | `repo_name` |
| `get_my_prs` | – | `state`, `repo_name` |
| `get_reviewing_prs` | – | `state`, `repo_name` |

Params: `action`, `pr_id`, `title`, `pr_description`, `from_branch`, `to_branch`, `new_title`, `state`, `strategy`, `delete_source_branch`, `commit_message`, `repo_name`, `description` → **13 params**

**`bitbucket_review`** — Comments, reviewers, inline feedback (6 actions):
| Action | Required Params | Optional Params |
|--------|----------------|-----------------|
| `add_pr_comment` | `pr_id`, `text` | `repo_name` |
| `add_inline_comment` | `pr_id`, `file_path`, `line`, `line_type`, `text` | `repo_name` |
| `reply_to_comment` | `pr_id`, `parent_comment_id`, `text` | `repo_name` |
| `add_reviewer` | `pr_id`, `username` | `repo_name` |
| `remove_reviewer` | `pr_id`, `username` | `repo_name` |
| `set_reviewer_status` | `pr_id`, `username`, `status` | `repo_name` |

Params: `action`, `pr_id`, `text`, `file_path`, `line`, `line_type`, `parent_comment_id`, `username`, `status`, `repo_name`, `description` → **11 params**

**`bitbucket_repo`** — Branches, files, users, build statuses (6 actions):
| Action | Required Params | Optional Params |
|--------|----------------|-----------------|
| `get_branches` | – | `filter`, `repo_name` |
| `create_branch` | `name`, `start_point` | `repo_name` |
| `search_users` | `filter` | `repo_name` |
| `get_file_content` | `file_path`, `at_ref` | `repo_name` |
| `get_build_statuses` | `commit_id` | `repo_name` |
| `list_repos` | – | – |

Params: `action`, `filter`, `name`, `start_point`, `file_path`, `at_ref`, `commit_id`, `repo_name`, `description` → **9 params**

---

### Debug Split

**`debug_breakpoints`** — Breakpoint CRUD + session start/attach (8 actions):
| Action | Required Params | Optional Params |
|--------|----------------|-----------------|
| `add_breakpoint` | `file`, `line` | `condition`, `log_expression`, `temporary` |
| `method_breakpoint` | `class_name`, `method_name` | `watch_entry`, `watch_exit` |
| `exception_breakpoint` | `exception_class` | `caught`, `uncaught` |
| `field_watchpoint` | `class_name`, `field_name` | `watch_read`, `watch_write` |
| `remove_breakpoint` | `file`, `line` | – |
| `list_breakpoints` | – | `file` |
| `start_session` | `config_name` | `wait_for_pause` |
| `attach_to_process` | `port` | `host`, `name` |

Params: `action`, `file`, `line`, `condition`, `log_expression`, `temporary`, `class_name`, `method_name`, `watch_entry`, `watch_exit`, `exception_class`, `caught`, `uncaught`, `field_name`, `watch_read`, `watch_write`, `config_name`, `wait_for_pause`, `port`, `host`, `name` → **21 params** (high count but each action uses only 2-5; structured description makes this workable)

**`debug_step`** — Session navigation + state (8 actions):
| Action | Required Params | Optional Params |
|--------|----------------|-----------------|
| `get_state` | – | `session_id` |
| `step_over` | – | `session_id` |
| `step_into` | – | `session_id` |
| `step_out` | – | `session_id` |
| `resume` | – | `session_id` |
| `pause` | – | `session_id` |
| `run_to_cursor` | `file`, `line` | `session_id` |
| `stop` | – | `session_id` |

Params: `action`, `session_id`, `file`, `line` → **4 params** (extremely clean)

**`debug_inspect`** — Evaluation, variables, threads, advanced ops (8 actions):
| Action | Required Params | Optional Params |
|--------|----------------|-----------------|
| `evaluate` | `expression` | `session_id` |
| `get_stack_frames` | – | `session_id`, `thread_name`, `max_frames` |
| `get_variables` | – | `session_id`, `variable_name`, `max_depth` |
| `thread_dump` | – | `session_id`, `max_frames`, `include_stacks`, `include_daemon` |
| `memory_view` | `class_name` | `session_id`, `max_instances` |
| `hotswap` | – | `session_id`, `compile_first` |
| `force_return` | – | `session_id`, `return_value`, `return_type` |
| `drop_frame` | – | `session_id`, `frame_index` |

Params: `action`, `session_id`, `expression`, `variable_name`, `max_depth`, `thread_name`, `max_frames`, `include_stacks`, `include_daemon`, `class_name`, `max_instances`, `compile_first`, `return_value`, `return_type`, `frame_index` → **15 params**

---

### Runtime Split

**`runtime_config`** — Run configuration CRUD (4 actions):
| Action | Required Params | Optional Params |
|--------|----------------|-----------------|
| `get_run_configurations` | – | `type_filter` |
| `create_run_config` | `name`, `type` | `main_class`, `test_class`, `test_method`, `module`, `env_vars`, `vm_options`, `program_args`, `working_dir`, `active_profiles`, `port` |
| `modify_run_config` | `name` | `env_vars`, `vm_options`, `program_args`, `working_dir`, `active_profiles` |
| `delete_run_config` | `name` | – |

Params: `action`, `type_filter`, `name`, `type`, `main_class`, `test_class`, `test_method`, `module`, `env_vars`, `vm_options`, `program_args`, `working_dir`, `active_profiles`, `port`, `description` → **15 params**

**`runtime_exec`** — Process management, testing, compilation (5 actions):
| Action | Required Params | Optional Params |
|--------|----------------|-----------------|
| `get_running_processes` | – | – |
| `get_run_output` | `config_name` | `last_n_lines`, `filter` |
| `get_test_results` | `config_name` | `status_filter` |
| `run_tests` | – | `class_name`, `method`, `timeout`, `use_native_runner` |
| `compile_module` | – | `module` |

Params: `action`, `config_name`, `last_n_lines`, `filter`, `status_filter`, `class_name`, `method`, `timeout`, `use_native_runner`, `module`, `description` → **11 params**

---

### Bamboo Split

**`bamboo_builds`** — Build lifecycle + monitoring (10 actions):
| Action | Required Params | Optional Params |
|--------|----------------|-----------------|
| `build_status` | `plan_key` | `branch`, `repo_name` |
| `get_build` | `build_key` | – |
| `trigger_build` | `plan_key` | `variables` |
| `get_build_log` | `build_key` | – |
| `get_test_results` | `build_key` | – |
| `stop_build` | `result_key` | – |
| `cancel_build` | `result_key` | – |
| `get_artifacts` | `result_key` | – |
| `recent_builds` | `plan_key` | `branch`, `repo_name`, `max_results` |
| `get_running_builds` | `plan_key` | `repo_name` |

Params: `action`, `plan_key`, `build_key`, `result_key`, `branch`, `repo_name`, `variables`, `max_results`, `description` → **9 params**

**`bamboo_plans`** — Plan management + configuration (8 actions):
| Action | Required Params | Optional Params |
|--------|----------------|-----------------|
| `get_plans` | – | – |
| `get_project_plans` | `project_key` | – |
| `search_plans` | `query` | – |
| `get_plan_branches` | `plan_key` | `repo_name` |
| `get_build_variables` | `result_key` | – |
| `get_plan_variables` | `plan_key` | – |
| `rerun_failed_jobs` | `plan_key`, `build_number` | – |
| `trigger_stage` | `plan_key` | `stage`, `variables` |

Params: `action`, `plan_key`, `project_key`, `query`, `repo_name`, `result_key`, `build_number`, `stage`, `variables`, `description` → **10 params**

---

## Structured Description Format (All Tools)

Every meta-tool description follows this format:

```
[One-line purpose]

Actions and their parameters:
- action_name(required_param, optional_param?) → What it does
- action_name(param1, param2, opt?) → What it does
...

Common optional: param_name applies to all actions for [purpose].
```

Parameters marked with `?` are optional. This function-signature style gives the LLM an explicit mapping of which params each action needs.

---

## Tasks

### Task 1: Split BitbucketTool into 3 focused tools

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/integration/BitbucketPrTool.kt`
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/integration/BitbucketReviewTool.kt`
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/integration/BitbucketRepoTool.kt`
- Delete: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/integration/BitbucketTool.kt`

Read `BitbucketTool.kt` fully. Create 3 new tool classes following the same patterns (ServiceLookup, ToolValidation, missingParam, parsePrId helpers). Each tool gets:

1. Its own subset of actions from the grouping table above
2. Only the parameters relevant to its actions (no fat union)
3. Structured description with per-action param signatures
4. Same `allowedWorkers` as original: `TOOLER, ORCHESTRATOR, CODER, REVIEWER, ANALYZER`
5. Copy over helper methods (parsePrId, invalidPrId, missingParam) to each tool that needs them — use a private companion or put shared helpers in a `BitbucketToolUtils.kt` if 2+ tools need the same helper

- [ ] **Step 1.1: Read BitbucketTool.kt and understand all 26 action dispatch branches**
- [ ] **Step 1.2: Create BitbucketPrTool.kt**

```kotlin
class BitbucketPrTool : AgentTool {
    override val name = "bitbucket_pr"
    override val description = """
Pull request lifecycle — create, inspect, approve, merge, decline, update PRs.

Actions and their parameters:
- create_pr(title, pr_description, from_branch, to_branch?) → Create pull request
- get_pr_detail(pr_id) → Full PR info with reviewers and status
- get_pr_commits(pr_id) → List commits in PR
- get_pr_activities(pr_id) → PR activity feed
- get_pr_changes(pr_id) → List changed files
- get_pr_diff(pr_id) → Get PR diff
- check_merge_status(pr_id) → Check if PR is mergeable
- approve_pr(pr_id) → Approve a pull request
- merge_pr(pr_id, strategy?, delete_source_branch?, commit_message?) → Merge PR
- decline_pr(pr_id) → Decline a pull request
- update_pr_title(pr_id, new_title) → Change PR title
- update_pr_description(pr_id, pr_description) → Update PR body
- get_my_prs(state?) → My pull requests (state: OPEN|MERGED|DECLINED)
- get_reviewing_prs(state?) → PRs I'm reviewing

Common optional: repo_name for multi-repo projects. description for approval dialog on write actions.
""".trimIndent()
    // ... parameters with only the 13 params listed above
    // ... execute() with when(action) dispatch for these 14 actions
}
```

- [ ] **Step 1.3: Create BitbucketReviewTool.kt**

```kotlin
class BitbucketReviewTool : AgentTool {
    override val name = "bitbucket_review"
    override val description = """
PR comments, inline feedback, and reviewer management.

Actions and their parameters:
- add_pr_comment(pr_id, text) → Add general comment to PR
- add_inline_comment(pr_id, file_path, line, line_type, text) → Comment on specific line (line_type: ADDED|REMOVED|CONTEXT)
- reply_to_comment(pr_id, parent_comment_id, text) → Reply to existing comment
- add_reviewer(pr_id, username) → Add reviewer to PR
- remove_reviewer(pr_id, username) → Remove reviewer from PR
- set_reviewer_status(pr_id, username, status) → Set status (APPROVED|NEEDS_WORK|UNAPPROVED)

Common optional: repo_name for multi-repo projects. description for approval dialog.
""".trimIndent()
    // ... parameters with only the 11 params listed above
    // ... execute() with when(action) dispatch for these 6 actions
}
```

- [ ] **Step 1.4: Create BitbucketRepoTool.kt**

```kotlin
class BitbucketRepoTool : AgentTool {
    override val name = "bitbucket_repo"
    override val description = """
Repository operations — branches, files, users, build statuses.

Actions and their parameters:
- get_branches(filter?) → List branches
- create_branch(name, start_point) → Create new branch
- search_users(filter) → Find users by name
- get_file_content(file_path, at_ref) → Read file at git ref
- get_build_statuses(commit_id) → Build status for commit
- list_repos() → List all repositories

Common optional: repo_name for multi-repo projects. description for approval dialog on create_branch.
""".trimIndent()
    // ... parameters with only the 9 params listed above
    // ... execute() with when(action) dispatch for these 6 actions
}
```

- [ ] **Step 1.5: Delete BitbucketTool.kt**
- [ ] **Step 1.6: Verify compilation**

Run: `./gradlew :agent:compileKotlin`

(This will fail until registrations are updated in Task 5, but catches syntax errors.)

---

### Task 2: Split DebugTool into 3 focused tools

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/debug/DebugBreakpointsTool.kt`
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/debug/DebugStepTool.kt`
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/debug/DebugInspectTool.kt`
- Delete: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/debug/DebugTool.kt`

All 3 tools take `AgentDebugController` as constructor parameter (same as original DebugTool). Read the original to understand how the controller is used in each action.

- [ ] **Step 2.1: Read DebugTool.kt fully (~1893 lines) and map each action's dependencies**
- [ ] **Step 2.2: Create DebugBreakpointsTool.kt**

```kotlin
class DebugBreakpointsTool(private val controller: AgentDebugController) : AgentTool {
    override val name = "debug_breakpoints"
    override val description = """
Breakpoint management and debug session setup.

Actions and their parameters:
- add_breakpoint(file, line, condition?, log_expression?, temporary?) → Set line breakpoint
- method_breakpoint(class_name, method_name, watch_entry?, watch_exit?) → Break on method entry/exit
- exception_breakpoint(exception_class, caught?, uncaught?) → Break on exception
- field_watchpoint(class_name, field_name, watch_read?, watch_write?) → Watch field access
- remove_breakpoint(file, line) → Remove line breakpoint
- list_breakpoints(file?) → List all breakpoints or filter by file
- start_session(config_name, wait_for_pause?) → Launch debug session
- attach_to_process(port, host?, name?) → Remote debug attach
""".trimIndent()
    override val allowedWorkers = setOf(WorkerType.CODER, WorkerType.REVIEWER, WorkerType.ANALYZER)
    // ... parameters and execute() — move the 8 relevant executeXxx() methods from DebugTool
}
```

- [ ] **Step 2.3: Create DebugStepTool.kt**

```kotlin
class DebugStepTool(private val controller: AgentDebugController) : AgentTool {
    override val name = "debug_step"
    override val description = """
Debug session navigation — stepping, state, and lifecycle control.

Actions and their parameters:
- get_state(session_id?) → Current breakpoint, thread, and line info
- step_over(session_id?) → Step over current line
- step_into(session_id?) → Step into method call
- step_out(session_id?) → Step out of current method
- resume(session_id?) → Resume execution
- pause(session_id?) → Pause execution
- run_to_cursor(file, line, session_id?) → Run to specific line
- stop(session_id?) → Stop debug session

All actions accept optional session_id (defaults to active session).
""".trimIndent()
    override val allowedWorkers = setOf(WorkerType.CODER, WorkerType.REVIEWER, WorkerType.ANALYZER)
    // ... only 4 params: action, session_id, file, line
}
```

- [ ] **Step 2.4: Create DebugInspectTool.kt**

```kotlin
class DebugInspectTool(private val controller: AgentDebugController) : AgentTool {
    override val name = "debug_inspect"
    override val description = """
Debug inspection — evaluate expressions, inspect variables, thread state, and advanced operations.

Actions and their parameters:
- evaluate(expression, session_id?) → Evaluate Java/Kotlin expression in current frame
- get_stack_frames(session_id?, thread_name?, max_frames?) → Stack trace
- get_variables(session_id?, variable_name?, max_depth?) → Inspect variables (max_depth: 1-4)
- thread_dump(session_id?, max_frames?, include_stacks?, include_daemon?) → All threads
- memory_view(class_name, session_id?, max_instances?) → Heap introspection
- hotswap(session_id?, compile_first?) → Hot reload changed classes
- force_return(session_id?, return_value?, return_type?) → Force method return
- drop_frame(session_id?, frame_index?) → Pop stack frame
""".trimIndent()
    override val allowedWorkers = setOf(WorkerType.CODER, WorkerType.REVIEWER, WorkerType.ANALYZER)
    // ... 15 params but each action clearly documented
}
```

- [ ] **Step 2.5: Delete DebugTool.kt**
- [ ] **Step 2.6: Verify compilation**

Run: `./gradlew :agent:compileKotlin`

---

### Task 3: Split RuntimeTool into 2 focused tools

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/runtime/RuntimeConfigTool.kt`
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/runtime/RuntimeExecTool.kt`
- Delete: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/runtime/RuntimeTool.kt`

RuntimeTool is 1811 lines with heavy reflection-based configuration handling. The config-related helper methods (applyApplicationConfig, applyReflectionConfig, applyJUnitConfig, etc.) go into RuntimeConfigTool. The execution helpers go into RuntimeExecTool.

- [ ] **Step 3.1: Read RuntimeTool.kt fully (~1811 lines) and map helper method dependencies**
- [ ] **Step 3.2: Create RuntimeConfigTool.kt**

```kotlin
class RuntimeConfigTool : AgentTool {
    override val name = "runtime_config"
    override val description = """
IntelliJ run configuration management — create, modify, delete, and list run/debug configurations.

Actions and their parameters:
- get_run_configurations(type_filter?) → List configs (type_filter: application|spring_boot|junit|gradle|remote_debug)
- create_run_config(name, type, main_class?, test_class?, test_method?, module?, env_vars?, vm_options?, program_args?, working_dir?, active_profiles?, port?) → Create config (type: application|spring_boot|junit|gradle|remote_debug; main_class required for application/spring_boot; test_class required for junit)
- modify_run_config(name, env_vars?, vm_options?, program_args?, working_dir?, active_profiles?) → Modify existing config (at least one change required)
- delete_run_config(name) → Delete config (only [Agent]-prefixed configs)

description optional: for approval dialog on create/modify/delete.
""".trimIndent()
    override val allowedWorkers = setOf(WorkerType.CODER, WorkerType.REVIEWER, WorkerType.ANALYZER, WorkerType.ORCHESTRATOR, WorkerType.TOOLER)
    // ... 15 params, all config-related
    // ... move applyXxxConfig() and modifyApplyXxx() helper methods here
}
```

- [ ] **Step 3.3: Create RuntimeExecTool.kt**

```kotlin
class RuntimeExecTool : AgentTool {
    override val name = "runtime_exec"
    override val description = """
Process execution — run tests, compile modules, inspect output and test results.

Actions and their parameters:
- get_running_processes() → List running IDE processes
- get_run_output(config_name, last_n_lines?, filter?) → Read process output (last_n_lines default 200 max 1000; filter is regex)
- get_test_results(config_name, status_filter?) → Test results (status_filter: FAILED|ERROR|PASSED|SKIPPED)
- run_tests(class_name?, method?, timeout?, use_native_runner?) → Run tests (timeout default 300s max 900s)
- compile_module(module?) → Compile module (omit for entire project)

description optional: for approval dialog on run_tests and compile_module.
""".trimIndent()
    override val allowedWorkers = setOf(WorkerType.CODER, WorkerType.REVIEWER, WorkerType.ANALYZER, WorkerType.ORCHESTRATOR, WorkerType.TOOLER)
    // ... 11 params, all execution-related
}
```

- [ ] **Step 3.4: Delete RuntimeTool.kt**
- [ ] **Step 3.5: Verify compilation**

Run: `./gradlew :agent:compileKotlin`

---

### Task 4: Split BambooTool into 2 focused tools

**Files:**
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/integration/BambooBuildsTool.kt`
- Create: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/integration/BambooPlansTool.kt`
- Delete: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/integration/BambooTool.kt`

- [ ] **Step 4.1: Read BambooTool.kt fully (~279 lines)**
- [ ] **Step 4.2: Create BambooBuildsTool.kt**

```kotlin
class BambooBuildsTool : AgentTool {
    override val name = "bamboo_builds"
    override val description = """
Bamboo build lifecycle — trigger, monitor, stop, inspect builds and test results.

Actions and their parameters:
- build_status(plan_key, branch?, repo_name?) → Latest build status for plan
- get_build(build_key) → Detailed build info
- trigger_build(plan_key, variables?) → Trigger new build (variables: JSON {"key":"value"})
- get_build_log(build_key) → Build log output
- get_test_results(build_key) → Test results for build
- stop_build(result_key) → Stop running build
- cancel_build(result_key) → Cancel queued build
- get_artifacts(result_key) → List build artifacts
- recent_builds(plan_key, branch?, repo_name?, max_results?) → Recent builds (default 10)
- get_running_builds(plan_key, repo_name?) → Currently running builds

description optional: for approval dialog on trigger/stop/cancel.
""".trimIndent()
    override val allowedWorkers = setOf(WorkerType.TOOLER, WorkerType.ORCHESTRATOR)
    // ... 9 params
}
```

- [ ] **Step 4.3: Create BambooPlansTool.kt**

```kotlin
class BambooPlansTool : AgentTool {
    override val name = "bamboo_plans"
    override val description = """
Bamboo plan management — browse plans, variables, branches, rerun, and stage triggers.

Actions and their parameters:
- get_plans() → List all build plans
- get_project_plans(project_key) → Plans in a project
- search_plans(query) → Search plans by name
- get_plan_branches(plan_key, repo_name?) → Plan branch list
- get_build_variables(result_key) → Variables for a specific build
- get_plan_variables(plan_key) → Default plan variables
- rerun_failed_jobs(plan_key, build_number) → Rerun failed jobs in build
- trigger_stage(plan_key, stage?, variables?) → Trigger specific stage

description optional: for approval dialog on rerun/trigger_stage.
""".trimIndent()
    override val allowedWorkers = setOf(WorkerType.TOOLER, WorkerType.ORCHESTRATOR)
    // ... 10 params
}
```

- [ ] **Step 4.4: Delete BambooTool.kt**
- [ ] **Step 4.5: Verify compilation**

Run: `./gradlew :agent:compileKotlin`

---

### Task 5: Restructure descriptions for unsplit meta-tools

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/integration/JiraTool.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/integration/SonarTool.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/vcs/GitTool.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/framework/SpringTool.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/framework/BuildTool.kt`

For each: replace the flat `description` string with the structured per-action-param-signature format. Do NOT change `parameters`, `execute()`, or anything else.

- [ ] **Step 5.1: Restructure JiraTool description**

```kotlin
override val description = """
Jira ticket management — issues, sprints, boards, transitions, comments, time logging.

Actions and their parameters:
- get_ticket(key) → Full ticket details
- get_transitions(key) → Available status transitions
- transition(key, transition_id, comment?) → Move ticket to new status
- comment(key, body) → Add comment to ticket
- get_comments(key) → List comments
- log_work(key, time_spent, comment?) → Log time (format: '2h', '30m', '1h 30m')
- get_worklogs(key) → List work logs (also accepts issue_key)
- get_sprints(board_id) → List sprints for board
- get_boards(type?, name_filter?) → List boards (type: scrum|kanban)
- get_sprint_issues(sprint_id) → Issues in sprint
- get_board_issues(board_id) → Issues on board
- search_issues(text, max_results?) → JQL/text search (default 20 results)
- get_linked_prs(issue_id) → PRs linked to issue
- get_dev_branches(issue_id) → Dev branches for issue
- start_work(issue_key, branch_name, source_branch) → Create branch and start work (also accepts key)
""".trimIndent()
```

- [ ] **Step 5.2: Restructure SonarTool description**

```kotlin
override val description = """
SonarQube code quality — issues, coverage, quality gates, analysis, security hotspots.

Actions and their parameters:
- issues(project_key, file?, branch?) → Code issues (optionally filter by file path)
- quality_gate(project_key, branch?) → Quality gate status
- coverage(project_key, branch?) → Code coverage metrics
- search_projects(query) → Search SonarQube projects
- analysis_tasks(project_key) → Recent analysis task status
- branches(project_key) → Analyzed branches
- project_measures(project_key, branch?) → All project metrics
- source_lines(component_key, from?, to?, branch?) → Source code with metrics (from/to are line numbers)
- issues_paged(project_key, page?, page_size?, branch?) → Paginated issues (default page 1, 100/page, max 500)
- security_hotspots(project_key, branch?) → Security hotspots
- duplications(component_key, branch?) → Code duplications

Common optional: repo_name for multi-repo projects.
""".trimIndent()
```

- [ ] **Step 5.3: Restructure GitTool description**

```kotlin
override val description = """
Git operations — status, blame, diff, log, branches, file history, shelve.

Actions and their parameters:
- status() → Working tree status
- blame(path, start_line?, end_line?) → Line-by-line blame
- diff(path?, ref?, staged?) → Show diff (staged=true for staged changes)
- log(path?, ref?, max_count?, oneline?) → Commit log (default 20, max 50)
- branches(show_remote?, show_tags?) → List branches
- show_file(path, ref) → File content at git ref (local refs only)
- show_commit(commit, include_diff?) → Commit details (SHA, HEAD, HEAD~N, or local branch)
- stash_list() → List stashes
- merge_base(ref1, ref2) → Common ancestor
- file_history(path, max_count?) → File commit history (default 15, max 30)
- shelve(shelve_action, name?, comment?, shelf_index?) → Changelist shelving (shelve_action: list|list_shelves|create|shelve|unshelve)
""".trimIndent()
```

- [ ] **Step 5.4: Restructure SpringTool description**

```kotlin
override val description = """
Spring framework intelligence — beans, endpoints, configuration, JPA, security, actuator.

Actions and their parameters:
- context(filter?) → Spring bean context
- endpoints(filter?, include_params?) → REST endpoint mappings
- bean_graph(bean_name) → Bean dependency graph
- config(property) → Configuration property value
- version_info(module) → Framework version info
- profiles() → Active Spring profiles
- repositories(filter?) → Spring Data repositories
- security_config() → Security configuration
- scheduled_tasks() → @Scheduled methods
- event_listeners() → @EventListener methods
- boot_endpoints(class_name?) → Boot endpoint mappings
- boot_autoconfig(filter?, project_only?) → Auto-configuration classes (project_only default true)
- boot_config_properties(class_name?, prefix?) → @ConfigurationProperties bindings
- boot_actuator() → Actuator endpoints
- jpa_entities(entity?) → JPA entity analysis
""".trimIndent()
```

- [ ] **Step 5.5: Restructure BuildTool description**

```kotlin
override val description = """
Build system intelligence — Maven and Gradle dependencies, plugins, properties, modules.

Actions and their parameters:
- maven_dependencies(module?, scope?, search?) → Maven dependencies (scope: compile|test|runtime|provided)
- maven_properties(module?, search?) → POM properties
- maven_plugins(module?) → Build plugins
- maven_profiles(module?) → Build profiles
- maven_dependency_tree(module?, artifact?) → Transitive dependency tree (artifact to filter paths)
- maven_effective_pom(module?, plugin?) → Effective POM (plugin to filter by artifactId)
- gradle_dependencies(module?, configuration?, search?) → Gradle deps (configuration: implementation|api|testImplementation|...)
- gradle_tasks(module?, search?) → Gradle tasks
- gradle_properties(module?, search?) → Gradle properties
- project_modules() → List all IntelliJ modules
- module_dependency_graph(module?, transitive?, include_libraries?, detect_cycles?) → Module dependency graph
""".trimIndent()
```

- [ ] **Step 5.6: Verify compilation**

Run: `./gradlew :agent:compileKotlin`

---

### Task 6: Update tool registration, selection, categories, and filtering

**Files:**
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/AgentService.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/DynamicToolSelector.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/ToolCategoryRegistry.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/SingleAgentSession.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/orchestrator/PromptAssembler.kt`

- [ ] **Step 6.1: Update AgentService.kt — replace old registrations with new tools**

Replace:
```kotlin
register(BitbucketTool())
register(BambooTool())
register(RuntimeTool())
register(DebugTool())  // note: check constructor params
```

With:
```kotlin
// Bitbucket (3 focused tools replacing 1)
register(BitbucketPrTool())
register(BitbucketReviewTool())
register(BitbucketRepoTool())

// Bamboo (2 focused tools replacing 1)
register(BambooBuildsTool())
register(BambooPlansTool())

// Runtime (2 focused tools replacing 1)
register(RuntimeConfigTool())
register(RuntimeExecTool())

// Debug (3 focused tools replacing 1) — check if controller param needed
val debugController = ... // same source as original DebugTool()
register(DebugBreakpointsTool(debugController))
register(DebugStepTool(debugController))
register(DebugInspectTool(debugController))
```

Read AgentService.kt to find how `DebugTool()` gets its controller. Replicate for all 3 debug tools.

- [ ] **Step 6.2: Update DynamicToolSelector.kt — update tool groups**

Find the semantic tool groups and update tool name sets:

```kotlin
// Old:
ToolGroup("bamboo", keywords = setOf("bamboo", "build", "ci", ...), tools = setOf("bamboo", ...))
ToolGroup("bitbucket", keywords = setOf("bitbucket", "pr", ...), tools = setOf("bitbucket"))
ToolGroup("debug", keywords = setOf("debug", "breakpoint", ...), tools = setOf("debug", ...))

// New:
ToolGroup("bamboo", keywords = setOf("bamboo", "build", "ci", ...), tools = setOf("bamboo_builds", "bamboo_plans", ...))
ToolGroup("bitbucket", keywords = setOf("bitbucket", "pr", ...), tools = setOf("bitbucket_pr", "bitbucket_review", "bitbucket_repo"))
ToolGroup("debug", keywords = setOf("debug", "breakpoint", ...), tools = setOf("debug_breakpoints", "debug_step", "debug_inspect", ...))
ToolGroup("runtime", keywords = ..., tools = setOf("runtime_config", "runtime_exec"))
```

All sub-tools in a group are activated together — when the keyword triggers, all sub-tools in that domain become available.

- [ ] **Step 6.3: Update ToolCategoryRegistry.kt — update categories**

```kotlin
// Old:
ToolCategory(id = "bitbucket", tools = listOf("bitbucket"))
ToolCategory(id = "bamboo", tools = listOf("bamboo"))
ToolCategory(id = "runtime_debug", tools = listOf("runtime", "debug"))

// New:
ToolCategory(id = "bitbucket", tools = listOf("bitbucket_pr", "bitbucket_review", "bitbucket_repo"))
ToolCategory(id = "bamboo", tools = listOf("bamboo_builds", "bamboo_plans"))
ToolCategory(id = "runtime_debug", tools = listOf("runtime_config", "runtime_exec", "debug_breakpoints", "debug_step", "debug_inspect"))
```

- [ ] **Step 6.4: Update SingleAgentSession.kt — update constants**

Update `META_TOOLS_WITH_WRITE_ACTIONS`:
```kotlin
// Old:
private val META_TOOLS_WITH_WRITE_ACTIONS = setOf("jira", "bamboo", "bitbucket", "git")

// New:
private val META_TOOLS_WITH_WRITE_ACTIONS = setOf(
    "jira",
    "bamboo_builds", "bamboo_plans",
    "bitbucket_pr", "bitbucket_review", "bitbucket_repo",
    "git"
)
```

Note: `runtime_config`, `runtime_exec`, `debug_*` are NOT in this set — they don't modify source code or external services in plan-mode-relevant ways.

`PLAN_MODE_BLOCKED_ACTIONS` — the action names themselves don't change, only which meta-tool they belong to. The existing action-level check works because it parses the `action` param from any tool in `META_TOOLS_WITH_WRITE_ACTIONS`. Verify the blocked action names match the new tools' action names exactly.

`READ_ONLY_TOOLS` — no changes needed (none of the new split tools are fully read-only).

- [ ] **Step 6.5: Update PromptAssembler.kt — update tool name matching**

```kotlin
// Old:
val hasBitbucket = includeAll || "bitbucket" in activeToolNames!!
val hasBamboo = includeAll || "bamboo" in activeToolNames!!
val hasDebug = includeAll || "debug" in activeToolNames!!

// New:
val hasBitbucket = includeAll || activeToolNames!!.any { it.startsWith("bitbucket_") }
val hasBamboo = includeAll || activeToolNames!!.any { it.startsWith("bamboo_") }
val hasDebug = includeAll || activeToolNames!!.any { it.startsWith("debug_") }
```

This way `buildIntegrationRules()` includes Bitbucket rules when any `bitbucket_*` tool is active, etc.

- [ ] **Step 6.6: Verify compilation**

Run: `./gradlew :agent:compileKotlin`

- [ ] **Step 6.7: Verify all tests still compile**

Run: `./gradlew :agent:compileTestKotlin`

---

### Task 7: Write tests for new tools

**Files:**
- Create: 10 new test files (listed in File Map above)
- Modify: existing test files that reference old tool names

Each test file follows the established pattern from existing tests:
1. **Metadata tests**: name, description contains action names, parameters.required == ["action"], allowedWorkers correct, toToolDefinition() valid
2. **Parameter validation tests**: missing action → error, unknown action → error, missing required param per action → error
3. **Schema validation**: enum values present for action param

- [ ] **Step 7.1: Create BitbucketPrToolTest.kt, BitbucketReviewToolTest.kt, BitbucketRepoToolTest.kt**

Follow `IntegrationToolMetadataTest.kt` pattern. Each test class verifies:
- `tool.name` matches expected
- `tool.parameters.properties["action"]?.enumValues` contains exactly the right actions
- `tool.parameters.required == listOf("action")`
- `tool.allowedWorkers` matches
- `tool.toToolDefinition()` produces valid OpenAI schema
- Missing required params per action return isError=true

- [ ] **Step 7.2: Create BambooBuildToolTest.kt, BambooPlansToolTest.kt**

Same pattern. Verify action enum for each tool, param validation.

- [ ] **Step 7.3: Create RuntimeConfigToolTest.kt, RuntimeExecToolTest.kt**

Same pattern.

- [ ] **Step 7.4: Create DebugBreakpointsToolTest.kt, DebugStepToolTest.kt, DebugInspectToolTest.kt**

Follow `StartDebugSessionToolTest.kt` pattern — mock `AgentDebugController` via MockK.

- [ ] **Step 7.5: Update existing tests that reference old tool names**

Search test files for references to `"bitbucket"`, `"bamboo"`, `"runtime"`, `"debug"` tool names and update:
- `NewToolsRegistrationTest.kt` — tool risk classification for new names
- `PlanModeToolFilterTest.kt` — plan mode blocked tools/actions
- `ToolCategoryRegistryTest.kt` — category lookups
- `DynamicToolSelectorTest.kt` — keyword matching
- `IntegrationToolMetadataTest.kt` — if it references old tools

- [ ] **Step 7.6: Run all agent tests**

Run: `./gradlew :agent:test`
Expected: All tests pass.

---

### Task 8: Update documentation

**Files:**
- Modify: `agent/CLAUDE.md`
- Modify: `CLAUDE.md` (root)

- [ ] **Step 8.1: Update agent/CLAUDE.md**

Update the Tools table:
```
| CI/CD — Bamboo | **bamboo_builds** (10 actions: ...), **bamboo_plans** (8 actions: ...) |
| Pull Requests — Bitbucket | **bitbucket_pr** (14 actions: ...), **bitbucket_review** (6 actions: ...), **bitbucket_repo** (6 actions: ...) |
| Runtime & Debug | **runtime_config** (4 actions: ...), **runtime_exec** (5 actions: ...), **debug_breakpoints** (8 actions: ...), **debug_step** (8 actions: ...), **debug_inspect** (8 actions: ...) |
```

Update the tool count: "67 registered, 15 meta-tools consolidating 138 actions"

Update any references to old tool names throughout the file.

- [ ] **Step 8.2: Update root CLAUDE.md**

Update the Architecture table module descriptions and any tool counts. Update the Plan Mode section if it references specific tool names.

- [ ] **Step 8.3: Final build and test verification**

```bash
./gradlew :agent:clean :agent:test --rerun --no-build-cache
./gradlew verifyPlugin
./gradlew buildPlugin
```

All must pass.

---

## Verification Checklist

After all tasks are complete, verify:

- [ ] No file references `BitbucketTool`, `BambooTool`, `RuntimeTool`, or `DebugTool` (the old classes)
- [ ] All 15 meta-tools have structured per-action-param descriptions
- [ ] Every tool's action enum contains exactly the right actions (no missing, no extra)
- [ ] Every tool's parameters map contains only params used by its actions (no fat union)
- [ ] `DynamicToolSelector` activates all sub-tools in a group together
- [ ] `ToolCategoryRegistry` lists all new tool names in correct categories
- [ ] `META_TOOLS_WITH_WRITE_ACTIONS` includes all tools with plan-mode-blocked actions
- [ ] `PromptAssembler.buildIntegrationRules()` triggers on new tool name prefixes
- [ ] `./gradlew :agent:test` passes
- [ ] `./gradlew verifyPlugin` passes
- [ ] `./gradlew buildPlugin` produces installable ZIP
