# Claude Code Explore Agent — Complete Research

Research date: 2026-03-23
Sources: Claude Code system prompt (primary), Anthropic docs (code.claude.com/docs/en/sub-agents),
GitHub issues (#9595, #10469, #29379, #29768, #14784), community blogs (Medium, DEV.to, ClaudeLog),
codebase analysis of our existing `:agent` module

---

## 1. WHAT IS THE EXPLORE AGENT?

The Explore agent is one of Claude Code's **built-in subagent types** — a specialized, read-only agent optimized for fast codebase exploration. It runs as an isolated subprocess (subagent) spawned by the main Claude Code agent via the `Agent` tool.

### Core Identity
- **Type**: `subagent_type: "Explore"`
- **Purpose**: Fast, read-only codebase exploration
- **Key property**: Cannot modify anything — pure information gathering
- **Depth**: Flat (depth=1) — cannot spawn its own subagents
- **Default model**: Claude Haiku (fast, cheap) — NOT the parent's model
- **Introduced**: Claude Code v2.0.17 (GitHub issue #9595, Dec 2025)
- **Max concurrent**: Up to 7 subagents can run simultaneously

### When Claude Code Uses It
The main agent spawns an Explore subagent when:
1. It needs to find files matching patterns (e.g., `src/components/**/*.tsx`)
2. It needs to search code for keywords (e.g., "API endpoints")
3. It needs to answer questions about the codebase (e.g., "how do API endpoints work?")
4. The search is open-ended and may require multiple rounds of globbing/grepping
5. The main agent wants to **protect its own context** from verbose search results

### When Claude Code Does NOT Use It
- Simple, directed searches → use Grep/Glob directly
- Searching within a specific file → use Read directly
- Searching 2-3 known files → use Read directly
- Anything requiring edits or writes

---

## 2. THOROUGHNESS LEVELS

The Explore agent has **three thoroughness levels** that control how deep it searches:

| Level | Behavior | Use Case |
|-------|----------|----------|
| `quick` | Basic searches — 1-2 glob/grep operations, return first matches | "Find the config file" |
| `medium` | Moderate exploration — follow references, check related files | "How does authentication work?" |
| `very thorough` | Comprehensive analysis — search across multiple locations, naming conventions, patterns | "Map all API endpoints and their handlers" |

**How thoroughness works**: It's specified by the parent agent in the prompt when spawning the Explore agent. The Explore agent's system prompt instructs it to calibrate its search depth accordingly. It is NOT a parameter — it's a natural language instruction in the prompt.

Example from Claude Code system prompt:
```
"When calling this agent, specify the desired thoroughness level:
'quick' for basic searches, 'medium' for moderate exploration,
or 'very thorough' for comprehensive analysis across multiple
locations and naming conventions."
```

---

## 3. TOOL RESTRICTIONS

### Tools Available to Explore Agent
**All tools EXCEPT:**
- `Agent` — cannot spawn sub-agents (prevents recursion)
- `ExitPlanMode` — not relevant to exploration
- `Edit` — cannot edit files
- `Write` — cannot write files
- `NotebookEdit` — cannot edit notebooks

### What This Means in Practice
The Explore agent CAN use:
- **Read** — read any file
- **Glob** — find files by pattern
- **Grep** — search file contents with regex
- **Bash** — run read-only shell commands (ls, find, wc, etc.)
- **WebSearch** — search the web for context
- **WebFetch** — fetch URLs
- **LSP** — language server protocol queries
- **All MCP tools** — including context7 docs queries

The Explore agent CANNOT:
- Edit or write any file
- Spawn sub-agents
- Create tasks or manage plans
- Enter/exit plan mode

### Key Insight: It's Read-Only but NOT Tool-Limited
Unlike a simple "grep wrapper", the Explore agent has access to ALL read-only tools including Bash, web search, and MCP servers. This means it can:
- Run `git log`, `git blame`, `wc -l` via Bash
- Search documentation via web
- Query language servers
- Use any read-only MCP tool

This is a **much richer toolset** than just Read/Glob/Grep.

### Model Choice: Haiku for Speed
Claude Code defaults Explore to **Haiku** — the fastest, cheapest model. This is a deliberate design:
- Exploration doesn't need deep reasoning — pattern matching + file reading
- Haiku processes search queries ~3x faster than Opus
- Cost is ~10x lower per token
- Known bug (#29768): In some modes, Explore inherits the parent's model (Opus) instead of using Haiku

### Known Issues (from GitHub)
1. **#29379**: Opus misuses Explore by asking it to *solve problems* instead of just finding code. The main agent sends analytical/diagnostic questions to Explore instead of simple search queries.
2. **#29768**: Explore inherits the parent model (Opus) instead of Haiku when running in certain modes.
3. **#20167**: Task tool `model` parameter is ignored for built-in subagent types.

---

## 4. CONTEXT ISOLATION

### Fresh Context Per Invocation
Each Explore agent invocation gets:
- Fresh context window (no parent history)
- Its own system prompt + CLAUDE.md
- Only the task prompt from the parent
- Only the final response returns to the parent as a tool result

### Why This Matters
1. **Context protection**: Parent's context doesn't bloat from search results
2. **Focus**: Agent isn't confused by unrelated conversation history
3. **Cost efficiency**: Only relevant tokens are used
4. **Clean results**: Only the synthesized answer returns, not raw search output

### Resume Capability
Claude Code's Agent tool supports `resume` parameter — the parent can continue a previous Explore agent's work with additional instructions. The full previous transcript is reconstructed.

---

## 5. COMPARISON WITH OTHER SUBAGENT TYPES

### Claude Code Built-in Types (from system prompt)
| Type | Description | Tools |
|------|-------------|-------|
| `general-purpose` | Full capability agent | All tools including Agent |
| `Explore` | Fast read-only exploration | All except Agent, Edit, Write, NotebookEdit, ExitPlanMode |
| `Plan` | Software architect for plans | All except Agent, Edit, Write, NotebookEdit, ExitPlanMode |

### Key Differences: Explore vs Plan
- **Explore**: Answers questions about code, finds patterns, maps structure
- **Plan**: Designs implementation strategies, identifies critical files, considers trade-offs
- Both are read-only, but their system prompts guide very different behaviors

### Key Differences: Explore vs general-purpose
- **Explore**: Focused, fast, read-only — optimized for information gathering
- **general-purpose**: Full capability — can edit, write, spawn sub-agents
- Explore is preferred when no modifications are needed (cheaper, faster, safer)

---

## 6. SYSTEM PROMPT DESIGN

### What the Explore Agent's System Prompt Emphasizes
Based on the Claude Code architecture:
1. **Speed over completeness** — favor fast, focused results
2. **Multiple search strategies** — try different patterns, naming conventions, file paths
3. **Structured findings** — return organized, actionable results
4. **No modifications** — pure read-only, information gathering only
5. **Thoroughness calibration** — adjust depth based on parent's specified level

### Anti-Patterns the Prompt Guards Against
- Exploring too broadly when a quick search suffices
- Returning raw search output instead of synthesized findings
- Attempting to edit or write (tools simply aren't available)
- Going deep when the parent only needs a quick answer

---

## 7. HOW CLAUDE CODE SPAWNS EXPLORE AGENTS

### Invocation Pattern
```json
{
  "name": "Agent",
  "input": {
    "description": "Find authentication handlers",
    "prompt": "Search the codebase for all authentication-related files and handlers. I need to understand how auth works in this project. Thoroughness: medium",
    "subagent_type": "Explore"
  }
}
```

### Parent Agent Decision Heuristic
From Claude Code's system prompt:
> "For broader codebase exploration and deep research, use the Agent tool with subagent_type=Explore. This is slower than using the Glob or Grep directly, so use this only when a simple, directed search proves to be insufficient or when your task will clearly require more than 3 queries."

Decision tree:
1. Need a specific file? → Glob directly
2. Need a specific string in a known file? → Read directly
3. Need a keyword across the codebase? → Grep directly
4. Open-ended search, multiple queries needed? → Explore agent
5. Need to understand HOW something works? → Explore agent

---

## 8. GAP ANALYSIS — OUR CURRENT `explorer` vs CLAUDE CODE'S EXPLORE

### Our Current Implementation

**`explorer` built-in type:**
- Maps to `WorkerType.ANALYZER`
- System prompt: `ANALYZER_SYSTEM_PROMPT` (~200 tokens) — focuses on PSI analysis
- Max iterations: 10
- Tools: Everything with `allowedWorkers` containing `ANALYZER`
- Currently includes: read_file, search_code, glob_files, file_structure, find_definition, find_references, type_hierarchy, call_hierarchy, git_*, diagnostics, run_inspections, breakpoint tools, evaluate_expression, sonar tools, etc.

### Gaps to Close

| Feature | Claude Code | Ours | Gap |
|---------|-------------|------|-----|
| **System prompt** | Specialized exploration prompt with thoroughness calibration | Generic PSI analysis prompt | MAJOR — needs complete rewrite |
| **Thoroughness levels** | quick/medium/very thorough guidance in prompt | None | MAJOR — need to add prompt guidance |
| **Tool restrictions** | Strictly read-only (no Edit, Write) | Has debug tools (add_breakpoint, start_debug_session, etc.) | MEDIUM — explorer shouldn't debug |
| **Context isolation** | Fresh context per invocation | ✓ Already have this (WorkerSession) | OK |
| **Resume capability** | Resume from transcript | ✓ Already have this (SpawnAgentTool) | OK |
| **Background execution** | Run in background | ✓ Already have this | OK |
| **Structured output** | Synthesized findings, not raw results | Prompt says "structured text" but weak | MINOR — strengthen prompt |
| **Decision heuristic** | Specific rules for when to use Explore vs direct search | Not in our prompts | MEDIUM — add to orchestrator prompt |
| **Web search** | Can search web for context | Not available to explorer | N/A — not applicable (enterprise/offline) |
| **IDE intelligence** | N/A (Claude Code is CLI-based) | We have PSI, Spring, Maven tools | ADVANTAGE — we're ahead here |

### Our Advantages Over Claude Code's Explore

1. **PSI-powered navigation**: find_definition, find_references, type_hierarchy, call_hierarchy, find_implementations — Claude Code has nothing equivalent
2. **Spring/framework awareness**: Spring context, endpoints, bean graph, config, JPA entities — deep framework understanding
3. **Maven intelligence**: Dependencies, properties, plugins, profiles — build system awareness
4. **SonarQube integration**: Code quality metrics, issues, coverage — quality context
5. **Git intelligence**: Blame, file history, merge base — VCS context
6. **IDE diagnostics**: Compilation errors, inspections — real-time code health

---

## 9. INTEGRATION PLAN — UPGRADING OUR EXPLORER

### Phase 1: Prompt Overhaul

Replace the generic `ANALYZER_SYSTEM_PROMPT` with a specialized exploration prompt that:
1. Emphasizes speed and focused results
2. Implements thoroughness calibration (quick/medium/very thorough)
3. Teaches multi-strategy search (filename patterns → content search → PSI navigation → Spring context)
4. Requires structured output format
5. Leverages our PSI advantage (guide LLM to use find_references, type_hierarchy, etc.)

### Phase 2: Tool Restriction Refinement

Currently ANALYZER has access to debug and config tools (breakpoints, run configs, etc.) — these should be restricted for the explorer type:
- **Keep**: All read-only tools (read_file, search_code, glob_files, file_structure, PSI tools, git read-only, diagnostics, inspections, sonar read-only, runtime read-only)
- **Remove**: Debug action tools (add_breakpoint, start_debug_session, step tools, etc.), run config mutation (create_run_config, modify_run_config, delete_run_config), edit_file, run_command
- **Consider**: Keep run_command as read-only shell access (like Claude Code's Bash access for `wc -l`, `find`, etc.) but restrict to read-only patterns

### Phase 3: Orchestrator Prompt Enhancement

Add decision heuristic to the orchestrator's system prompt:
- When to use `explorer` vs direct Grep/Glob/Read
- How to specify thoroughness level
- When to run explorer in background vs foreground

### Phase 4: Output Format Enhancement

Standardize explorer output for maximum usefulness to the parent agent:
```
<exploration_results thoroughness="medium">
  <files_found>
    - path/to/file.kt:45 — AuthService interface
    - path/to/impl.kt:23 — AuthServiceImpl
  </files_found>
  <structure>
    - AuthService → AuthServiceImpl (via Spring DI)
    - Uses JwtTokenProvider for token validation
  </structure>
  <key_findings>
    - Auth is JWT-based with refresh tokens
    - Sessions stored in Redis (see RedisSessionStore:12)
  </key_findings>
</exploration_results>
```

---

## 10. UNIQUE OPPORTUNITY: PSI-ENHANCED EXPLORATION

Claude Code's Explore agent uses regex-based tools (Grep, Glob) for exploration. Our explorer has access to **IntelliJ's PSI (Program Structure Interface)** — this is a massive advantage:

### What PSI Enables That Regex Cannot

| Capability | Regex (Claude Code) | PSI (Our Plugin) |
|-----------|---------------------|-------------------|
| Find all implementations of an interface | Grep for class names (misses, false positives) | `find_implementations` — exact, compiler-accurate |
| Trace method call chain | Cannot reliably | `call_hierarchy` — full upstream/downstream |
| Find all usages of a method | Grep for name (ambiguous) | `find_references` — semantically correct |
| Understand class hierarchy | Manual file reading | `type_hierarchy` — instant full tree |
| Map Spring beans and DI | Grep for @Service/@Component (fragile) | `spring_context`, `spring_bean_graph` — complete graph |
| Find API endpoints | Grep for @RequestMapping (partial) | `spring_endpoints` — all endpoints with types |
| Understand Maven structure | Read pom.xml manually | `maven_dependencies`, `project_modules` — parsed |
| Get compilation errors | Run compiler, parse output | `diagnostics` — instant, file-level |

### Strategy: PSI-First Exploration

Our explorer should be prompted to:
1. **Start with PSI tools** for structural questions (find_definition → find_references → type_hierarchy)
2. **Fall back to search** for text-pattern questions (search_code → glob_files)
3. **Use framework tools** for architecture questions (spring_endpoints → spring_bean_graph → spring_context)
4. **Use Git tools** for history questions (git_blame → git_file_history → git_log)
5. **Use diagnostics** for health questions (diagnostics → run_inspections)

This makes our explorer significantly more capable than Claude Code's for Java/Kotlin/Spring projects.

---

## 11. IMPLEMENTATION CONSIDERATIONS

### Token Budget
- Claude Code subagents get a fresh context window (200K or 1M)
- Our WorkerSession gets `settings.state.maxInputTokens` (default 190K)
- Explorer sessions should be short-lived — set a lower budget? Or inherit parent budget?
- Recommendation: Inherit parent budget but set lower max iterations (10 is fine, maybe reduce to 8 for quick thoroughness)

### Max Iterations by Thoroughness
| Thoroughness | Suggested Max Iterations |
|-------------|-------------------------|
| quick | 3-5 |
| medium | 5-8 |
| very thorough | 8-10 |

Note: This is a suggestion in the prompt, not a hard limit. The agent naturally stops when it has enough information.

### Model Selection
- Claude Code uses the same model for subagents (configurable via `CLAUDE_CODE_SUBAGENT_MODEL`)
- Our SpawnAgentTool supports `model` parameter override
- For explorer: Sonnet is sufficient (fast, cheap), Opus for very thorough only
- Recommendation: Default to Sonnet for explorer, let parent override

### Concurrent Explorers
- Claude Code allows multiple background agents
- Our current limit: 5 concurrent workers (MAX_CONCURRENT_WORKERS)
- Multiple explorers can run in parallel for independent searches
- This is already supported — no changes needed

---

## 12. FILES THAT NEED CHANGES

| File | Change |
|------|--------|
| `orchestrator/OrchestratorPrompts.kt` | Replace ANALYZER_SYSTEM_PROMPT with new EXPLORER_SYSTEM_PROMPT |
| `tools/builtin/SpawnAgentTool.kt` | Update explorer description, possibly add thoroughness hint parsing |
| `orchestrator/PromptAssembler.kt` | Add exploration decision heuristic to RULES |
| Various tool files | Review ANALYZER allowedWorkers — remove debug/config tools from explorer |
| `runtime/WorkerType.kt` | Consider renaming ANALYZER → EXPLORER (breaking change, evaluate) |

### New Files (Optional)
| File | Purpose |
|------|---------|
| `runtime/ExplorerConfig.kt` | Thoroughness-based configuration (iterations, model, tool set) |

---

## APPENDIX A: ADDITIONAL WEB RESEARCH FINDINGS

### Sources Consulted
- [Create custom subagents — Claude Code Official Docs](https://code.claude.com/docs/en/sub-agents)
- [Issue #9595: Documentation missing for Explore subagent](https://github.com/anthropics/claude-code/issues/9595) (CLOSED)
- [Issue #10469: Missing docs for Plan/Explore subagent](https://github.com/anthropics/claude-code/issues/10469)
- [Issue #29379: Explore subagent being used to solve problems](https://github.com/anthropics/claude-code/issues/29379) (OPEN)
- [Issue #29768: Explore inherits parent model instead of Haiku](https://github.com/anthropics/claude-code/issues/29768) (OPEN)
- [Issue #14784: Add subagent_type to OTEL telemetry](https://github.com/anthropics/claude-code/issues/14784)
- [The Task Tool: Claude Code's Agent Orchestration (DEV.to)](https://dev.to/bhaidar/the-task-tool-claude-codes-agent-orchestration-system-4bf2)
- [Task/Agent Tools — ClaudeLog](https://claudelog.com/mechanics/task-agent-tools/)
- [Claude Code Deep Dive — Subagents in Action (Medium)](https://medium.com/@the.gigi/claude-code-deep-dive-subagents-in-action-703cd8745769)
- [Claude Code Internals, Part 9: Sub-Agents (Medium)](https://kotrotsos.medium.com/claude-code-internals-part-9-sub-agents-2c3da315b1c0)
- [Claude Code Sub-agents: 90% Performance Gain (CodeWithSeb)](https://www.codewithseb.com/blog/claude-code-sub-agents-multi-agent-systems-guide)

### Key Finding: Haiku Default Model
From official docs and GitHub issues: Explore defaults to **Haiku** for speed/cost efficiency. The parent agent runs on Opus/Sonnet, but Explore uses the cheapest model because exploration doesn't require deep reasoning — just fast file reading and pattern matching.

### Key Finding: Disabling Explore
Explore can be disabled via settings:
```json
{ "permissions": { "deny": ["Agent(Explore)"] } }
```
Or CLI: `claude --disallowedTools "Agent(Explore)"`

### Key Finding: Up to 7 Concurrent Agents
From ClaudeLog: "It delegates work to parallel sub-agents—file reads, code searches, web fetches—running up to 7 agents simultaneously." Users must balance token costs against performance gains.

### Key Finding: Misuse Pattern (#29379)
A known issue where Opus over-delegates to Explore: instead of asking "find all auth files", the main agent asks Explore to "analyze the authentication architecture and suggest improvements." This defeats the purpose — Explore should only FIND things, not ANALYZE them. This is important for our system prompt design.

---

## APPENDIX B: Claude Code Agent Tool Full Specification (from System Prompt)

From Claude Code's system prompt (verbatim excerpt):

```
Agent tool description:
"Launch a new agent to handle complex, multi-step tasks autonomously."

Available agent types:
- general-purpose: General-purpose agent for researching complex questions,
  searching for code, and executing multi-step tasks.
- Explore: Fast agent specialized for exploring codebases. Use this when you
  need to quickly find files by patterns, search code for keywords, or answer
  questions about the codebase. When calling this agent, specify the desired
  thoroughness level: "quick" for basic searches, "medium" for moderate
  exploration, or "very thorough" for comprehensive analysis across multiple
  locations and naming conventions.
  (Tools: All tools except Agent, ExitPlanMode, Edit, Write, NotebookEdit)
- Plan: Software architect agent for designing implementation plans.
  (Tools: All tools except Agent, ExitPlanMode, Edit, Write, NotebookEdit)

When NOT to use the Agent tool:
- If you want to read a specific file path, use the Read tool
- If you are searching for a specific class definition like "class Foo", use the Glob tool
- If you are searching for code within a specific file or set of 2-3 files, use Read
- Other tasks not related to the agent descriptions

Usage notes:
- Always include a short description (3-5 words)
- When done, returns a single message back to you
- Can run in background using run_in_background parameter
- Agents can be resumed using the resume parameter
- Provide clear, detailed prompts so agent can work autonomously
```

## APPENDIX B: Our Current Explorer Tool Set (ANALYZER Worker)

Tools currently available to our `explorer` (WorkerType.ANALYZER):

**Core Read-Only:**
read_file, search_code, glob_files, file_structure, think, request_tools

**PSI Navigation:**
find_definition, find_references, type_hierarchy, call_hierarchy, find_implementations

**Framework:**
spring_context, spring_endpoints, spring_bean_graph, spring_config, jpa_entities, project_modules,
maven_dependencies, maven_properties, maven_plugins, maven_profiles, spring_version_info, spring_profiles,
spring_repositories, spring_security_config, spring_scheduled_tasks, spring_event_listeners

**IDE Intelligence:**
diagnostics, run_inspections, list_quickfixes

**VCS (Read-Only):**
git_status, git_blame, git_diff, git_log, git_branches, git_show_file, git_show_commit,
git_stash_list, git_merge_base, git_file_history

**Runtime (Read-Only):**
get_run_configurations, get_running_processes, get_run_output, get_test_results

**Sonar (Read-Only):**
sonar_issues, sonar_quality_gate, sonar_coverage, sonar_search_projects, sonar_analysis_tasks,
sonar_branches, sonar_project_measures, sonar_source_lines, sonar_issues_paged

**Enterprise (Read-Only):**
bitbucket_list_repos

**Debug (SHOULD BE REMOVED for explorer):**
add_breakpoint, remove_breakpoint, list_breakpoints, start_debug_session, get_debug_state,
debug_step_over, debug_step_into, debug_step_out, debug_resume, debug_pause, debug_run_to_cursor,
debug_stop, evaluate_expression, get_stack_frames, get_variables

**Config (SHOULD BE REMOVED for explorer):**
create_run_config, modify_run_config, delete_run_config

**Planning:**
create_plan, update_plan_step, ask_questions, save_memory
