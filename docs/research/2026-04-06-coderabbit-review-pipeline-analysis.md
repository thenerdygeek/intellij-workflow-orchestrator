# CodeRabbit AI Code Review Pipeline — Deep Analysis

**Date:** 2026-04-06
**Purpose:** Inform design of our bundled code-reviewer sub-agent for the `:agent` module

---

## 1. Review Pipeline — End-to-End Sequence

CodeRabbit uses a **hybrid pipeline+agentic architecture** — a deterministic pipeline with agentic elements (tool use, learned behavior, context enrichment) layered in. The full sequence from PR webhook to posted review:

### Stage 1: Event Ingestion (0-50ms)
- **Stateless API Gateway** receives GitHub/GitLab/Bitbucket/Azure DevOps webhook
- Validates HMAC signature, performs billing/subscription checks
- Immediately acknowledges (GitHub requires response within 10 seconds)
- Pushes task to **high-throughput queue** (Kafka/Redpanda on Google Cloud Tasks)
- Decouples reception from processing — GitHub gets instant 200 while review takes 60-90s

### Stage 2: Gatekeeper / Triage Filter (seconds 0-2)
Rule-based filtering before any LLM invocation:
- **Discards ~40% of events immediately** — bot PRs, lockfiles, documentation-only changes, generated code
- **Lane routing** — prevents massive monorepo PRs from blocking smaller submissions
- Heuristic rules (regex + metadata) classify changes
- Checks: file types, PR size, draft status, title keywords (e.g., "WIP"), label filters, base branch filters
- **Result: eliminates ~65% of noise** before any compute cost

### Stage 3: Code Analysis & AST Parsing (seconds 2-4)
- **Tree-Sitter AST parsing** extracts symbols, scope boundaries, imports, function calls from changed code
- Builds lightweight map of definitions and references
- **Incremental analysis** — only analyzes what changed, not entire codebase

### Stage 4: GraphRAG Context Building (seconds 4-8)
Three-phase context assembly:
1. **AST Parsing** — extract symbols from diffs
2. **Graph Querying** — trace which external files call/import modified functions (codegraph)
3. **Context Injection** — fetch relevant caller snippets for inclusion in prompts

Key capabilities:
- Scans commit history for files that frequently change together
- **Semantic code indexing** via LanceDB — embeddings of functions, classes, tests, prior PRs
- Searches by semantic purpose, not keyword matching
- Queries **knowledge base** — past learnings, review instructions, web search results
- **Relevance ranking**: same-file references (P1) > same-module (P2) > test files (P3) > distant modules (P4)

Example: Changing `calculatePrice(item)` to `calculatePrice(item, discount)` triggers a graph walk that discovers all 15 callers across 8 files — enabling detection of breaking changes invisible in an isolated diff.

### Stage 5: Model Cascade / Router Classification (seconds 8-10)
A **fast router** (small/cheap LLM) classifies each file's changes:
- `COSMETIC` — whitespace, formatting, variable renames
- `DOCS` — documentation changes
- `LOGIC` — business logic modifications
- `SECURITY` — authentication, data handling, crypto

Routing:
- **Tier 1 (Fast Path)**: Cosmetic/docs changes → static analysis tools only, near-zero cost
- **Tier 2 (Slow Path)**: Logic/security changes → frontier LLM with Chain-of-Thought reasoning
- **Cost impact: ~70% cost reduction** through intelligent routing

### Stage 6: Static Analysis Tool Execution (parallel with Stage 5-7)
Runs **40-50+ static analyzers** in sandboxed environments:

| Category | Tools |
|---|---|
| Code quality | ESLint, Biome, oxlint, Ruff, Pylint, RuboCop, SwiftLint, PHPStan, PHPMD, golangci-lint, Clippy |
| Security | Gitleaks/Betterleaks, Semgrep, Checkov, Brakeman |
| Infrastructure | Hadolint (Docker), Checkov (IaC) |
| Formatting | Prettier, Biome |
| Docs/markup | markdownlint, LanguageTool |
| Shell | ShellCheck |
| YAML | yamllint |
| AST patterns | ast-grep |

Each tool:
- Respects existing project config files (`.eslintrc.js`, `pyproject.toml`, etc.)
- Can be individually enabled/disabled via `.coderabbit.yaml`
- Runs in sandboxed environment (Jailkit + cgroups)

### Stage 7: LLM Review Generation (seconds 10-30)
For files routed to deep analysis:
- **Summarization** (cheap model, e.g., GPT-3.5-turbo or GPT-4.1): compresses large code diffs into concise summaries
- **Deep review** (expensive model, e.g., o3, o4-mini): reviews each file with full context
- Chain-of-Thought reasoning for logic/security changes
- Applies path-specific review instructions from `.coderabbit.yaml`
- References knowledge base learnings to avoid known false positives

### Stage 8: Agentic Verification (seconds 30-45)
A **separate verification agent** checks and grounds review feedback:
- Generates shell scripts to verify claims (cat, grep, ast-grep, Python analysis)
- Runs in isolated "tools in jail" sandbox (Jailkit + cgroups, microVM with full Linux cgroup)
- Validates assumptions with evidence before posting comments
- Detects prompt injection attacks and edge cases

### Stage 9: Signal Filtering & Post-Processing (seconds 45-50)
- **Deduplication** — merge identical issues found by multiple tools/models
- **Severity scoring** — assign severity to each finding
- **Smart delivery routing**:
  - Critical/High → inline comments (may block merges)
  - Medium → grouped summary tables
  - Low/Nitpick → collapsible optional sections
- **Semantic caching** — compares with previous review comments to avoid duplicates on incremental commits
- **Memory pattern checking** — filters based on user feedback (thumbs up/down) history

### Stage 10: Review Posting (seconds 50-60)
Posts formatted review to the PR platform (GitHub, GitLab, Bitbucket, Azure DevOps).

---

## 2. What CodeRabbit Analyzes — Context Scope

### Primary Input: The Diff
- CodeRabbit only analyzes **diffs, not the entire project**
- On GitHub: PR changes (diff between branches)
- On CLI: staged changes or specific commits
- On IDE: uncommitted changes

### Extended Context (beyond the diff)
1. **Full file context** — surrounding code for each changed file (not just diff hunks)
2. **Cross-file dependencies** — via codegraph (AST + import/call tracing)
3. **Caller/callee analysis** — traces which files call modified functions
4. **Commit history** — files that frequently change together
5. **Semantic embeddings** — prior PRs, functions, classes, tests (via LanceDB)
6. **Knowledge base** — stored learnings from past reviews
7. **Linked issues** — Jira/Linear ticket context
8. **Related PRs** — similar past PRs
9. **Repository conventions** — from `.coderabbit.yaml` instructions
10. **Static analysis results** — from 40+ tools

### What It Does NOT Analyze
- Runtime behavior / dynamic analysis
- Full project scan (only diff-triggered context)
- Test execution results
- Deployment configurations (unless in the diff)

---

## 3. Review Categories / Dimensions

### Issue Types Detected
| Category | Examples |
|---|---|
| **Logic/Correctness** | Business logic errors, misconfigurations, missing error handling, unsafe control flow, edge cases |
| **Security** | XSS, insecure deserialization, improper auth, insecure object references, secret leaks, prompt injection |
| **Performance** | Excessive I/O, repeated file reads, unnecessary network calls, N+1 queries |
| **Maintainability** | Code smells, naming inconsistencies, dead code, high complexity |
| **Readability** | Poor naming, unclear logic, formatting problems |
| **Testing** | Missing test coverage, incomplete assertions, untested edge cases |
| **Documentation** | Missing docstrings, outdated comments |
| **Breaking changes** | API signature changes that affect callers |
| **Dependencies** | Version conflicts, unused imports |

### Severity Levels
| Level | Emoji | Meaning |
|---|---|---|
| Critical | Red circle | System failures, security breaches, data loss |
| Major | Orange circle | Significant functionality impact |
| Minor | Yellow circle | Should address, not critical |
| Trivial | Blue circle | Low-impact quality improvements |
| Info | White circle | Informational, no action required |
| Nitpick | Broom | Style/formatting (Assertive mode only) |

### Comment Types
| Type | Meaning |
|---|---|
| Potential issue | Bugs, vulnerabilities, problematic patterns |
| Refactor suggestion | Maintainability/performance improvements |
| Nitpick | Style/formatting (Assertive mode only) |

---

## 4. Context Handling Strategy

### Summarization Pipeline
1. **Per-file diff summarization** — cheap model (GPT-3.5-turbo/GPT-4.1) compresses each file's diff
2. **Summary of summaries** — aggregates file summaries into PR-level summary
3. **Incremental update** — on new commits, only new changes are summarized and merged with existing summary
4. **Triage classification** — cheap model classifies each diff as trivial/complex (piggybacks on summarization prompt)

### File-by-File vs Holistic
- **File-by-file analysis**: each file is reviewed individually with its relevant context
- **Holistic summary**: a PR-level walkthrough is generated from all file summaries
- **Cross-file awareness**: codegraph provides cross-file dependency context per file, but the review is fundamentally file-by-file with cross-file context injected

### Context Compression Strategy
- Custom input formats "closer to human understanding" (not raw unified diffs)
- Few-shot examples embedded in prompts for desired output format
- Relevant code snippets (not entire files) — function signatures, docstrings, type annotations
- Path-specific instructions injected per file
- Knowledge base learnings appended to relevant file reviews

---

## 5. Output Format

### Walkthrough Comment (first PR comment)
A single summary comment containing:

| Section | Description | Configurable |
|---|---|---|
| **High-level summary** | What the PR does and why | `high_level_summary: true` |
| **Walkthrough** | File-by-file change descriptions | `collapse_walkthrough: false` |
| **Changed files table** | Table of all modified files with change type | `changed_files_summary: true` |
| **Sequence diagram** | Mermaid diagram for request flows/API calls | `sequence_diagrams: true` |
| **Linked issue assessment** | Status indicators for linked issues | `assess_linked_issues: true` |
| **Related issues** | Similar issues in the repo | `related_issues: true` |
| **Related PRs** | Similar past PRs | `related_prs: true` |
| **Suggested labels** | Recommended PR labels | `suggested_labels: true` |
| **Suggested reviewers** | Recommended human reviewers | `suggested_reviewers: true` |
| **Estimated review effort** | Time estimate for human review | Default on |
| **Poem** | Creative haiku/poem about changes | `poem: true` |

### Inline Review Comments
Each inline comment contains:
- **Severity indicator** (emoji: Critical/Major/Minor/Trivial/Info/Nitpick)
- **Main feedback text** — description of the issue and why it matters
- **Suggested code fix** — committable suggestion (GitHub suggested changes format)
- **"Fix with AI" button** — for complex fixes (Pro plan)
- **"Prompt for AI Agents" section** — explicit instructions for Claude Code/Codex to action the fix

### Review Status
- `review_status: true` — posts review progress/details
- `commit_status: true` — sets pending/success commit statuses
- `fail_commit_status: false` — optionally block merges on failure
- `request_changes_workflow: false` — mark as "request changes", auto-approve when resolved

---

## 6. Special Features

### Learnable Knowledge Base
- Every reply/correction teaches CodeRabbit preferences
- When you explain why a suggestion doesn't apply, it stores as a **learning**
- Avoids making the same false-positive suggestion in future reviews
- Scoped: `local` (repo), `global` (org), or `auto`
- Teams typically see significant false-positive reduction within 2 weeks of active feedback

### Feedback Loop / Memory
- Captures thumbs up/down on comments
- Builds `positive_patterns` and `negative_patterns` databases
- Future reviews query these, adding guardrails: "Based on user feedback, avoid suggesting X in similar contexts"

### Semantic Caching
- Uses LLM comparison of summaries (not vector similarity) to detect duplicate reviews
- Prevents re-posting same comments on minor incremental updates
- Saves ~20% cost

### Auto-Fix / Committable Suggestions
- **One-click commit** — GitHub suggested changes format, apply directly from PR
- **"Fix with AI" button** — for complex multi-line fixes
- **Prompt for AI Agents** — generates explicit prompt for Claude Code/Codex to implement the fix

### Incremental Reviews
- `auto_incremental_review: true` (default) — only reviews new changes on each push
- Avoids re-reviewing unchanged code
- Maintains running summary that is incrementally updated
- `@coderabbitai full review` — forces complete re-review from scratch

### Review Profiles
- **chill** (default): focuses on bugs, security, logic errors only
- **assertive**: includes style, naming, documentation, best practices
- **followup**: tracks whether previous comments were addressed

### Path-Specific Instructions
```yaml
reviews:
  path_instructions:
    - path: "src/api/**"
      instructions: |
        Ensure all endpoints have input validation.
        Check for proper error handling and HTTP status codes.
    - path: "**/*.test.*"
      instructions: |
        Each test function must contain at least one assertion
        and test exactly one behavior.
```

### Tone Customization
- `tone_instructions` field (max 250 chars) for custom voice
- E.g., "Be concise. Focus on security. Skip style nitpicks."

### Chat / Conversational Review
- `@coderabbitai` mentions in PR comments trigger contextual discussion
- Can ask for explanations, request code generation, generate docstrings
- Integrates with Jira/Linear for issue context

### Review Commands
| Command | Effect |
|---|---|
| `@coderabbitai review` | Trigger full re-review |
| `@coderabbitai full review` | Complete re-review from scratch |
| `@coderabbitai summary` | Regenerate PR summary |
| `@coderabbitai generate docstrings` | Auto-generate missing docstrings |
| `@coderabbitai resolve` | Dismiss comment thread |
| `@coderabbitai explain` | Detailed explanation of code |
| `@coderabbitai configuration` | Display active config |
| `@coderabbitai pause/resume` | Control review state |
| `@coderabbitai help` | List all commands |

### Finishing Touches
- `docstrings.enabled: true` — auto-generate missing docstrings
- Suggested labels and auto-apply
- Suggested reviewers and auto-assign

---

## 7. Review Scope Variants

### PR Review (primary mode)
- Triggered by: PR creation, new commits pushed, `@coderabbitai review` command
- Scope: diff between PR source and target branches
- Output: walkthrough comment + inline comments on the PR
- Full context engine: codegraph, knowledge base, static analysis, multi-model cascade
- Incremental: only new changes on subsequent commits (configurable)

### CLI Review (local, pre-commit)
- Command: `coderabbit` (interactive) or `coderabbit --prompt-only` (plain text for AI agents)
- Scope: staged changes (pre-commit) or specific commit vs parent
- Same analysis engine: 40+ static analyzers, codegraph, security scanners
- Output: terminal text or plain-text prompt for AI agents (Claude Code integration)
- Use case: catch issues before pushing / opening PR

### IDE Review (VS Code extension)
- Scope: uncommitted changes in the editor
- Real-time feedback as you code
- Same underlying analysis, different delivery surface

### Commit Review
- Compares specific commit to its parent
- Available via CLI command
- Narrower scope than PR review

---

## 8. Multi-Model Architecture

### Current Model Stack (as of early 2026)
| Role | Models | Purpose |
|---|---|---|
| **Summarization** | GPT-4.1 (1M context) | Diff compression, docstring generation, routine QA |
| **Triage/Router** | Small/cheap LLM | Classify changes as cosmetic/docs/logic/security |
| **Deep Review** | o3, o4-mini | Multi-line bugs, refactors, architecture issues, security |
| **Verification** | Separate agent | Shell script generation, ast-grep analysis, claim verification |
| **Semantic Cache** | LLM comparison | Compare summaries to detect duplicate reviews |
| **Evaluation** | LLM-judge recipes | Tone, clarity, helpfulness assessment during model rollout |

### Cost Optimization Results
- **50% reduction** from dual-model summarization + trivial change filtering
- **20% reduction** from rate-limiting (FluxNinja Aperture)
- **20% reduction** from semantic caching

### Model Evaluation Framework
When onboarding new models, CodeRabbit runs:
1. **Curiosity phase** — understand architectural characteristics, generate evaluation configs (temperature, context packing, instruction phrasing)
2. **Evaluation phase** — run against internal eval set measuring coverage, precision, signal-to-noise, latency, plus LLM-judge for tone/clarity/helpfulness
3. **Adaptation phase** — model-specific prompt tuning ("prompt physics"), meta-level feedback loops
4. **Staged rollout** — internal testing → early-access users → gradual randomized distribution → continuous monitoring

---

## 9. Infrastructure & Security

- **Google Cloud Run** — serverless execution, 3600s timeout, concurrency of 8 per instance
- **Sandboxing** — two layers: Cloud Run microVM (full Linux cgroup) + Jailkit isolated processes
- **Ephemeral environments** — spin up per review, pull only needed code, tear down after
- **Minimal IAM permissions** per Cloud Run instance
- **Rate limiting** — FluxNinja Aperture, 3 reviews/hour for OSS (burst of 2)

---

## 10. Key Architectural Principles (Lessons for Our Agent)

1. **"Curate context, don't wander"** — deterministic context assembly beats autonomous tool-calling. More context isn't better; *better* context is better.

2. **Model cascade is essential** — route trivial changes to cheap/free paths, expensive models only for logic/security. 70% cost savings.

3. **Static analysis + LLM = best results** — run 40+ linters BEFORE the LLM, feed findings into the prompt. AI reduces false positives from static tools.

4. **File-by-file with cross-file context** — review each file individually, but inject dependency/caller context from codegraph. Not holistic whole-PR review.

5. **Summarize first, review second** — cheap model summarizes diffs, then expensive model reviews with summaries as context. Two-pass approach.

6. **Verification agent is critical** — separate agent that generates scripts to verify claims before posting. Grounds LLM output in evidence.

7. **Incremental by default** — only review new changes on subsequent commits, maintain running summary.

8. **Learnable rules** — store feedback as learnings, reduce false positives over time. This is a major retention driver.

9. **Severity-based delivery** — don't dump all findings equally. Critical = inline blocking, Nitpick = collapsible section.

10. **Path-specific instructions** — different review standards for different parts of the codebase (API vs tests vs UI).

---

## Sources

- [Pipeline AI vs. Agentic AI for Code Reviews](https://www.coderabbit.ai/blog/pipeline-ai-vs-agentic-ai-for-code-reviews-let-the-model-reason-within-reason)
- [CodeRabbit Deep Dive](https://www.coderabbit.ai/blog/coderabbit-deep-dive)
- [Architecting CodeRabbit: Event Storm & Context Engine](https://learnwithparam.com/blog/architecting-coderabbit-ai-agent-at-scale)
- [Architecting CodeRabbit: Intelligence Layer](https://learnwithparam.com/blog/architecting-coderabbit-ai-agent-intelligence-layer)
- [How CodeRabbit Delivers Accurate Reviews on Massive Codebases](https://www.coderabbit.ai/blog/how-coderabbit-delivers-accurate-ai-code-reviews-on-massive-codebases)
- [How We Built Cost-Effective Generative AI Application](https://www.coderabbit.ai/blog/how-we-built-cost-effective-generative-ai-application)
- [CodeRabbit's Agentic Code Validation](https://www.coderabbit.ai/blog/how-coderabbits-agentic-code-validation-helps-with-code-reviews)
- [What It Takes to Bring a New Model Online](https://www.coderabbit.ai/blog/behind-the-curtain-what-it-really-takes-to-bring-a-new-model-online-at-coderabbit)
- [Framework for Evaluating AI Code Review Tools](https://www.coderabbit.ai/blog/framework-for-evaluating-ai-code-review-tools)
- [CodeRabbit CLI Blog Post](https://www.coderabbit.ai/blog/coderabbit-cli-free-ai-code-reviews-in-your-cli)
- [Shipping Code Faster with o3, o4-mini, GPT-4.1 (OpenAI)](https://openai.com/index/coderabbit/)
- [CodeRabbit + LanceDB Case Study](https://lancedb.com/blog/case-study-coderabbit/)
- [CodeRabbit + Google Cloud Run](https://cloud.google.com/blog/products/ai-machine-learning/how-coderabbit-built-its-ai-code-review-agent-with-google-cloud-run)
- [CodeRabbit YAML Configuration Reference (DEV)](https://dev.to/rahulxsingh/coderabbit-configuration-coderabbityaml-reference-5493)
- [CodeRabbit Custom Rules Guide (DEV)](https://dev.to/rahulxsingh/coderabbit-custom-rules-review-instructions-guide-1nc1)
- [Code Review System (DeepWiki)](https://deepwiki.com/coderabbitai/coderabbit-docs/4-code-review-system)
- [Review Configuration Options (DeepWiki)](https://deepwiki.com/coderabbitai/coderabbit-docs/2.1-review-configuration-options)
