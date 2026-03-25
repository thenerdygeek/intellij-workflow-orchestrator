# How Enterprise AI Coding Tools Handle Vague, Ambiguous, and Poorly-Typed Prompts

**Research Date:** 2026-03-25
**Sources:** Production source code, official docs, API repos, blog posts

---

## Executive Summary

**The dominant finding across all production tools is: none of them preprocess, rewrite, or classify user prompts before the agent loop.** Every major tool relies on the underlying LLM's native ability to interpret vague input, combined with system prompt instructions that tell the model when to ask for clarification. There is no "prompt quality classifier," no "complexity router," and no smaller model rewriting prompts before the main model sees them.

The real engineering effort goes into **context enrichment** (giving the model enough surrounding information to compensate for vague prompts) and **tool-based clarification** (providing the model with a tool to ask the user for more info when it determines it needs it).

---

## Tool-by-Tool Analysis

### 1. Claude Code (Anthropic)

**Source:** Official docs at code.claude.com, system prompt behavior, best practices guide

**Prompt preprocessing:** None. User input goes directly to Claude as-is. No rewriting, no typo correction, no intent classification before the agentic loop starts.

**How it handles vague prompts:** Claude Code's approach is "it's a conversation." The docs explicitly state:
- "You don't need perfect prompts. Start with what you want, then refine."
- "Vague prompts work, but you'll spend more time steering."
- "Vague prompts can be useful when you're exploring and can afford to course-correct."

**Clarifying questions:** Claude Code has a built-in `AskUserQuestion` tool. The LLM decides when to use it based on its own judgment. The docs recommend users explicitly request Claude to interview them for complex tasks:
> "I want to build [brief description]. Interview me in detail using the AskUserQuestion tool. Ask about technical implementation, UI/UX, edge cases, concerns, and tradeoffs."

**Context enrichment instead of prompt rewriting:** Rather than fixing the prompt, Claude Code compensates for vague prompts by:
- Reading CLAUDE.md at session start (persistent project context)
- Loading auto-memory from previous sessions
- Gathering context autonomously (reading files, searching, running commands)
- The three-phase loop: gather context -> take action -> verify results

**Complexity routing:** No explicit router. The model itself decides whether to explore, plan, or act. Users can manually switch to Plan Mode (read-only exploration) for complex tasks.

**Missing context handling:** When user says "fix the bug" without specifying which file, Claude Code:
1. Searches the codebase using its tools (grep, glob, file reading)
2. Reads error output, test failures, git diff
3. Makes its best guess at what needs fixing
4. The user course-corrects if wrong

**Key insight from best practices:** The docs recommend specific prompts over vague ones but frame it as a user responsibility, not a system responsibility:
| Before | After |
|--------|-------|
| "fix the login bug" | "users report that login fails after session timeout. check the auth flow in src/auth/" |
| "make the dashboard look better" | "[paste screenshot] implement this design" |
| "add tests for foo.py" | "write a test for foo.py covering the edge case where the user is logged out" |

### 2. Cline (Open Source)

**Source:** GitHub repo source code (src/core/prompts/)

**Prompt preprocessing:** None. User message goes directly to the LLM with the system prompt.

**Clarifying questions:** Cline has a dedicated `ask_followup_question` tool defined as:
```
"Ask the user a question to gather additional information needed to complete the task.
This tool should be used when you encounter ambiguities, need clarification, or require
more details to proceed effectively. It allows for interactive problem-solving by enabling
direct communication with the user. Use this tool judiciously to maintain a balance between
gathering necessary information and avoiding excessive back-and-forth."
```

The tool accepts:
- `question` (required): A clear, specific question
- `options` (optional): Array of 2-5 options for the user to choose from (saves typing)

**When to ask vs proceed (from system prompt rules):**
- "You are only allowed to ask the user questions using the ask_followup_question tool. Use this tool only when you need additional details to complete a task"
- "If you can use the available tools to avoid having to ask the user questions, you should do so"
- "Do not ask for more information than necessary"
- "Your goal is to try to accomplish the user's task, NOT engage in a back and forth conversation"
- If a required tool parameter is missing and can't be inferred: "ask the user to provide the missing parameters"

**YOLO mode:** When enabled, the system prompt changes to: "Use your available tools and apply your best judgment to accomplish the task without asking the user any followup questions, making reasonable assumptions from the provided context." The ask_followup_question tool is hidden entirely.

**Plan Mode:** For complex/ambiguous tasks, Cline has a separate PLAN MODE:
- Goal is "information gathering, asking questions, and architecting a solution"
- Model uses read_file, search_files, and ask_followup_question to understand the task
- Creates a detailed plan before switching to ACT MODE
- Described as "a brainstorming session where you can discuss the task"

**Missing context handling:** The system prompt instructs: "if the user mentions a file that may be in an outside directory like the Desktop, you should use the list_files tool to list the files in the Desktop and check if the file they are talking about is there, rather than asking the user to provide the file path themselves."

**Typo/intent detection:** None. The LLM handles this natively.

### 3. Cursor

**Source:** Official docs at cursor.com/docs

**Prompt preprocessing:** None documented. User input goes directly to the agent.

**Clarifying questions:** Cursor's agent has an "Ask questions" tool described as: "Ask clarifying questions during a task. While waiting for your response, the agent continues reading files, making edits, or running commands." This is notable -- Cursor's agent asks questions non-blocking and continues working in parallel.

**Plan Mode:** Cursor has a dedicated Plan Mode that:
1. Agent asks clarifying questions to understand requirements
2. Researches codebase for relevant context
3. Creates a comprehensive implementation plan
4. User reviews and edits through chat or markdown
5. Click to build when satisfied

**Complexity detection:** Cursor "suggests [Plan Mode] automatically when you type keywords that indicate complex tasks." This is the closest thing to a prompt complexity classifier found in any tool -- Cursor analyzes the user's input for complexity-indicating keywords and suggests switching modes.

**Context gathering (replacing prompt rewriting):** Cursor 2.0 removed manual context attachment (@Web, @Git). The agent now "self-gathers this context without manual attachment," independently locating relevant files. Users can still use @ mentions for specific files/symbols.

**Prompt rewriting:** None. The docs emphasize: "speak naturally, include technical details like file and function names."

### 4. Aider

**Source:** GitHub repo source code (aider/coders/base_coder.py, aider/commands.py)

**Prompt preprocessing (actual code):**
```python
def preproc_user_input(self, inp):
    if not inp:
        return
    if self.commands.is_command(inp):
        return self.commands.run(inp)
    self.check_for_file_mentions(inp)
    inp = self.check_for_urls(inp)
    return inp
```

This is the most structured preprocessing found in any tool, but it is purely structural:
1. Check if input is a command (starts with `/`)
2. **File mention detection:** Regex-matches filenames in the user's text, then asks "Add file to the chat?" -- automatically resolving missing context
3. **URL detection:** Regex-matches URLs in the text, asks "Add URL to the chat?", then fetches and appends the content
4. **Identifier matching:** `get_ident_mentions()` splits text on non-alphanumeric characters, builds identifier set, correlates with filenames in the repo

**No semantic preprocessing.** No intent detection, no prompt rewriting, no typo correction. All of these are structural pattern-matching operations.

**Clarifying questions:** Aider does NOT ask clarifying questions. It "favors autonomous context gathering and proceeding with modifications once sufficient information is detected." The philosophy is: gather context automatically, attempt the change, let the user correct if wrong.

**Mode switching for ambiguity:** Aider has `/ask` mode (questions without edits), `/architect` mode (planning), and `/code` mode (editing). Users manually select the appropriate mode.

### 5. OpenAI Codex CLI

**Source:** GitHub repo (codex-rs/core/src/)

**Prompt preprocessing:** Based on the source code analysis:
- `contextual_user_message.rs`: Handles structured context injection (AGENTS.md, environment context, skill payloads, subagent notifications) via XML-tagged fragments. This is context enrichment, not prompt rewriting.
- `mentions.rs`: Parses `@` and `$` mentions in user text to resolve file paths, skills, and plugin references
- `safety.rs`: Safety classification of tool actions (auto-approve, ask user, reject) -- but this applies to tool execution, not prompt interpretation

**No prompt rewriting or intent detection found.** The Codex CLI follows the same pattern as Claude Code: enrich context, let the model interpret, course-correct.

**Agent roles:** Codex supports configurable agent roles (via `role.rs`) that change the system prompt and available tools, but role selection is manual, not automatic based on prompt analysis.

### 6. GitHub Copilot

**Source:** Official blog posts, documentation

**Prompt preprocessing:** Copilot's agent mode "infers additional tasks that were not specified, but are also necessary for the primary request to work." This is post-interpretation task expansion by the LLM, not preprocessing.

**Clarifying questions:** Not explicitly documented. Copilot focuses on autonomous execution with "self-healing capabilities" and iterative processing.

**Context gathering:** Copilot Chat uses the current file, open tabs, and workspace context. It does not rewrite or enhance the user's prompt.

### 7. Sourcegraph Cody

**Source:** Blog posts, repository analysis

**Prompt preprocessing for completions (not chat):** Cody's code completion pipeline has actual preprocessing:
1. **Planning phase:** Classifies the request as single-line vs multi-line using "a mixture of language heuristics (by looking at indentation and specific symbols) and precise language information (guided by Tree-sitter)"
2. **Syntactic context analysis:** Uses Tree-sitter to "categorize the autocomplete request into different syntactic actions like implementing a function body, writing a docstring, or implementing a method call"
3. **Context retrieval:** Uses Jaccard similarity search over recently-accessed files
4. **Prompt construction:** Careful formatting (XML tags over markdown, whitespace trimming, fill-in-the-middle)

**For chat:** Cody uses embedding-based semantic search to find relevant code context. The user's query is embedded and compared against codebase embeddings. This is context enrichment, not prompt rewriting.

**Intent detection:** Cody has an `intent-detector` module in its shared library, though the exact implementation is in the server-side Sourcegraph API. The intent detector appears to classify between code completion vs chat vs search intents.

### 8. AutoGen (Microsoft)

**Source:** GitHub repo source code

**Prompt preprocessing:** None. "Messages flow directly into the model context without validation, normalization, or intent classification before model inference begins."

**The docs state:** "Responsibility for handling ambiguous user input falls entirely on the system prompt and model capability rather than architectural features within the agent itself."

### 9. Continue.dev

**Source:** GitHub repo source code

**Prompt preprocessing:** Template-based message formatting, prompt length management (pruning from top), and tool override system. No semantic preprocessing, intent detection, or prompt rewriting.

---

## Cross-Cutting Patterns

### Pattern 1: "Let the LLM Figure It Out" (Universal)

Every single tool relies on the LLM's native ability to interpret vague prompts. None of them preprocess, rewrite, or classify prompts before the main model sees them. The reasoning is:
- Modern LLMs are already excellent at interpreting vague language
- Adding a preprocessing step would add latency
- A preprocessing model might misinterpret the user's intent in ways the main model wouldn't

### Pattern 2: Tool-Based Clarification (Cline, Cursor, Claude Code)

The primary mechanism for handling ambiguity is giving the LLM a tool to ask the user questions. The LLM decides when to use it based on system prompt instructions. Key design decisions:

| Tool | Ask Tool Name | Blocks Execution? | Has Options? |
|------|--------------|-------------------|--------------|
| Claude Code | AskUserQuestion | Yes | No (documented) |
| Cline | ask_followup_question | Yes | Yes (2-5 options) |
| Cursor | Ask questions | **No** (continues working) | Unknown |
| Aider | None | N/A | N/A |

### Pattern 3: Context Enrichment Over Prompt Improvement (Universal)

Instead of improving the prompt, all tools improve the context:
- **File mention detection:** Aider, Codex CLI (@ mentions)
- **Automatic file/codebase search:** Claude Code, Cursor, Cline
- **Embedding-based retrieval:** Cody
- **Project configuration files:** CLAUDE.md, AGENTS.md, .cursorrules
- **Environment context injection:** OS info, working directory, open files, git state

### Pattern 4: Mode-Based Complexity Routing (Cline, Cursor, Claude Code)

Rather than automatically detecting prompt complexity, tools offer manual mode switching:
- **Plan Mode:** Explore and plan before coding (Cline, Cursor, Claude Code)
- **Ask Mode:** Question-only, no edits (Aider)
- **YOLO Mode:** No clarification, maximum autonomy (Cline)

**Cursor is the only tool with any automatic complexity detection** -- it suggests Plan Mode "automatically when you type keywords that indicate complex tasks."

### Pattern 5: System Prompt Instructions for Clarification (Cline, Claude Code)

The decision of when to ask vs proceed is encoded in the system prompt, not in code. Cline's rules are the most explicit:
1. Use available tools to find information before asking the user
2. Only ask when you genuinely need additional details
3. Don't ask about optional parameters
4. If a required parameter is missing and can't be inferred, then ask
5. Goal is task completion, not conversation

### Pattern 6: No Typo Correction or Spell Checking (Universal)

No tool implements typo correction or spell-checking on user input. The LLMs handle typos natively through their language understanding.

---

## What Does NOT Exist in Production

Based on exhaustive research across all major tools:

1. **No "prompt quality classifier"** -- No tool scores prompt quality or ambiguity before processing
2. **No "complexity router"** that automatically routes simple vs complex vs ambiguous prompts to different handling paths
3. **No smaller/faster model preprocessing** prompts before the main model sees them
4. **No prompt rewriting** -- User text is never modified, enhanced, or expanded before the model sees it
5. **No typo correction** layer
6. **No intent disambiguation** system separate from the LLM itself
7. **No query expansion** (like search engines do with short queries)
8. **No prompt template injection** based on detected intent category

---

## Academic Research

Limited academic work exists specifically on prompt rewriting for code agents. The research landscape focuses on:
- **Query rewriting for RAG systems** (not code agents) -- expanding queries for better retrieval
- **Evaluation bias** in LLM-as-judge systems
- **Instruction tuning** to make models better at following vague instructions
- **Self-ask / chain-of-thought** decomposition (but this happens inside the model, not as preprocessing)

No published system implements a production "prompt preprocessor" for coding agents.

---

## Implications for Our Agent Implementation

### What the industry consensus tells us:

1. **Don't build a prompt preprocessor.** The industry has voted unanimously: let the LLM interpret the prompt directly. Preprocessing adds latency and risks misinterpreting user intent.

2. **DO provide an ask-user tool.** Every tool that handles ambiguity well gives the LLM a way to ask the user questions. Key design decisions:
   - Should it block execution or allow parallel work? (Cursor allows parallel)
   - Should it offer clickable options? (Cline does, saves typing)
   - How many questions before it becomes annoying? (Cline says "judiciously")

3. **DO invest heavily in context enrichment.** The best way to handle "fix the bug" is to automatically gather:
   - Recent errors/stack traces
   - Changed files (git diff)
   - Test failures
   - Active ticket context (from Jira in our case)
   - Build status (from Bamboo)
   - Quality issues (from Sonar)

4. **DO provide a Plan Mode.** For truly ambiguous requests, a planning phase that gathers info before acting is the industry standard approach.

5. **Consider Cursor-style keyword detection for mode suggestion.** This is the only form of "prompt analysis" found in production. Detecting keywords like "refactor," "redesign," "migrate" to suggest Plan Mode could be valuable.

6. **Design the system prompt carefully.** The system prompt is the primary mechanism for controlling when the agent asks vs proceeds. Invest in clear, tested instructions.

### What we could innovate on:

Since no tool does any of the following, these represent potential differentiators:

1. **Workflow-aware context injection:** When user says "fix the bug," automatically inject: current Jira ticket details, latest build failures, recent Sonar issues. No other tool has domain-specific context enrichment at this level.

2. **Active ticket context as disambiguation:** If user says "fix it" and there's exactly one active Jira ticket assigned to them, auto-resolve the ambiguity. If multiple tickets, ask which one.

3. **Error-context pre-fetching:** When the agent detects error-related keywords ("broken," "failing," "crash"), automatically fetch recent build logs, test results, and error reports before the LLM starts reasoning.

4. **Lightweight prompt classification for telemetry:** Even if we don't route on it, classifying prompts as "clear" vs "vague" for analytics would help us understand user behavior and improve the product over time.
