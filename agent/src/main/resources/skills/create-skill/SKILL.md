---
name: create-skill
description: Create a new custom skill for this project or personal use. Use when the user says "create a skill", "make a skill", "turn this into a skill", "I want a workflow for X", or when you identify a repeatable workflow worth capturing. Guides through intent capture, tool selection, and SKILL.md generation.
disable-model-invocation: true
user-invocable: true
preferred-tools: [think, ask_followup_question, edit_file, read_file, search_code]
---

# Skill Creator

Create custom skills (SKILL.md files) that extend the agent with project-specific workflows.

## Overview

Skills are markdown files with YAML frontmatter that provide workflow instructions. When activated via `use_skill`, the skill's instructions are injected into your context.

**Skill format:**
```yaml
---
name: my-skill
description: When to trigger and what it does (be specific and a bit pushy)
preferred-tools: [tool1, tool2, tool3]
user-invocable: true
---
# Instructions here
```

**Supported frontmatter fields:**
- `name` (required) — unique skill identifier, must match directory name
- `description` (required) — used by the agent to decide when to auto-trigger the skill
- `preferred-tools` — list of tool names to prioritize (documentation, not enforced)
- `user-invocable` — if true, user can type `/skill-name` to trigger manually
- `disable-model-invocation` — if true, only user can trigger (not auto-triggered)

**Skill locations:**
- Project: `.agent-skills/{name}/SKILL.md` (committed to VCS, shared with team)
- Personal: `~/.workflow-orchestrator/skills/{name}/SKILL.md` (your preferences only)

Override precedence: global > project > bundled. A personal skill with the same name overrides a project skill.

## Process

### Step 1: Capture Intent

If the conversation already contains a workflow the user wants to capture (e.g., "turn this into a skill"), extract the steps, tools used, and corrections made. Otherwise, use `ask_followup_question` to gather:

```
ask_followup_question(questions='[
  {"id":"purpose","question":"What should this skill help you do?","type":"single","options":[
    {"id":"workflow","label":"Automate a multi-step workflow","description":"E.g. hotfix, deploy, review prep"},
    {"id":"checklist","label":"Enforce a checklist/process","description":"E.g. pre-merge checks, standup prep"},
    {"id":"other","label":"Something else","description":"I will explain in chat"}
  ]},
  {"id":"scope","question":"Where should this skill live?","type":"single","options":[
    {"id":"project","label":"Project skill (.agent-skills/)","description":"Shared with team via VCS"},
    {"id":"personal","label":"Personal skill (~/.workflow-orchestrator/skills/)","description":"Only for you"}
  ]}
]')
```

### Step 2: Identify Tools

Use `think` to analyze which tools are relevant to this workflow. You can also use `tool_search` to discover tools by keyword.

The agent has tools across 11 categories:

- **Core:** read_file, edit_file, create_file, search_code, glob_files, run_command, think, agent, project_context, revert_file
- **IDE:** diagnostics, format_code, optimize_imports, refactor_rename, run_inspections, list_quickfixes, problem_view
- **PSI / Code Intelligence:** find_definition, find_references, find_implementations, file_structure, type_hierarchy, call_hierarchy, type_inference, structural_search, dataflow_analysis, get_method_body, get_annotations, read_write_access, test_finder
- **Runtime & Debug:** runtime_exec (actions: run_tests, compile_module, get_test_results, get_run_output, get_running_processes), runtime_config (actions: get_run_configurations, create_run_config, modify_run_config), debug_breakpoints, debug_step, debug_inspect, coverage
- **VCS:** git (actions: status, blame, diff, log, branches, show_file, show_commit, merge_base, file_history, stash_list, shelve), changelist_shelve
- **Framework:** spring (actions: context, bean_graph, config, jpa_entities, etc.; also endpoints/boot_endpoints on IntelliJ Community), endpoints (actions: list, find_usages, export_openapi, export_http_scratch — multi-framework on Ultimate/Pro/WebStorm), build (actions: maven_dependencies, gradle_dependencies, etc.)
- **Jira:** jira (actions: get_ticket, get_transitions, transition, comment, log_work, search_issues, etc.)
- **Bamboo:** bamboo_builds (actions: build_status, get_build, trigger_build, get_build_log, get_test_results, etc.), bamboo_plans
- **SonarQube:** sonar (actions: issues, quality_gate, coverage, search_projects, analysis_tasks, etc.)
- **Bitbucket:** bitbucket_pr (actions: create_pr, get_pr_diff, get_pr_changes, get_pr_commits, etc.), bitbucket_review, bitbucket_repo
- **Memory:** core_memory_append, core_memory_replace, core_memory_read, archival_memory_insert, archival_memory_search, save_memory, conversation_search
- **Planning & Communication:** enable_plan_mode, plan_mode_respond, ask_followup_question, attempt_completion, use_skill
- **Database:** db_schema, db_query, db_list_profiles

Select the 3-8 most relevant tools for the `preferred-tools` field. These are documentation for readers and the agent — not hard restrictions.

### Step 3: Write the SKILL.md

**Key principles:**
- Keep the main file under 500 lines (ideally under 200)
- Use imperative form ("Do X", not "You should do X")
- Include specific tool names in instructions (e.g., "Use `sonar(action=\"issues\")` to check for blockers")
- Be explicit about the workflow steps
- Include `$ARGUMENTS` placeholder if the skill accepts input (e.g., ticket ID)
- Explain WHY steps matter, not just WHAT to do

**Description writing tips:**
- Be pushy — the description is how the agent decides to auto-trigger the skill
- Include trigger phrases: "Use when the user mentions X, Y, or Z"
- Include what it does AND when to use it
- Example: "Prepare code for review — run quality checks, fix blockers, verify coverage. Use when the user says 'review prep', 'prepare for PR', 'ready for review', or mentions code quality before merging."

### Step 4: Save the Skill

Based on the scope chosen in Step 1:

**Project skill:**
```
.agent-skills/{name}/SKILL.md
```

**Personal skill:**
```
~/.workflow-orchestrator/skills/{name}/SKILL.md
```

Use `edit_file` to create the file. Create the directory structure first if needed.

After saving, inform the user:
- "Skill created at {path}. It will be available on the next session start."
- "You can invoke it with `/{name}` or the agent will auto-trigger it when relevant."
- "To edit, modify the SKILL.md file directly."

### Step 5: Use archival_memory_insert to Remember

Call `archival_memory_insert` to note what skills exist:
```
archival_memory_insert(
  content="Created skill '{name}' at {path}. Purpose: {description}. Tools: {preferred-tools}.",
  tags="skills-created"
)
```

This helps the agent remember available skills across sessions.

## Example Skills

### Hotfix Workflow
```yaml
---
name: hotfix
description: Create and deploy a hotfix for production issues. Use when the user mentions hotfix, production bug, urgent fix, P0, or emergency patch.
preferred-tools: [jira, git, bamboo_builds, bitbucket_pr]
---
## Hotfix Workflow
1. Get the ticket details: use `jira(action="get_ticket")` with $ARGUMENTS (ticket ID)
2. Use `git(action="status")` to check current branch state
3. Transition ticket to "In Progress": use `jira(action="transition")`
4. Guide the user through the fix
5. Verify the build: use `bamboo_builds(action="trigger_build")`
6. Create PR targeting master: use `bitbucket_pr(action="create_pr")`
```

### Pre-Review Checklist
```yaml
---
name: review-prep
description: Prepare code for review — quality checks, fix blockers, verify coverage. Use when the user says review prep, prepare for PR, ready for review, or code quality before merging.
preferred-tools: [sonar, runtime_exec, diagnostics]
---
## Pre-Review Checklist
1. Run `runtime_exec(action="compile_module")` to verify clean compilation
2. Run `runtime_exec(action="run_tests")` for the affected module
3. Check `sonar(action="issues")` for any blocker/critical issues on changed files
4. Check `sonar(action="coverage")` meets the project threshold
5. Run `diagnostics` on changed files for IDE-level warnings
6. Summarize findings and suggest fixes
```

### Sprint Standup Context
```yaml
---
name: standup
description: Gather context for daily standup — current ticket, recent commits, build status. Use when the user asks about their work, standup, or what they did yesterday.
user-invocable: true
preferred-tools: [jira, git, bamboo_builds]
---
## Standup Context
1. Use `jira(action="get_ticket")` to get the active ticket details
2. Use `git(action="status")` to see current branch and recent changes
3. Use `bamboo_builds(action="build_status")` to check if the latest build passed
4. Summarize: what was done, what's in progress, any blockers
```
