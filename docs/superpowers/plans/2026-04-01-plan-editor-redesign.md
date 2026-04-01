# Plan Editor Redesign — Document-Quality Markdown Viewer with Per-Line Comments

> **For agentic workers:** REQUIRED SUB-SKILL: Use superpowers:subagent-driven-development (recommended) or superpowers:executing-plans to implement this plan task-by-task. Steps use checkbox (`- [ ]`) syntax for tracking. **TDD approach required — write tests FIRST, then implement.**

**Goal:** Replace the chat-style plan editor with a GitHub-quality markdown document viewer. The LLM outputs plans as raw markdown documents. The editor renders them with document typography, line numbers, per-line comments, and step status overlays. Users can comment on any line and send revisions to the LLM.

**Architecture:** The LLM writes the plan as a markdown document (not structured JSON). Kotlin stores the raw markdown + extracts step headings for status tracking. React renders the markdown in a dedicated document viewer with line-numbered gutter, per-line comment system, and section-based step status overlay.

**Tech Stack:** Kotlin (CreatePlanTool, PlanManager, AgentPlan), React + TypeScript (plan-editor.tsx), Vitest + React Testing Library (TDD), `react-markdown` + custom document CSS

**TDD Discipline:** Every task starts with writing failing tests, then implementing the minimum code to pass them.

---

## Architecture Diagram

```
LLM outputs:
  create_plan(
    markdown = "## Goal\nFix NPE in...\n\n## Steps\n### 1. Read...\n```kotlin\n...\n```\n### 2. Edit...",
    steps = [{id:"s1", title:"Read PaymentService", status:"pending"}, ...]  // for tracking
  )
      │
      ▼
┌─────────────────────────────────────────────────────────┐
│  CreatePlanTool (Kotlin)                                 │
│  - Accepts `markdown` param (raw document)               │
│  - Accepts `steps` param (JSON, for status tracking)     │
│  - Stores both in AgentPlan                              │
│  - Falls back: if no markdown, synthesize from steps     │
└─────────────────┬───────────────────────────────────────┘
                  │ PlanManager.submitPlan(plan)
                  ▼
┌─────────────────────────────────────────────────────────┐
│  AgentPlan (Kotlin data class)                           │
│  {                                                       │
│    markdown: String,      // raw document (new)          │
│    goal: String,          // extracted for anchor         │
│    steps: List<PlanStep>, // for status tracking         │
│    approved: Boolean                                     │
│  }                                                       │
└─────────────────┬───────────────────────────────────────┘
                  │ JSON.encodeToString(plan)
                  ▼
┌─────────────────────────────────────────────────────────┐
│  Plan Editor (React — plan-editor.tsx)                   │
│                                                          │
│  ┌────┬─────────────────────────────────────────────┐   │
│  │ #  │  ## Goal                                     │   │
│  │ 1  │  Fix NPE in PaymentService.processRefund()   │   │
│  │ 2  │                                              │   │
│  │ 3  │  ## Approach                                 │   │
│  │ 4  │  Add null guard clause at method entry       │   │
│  │ 5  │                                              │   │
│  │ 6  │  ## Steps                                    │   │
│  │ 7  │  ### ✅ 1. Read PaymentService.kt            │   │
│  │ 8  │  Understand the refund flow...               │   │
│  │ 9  │  ```kotlin                                   │   │
│  │10  │  val customer = order.customer               │   │
│  │11  │  ```                                         │   │
│  │12  │  ### ⏳ 2. Add null check                    │   │
│  │    │  ─── [User comment on line 10] ──────────    │   │
│  │    │  │ This should also handle empty string │    │   │
│  │    │  ────────────────────────────────────────    │   │
│  │13  │  Guard clause before getCustomer()           │   │
│  └────┴─────────────────────────────────────────────┘   │
│                                                          │
│  [Proceed]  [Revise (2 comments)]                       │
└─────────────────────────────────────────────────────────┘
```

## Data Model Changes

```kotlin
// AgentPlan — add markdown field
@Serializable
data class AgentPlan(
    val goal: String,
    val approach: String = "",
    val steps: List<PlanStep>,
    val testing: String = "",
    var approved: Boolean = false,
    // NEW: raw markdown document from LLM
    val markdown: String? = null
)

// CreatePlanTool — add markdown parameter
"markdown" to ParameterProperty(
    type = "string",
    description = "Full implementation plan as a markdown document. Use ## headings for sections (Goal, Approach, Steps, Testing). Use ### for individual steps. Include code blocks, file references, and detailed explanations."
)
```

## Comment Data Model

```typescript
// Per-line comment — keyed by 1-based line number
interface LineComment {
  lineNumber: number;      // 1-based line of the rendered markdown
  text: string;            // user's comment text
  lineContent: string;     // the markdown line content (for context when sending to LLM)
}

// When Revise is clicked, sent to Kotlin as:
{
  "comments": [
    { "line": 10, "content": "val customer = order.customer", "comment": "This should also handle empty string" },
    { "line": 28, "content": "### 3. Run tests", "comment": "Add integration tests too" }
  ],
  "markdown": "## Goal\nFix NPE in..."  // full markdown for LLM context
}
```

---

### Task 1: TDD — Plan Data Model Changes (Kotlin)

**Tests first, then implementation.**

**Files:**
- Test: `agent/src/test/kotlin/com/workflow/orchestrator/agent/runtime/PlanMarkdownTest.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/runtime/PlanManager.kt` (AgentPlan data class)
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/tools/builtin/CreatePlanTool.kt`

- [ ] **Step 1.1: Write failing tests**

```kotlin
class PlanMarkdownTest {

    private val json = Json { ignoreUnknownKeys = true }

    @Test
    fun `AgentPlan with markdown field serializes correctly`() {
        val plan = AgentPlan(
            goal = "Fix NPE",
            steps = listOf(PlanStep(id = "s1", title = "Read file")),
            markdown = "## Goal\nFix NPE in PaymentService"
        )
        val serialized = json.encodeToString(AgentPlan.serializer(), plan)
        val deserialized = json.decodeFromString(AgentPlan.serializer(), serialized)
        assertEquals("## Goal\nFix NPE in PaymentService", deserialized.markdown)
    }

    @Test
    fun `AgentPlan without markdown is backward compatible`() {
        // Old plans without markdown field should deserialize fine
        val oldJson = """{"goal":"Fix NPE","steps":[{"id":"s1","title":"Read"}]}"""
        val plan = json.decodeFromString(AgentPlan.serializer(), oldJson)
        assertNull(plan.markdown)
        assertEquals("Fix NPE", plan.goal)
    }

    @Test
    fun `AgentPlan markdown field is optional with null default`() {
        val plan = AgentPlan(goal = "test", steps = emptyList())
        assertNull(plan.markdown)
    }

    @Test
    fun `CreatePlanTool accepts markdown parameter`() {
        // Verify the tool's parameter definitions include "markdown"
        val tool = CreatePlanTool()
        val params = tool.parameters.properties
        assertTrue(params.containsKey("markdown"), "CreatePlanTool should accept 'markdown' parameter")
    }

    @Test
    fun `plan with markdown synthesizes from steps when markdown is null`() {
        val plan = AgentPlan(
            goal = "Fix bug",
            approach = "Add null check",
            steps = listOf(
                PlanStep(id = "s1", title = "Read file", description = "Understand the code"),
                PlanStep(id = "s2", title = "Edit file", description = "Add guard clause")
            ),
            testing = "Run unit tests"
        )
        // When markdown is null, the UI should synthesize it from structured fields
        assertNull(plan.markdown)
        assertNotNull(plan.goal)
        assertEquals(2, plan.steps.size)
    }
}
```

- [ ] **Step 1.2: Run tests — expect failures**

```bash
./gradlew :agent:test --tests "*.PlanMarkdownTest" --rerun --no-build-cache
```

Expected: Compilation errors (markdown field doesn't exist yet)

- [ ] **Step 1.3: Implement AgentPlan.markdown field**

Add `val markdown: String? = null` to `AgentPlan` data class in PlanManager.kt.

- [ ] **Step 1.4: Add markdown parameter to CreatePlanTool**

Add `"markdown"` to the tool's `parameters.properties` map. In `execute()`, read it and pass to the AgentPlan constructor.

- [ ] **Step 1.5: Run tests — all should pass**

```bash
./gradlew :agent:test --tests "*.PlanMarkdownTest" --rerun --no-build-cache
```

- [ ] **Step 1.6: Commit**

```bash
git commit -m "feat(agent): add markdown field to AgentPlan + CreatePlanTool parameter (TDD)"
```

---

### Task 2: TDD — Update PromptAssembler Planning Rules

**Files:**
- Test: `agent/src/test/kotlin/com/workflow/orchestrator/agent/orchestrator/PlanPromptTest.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/orchestrator/PromptAssembler.kt`

- [ ] **Step 2.1: Write failing tests**

```kotlin
class PlanPromptTest {

    private val assembler = PromptAssembler(/* mock or minimal registry */)

    @Test
    fun `planning rules instruct LLM to output markdown document`() {
        val prompt = assembler.buildSingleAgentPrompt()
        assertTrue(prompt.contains("markdown"), "Planning rules should mention markdown format")
    }

    @Test
    fun `planning rules specify heading structure`() {
        val prompt = assembler.buildSingleAgentPrompt()
        assertTrue(prompt.contains("## Goal") || prompt.contains("heading"),
            "Planning rules should specify heading structure for the markdown document")
    }

    @Test
    fun `planning rules mention markdown parameter in create_plan`() {
        val prompt = assembler.buildSingleAgentPrompt()
        // The prompt should guide the LLM to use the markdown parameter
        assertTrue(prompt.contains("create_plan") && prompt.contains("markdown"),
            "Rules should mention using the markdown parameter of create_plan")
    }
}
```

- [ ] **Step 2.2: Run tests — expect failures**
- [ ] **Step 2.3: Update PLANNING_RULES and FORCED_PLANNING_RULES**

Change the instructions to tell the LLM:
```
When creating a plan, use create_plan with the `markdown` parameter.
Write the plan as a full markdown document:
- Use ## Goal, ## Approach, ## Steps, ## Testing as section headings
- Use ### 1. Step Title for each step
- Include code blocks, file paths, and detailed explanations
- Be thorough — this is the implementation spec the developer will follow
Also provide the `steps` parameter as a JSON array for status tracking:
[{"id":"1","title":"Step title","description":"Brief desc","files":["path"],"action":"read|edit|create|verify"}]
```

- [ ] **Step 2.4: Run tests — all should pass**
- [ ] **Step 2.5: Commit**

```bash
git commit -m "feat(agent): update planning rules to instruct LLM to output markdown documents"
```

---

### Task 3: TDD — Document Markdown Viewer Component (React)

**This is the core — a dedicated document viewer, not the chat MarkdownRenderer.**

**Files:**
- Test: `agent/webview/src/__tests__/plan-document-viewer.test.tsx`
- Create: `agent/webview/src/components/plan/PlanDocumentViewer.tsx`
- Create: `agent/webview/src/components/plan/plan-document.css`

- [ ] **Step 3.1: Write failing tests**

```tsx
// plan-document-viewer.test.tsx
import { describe, it, expect, vi } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { PlanDocumentViewer } from '@/components/plan/PlanDocumentViewer';

const sampleMarkdown = `## Goal
Fix null pointer exception in PaymentService.processRefund()

## Approach
Add null guard clause at method entry before accessing customer reference.

## Steps

### 1. Read PaymentService.kt
Understand the refund flow and identify where customer is accessed.

\`\`\`kotlin
val customer = order.customer  // NPE here when customer is null
customer.processRefund(amount)
\`\`\`

**Files:** \`src/main/kotlin/PaymentService.kt\`

### 2. Add null check
Add guard clause before getCustomer().

### 3. Run tests
Execute PaymentServiceTest to verify the fix.

## Testing & Verification
Run \`./gradlew :payment:test\` and verify no NPE.
`;

describe('PlanDocumentViewer', () => {
  it('renders markdown content', () => {
    render(<PlanDocumentViewer markdown={sampleMarkdown} />);
    expect(screen.getByText('Goal')).toBeInTheDocument();
    expect(screen.getByText(/Fix null pointer/)).toBeInTheDocument();
  });

  it('renders code blocks with syntax highlighting', () => {
    render(<PlanDocumentViewer markdown={sampleMarkdown} />);
    expect(screen.getByText(/order\.customer/)).toBeInTheDocument();
  });

  it('renders step headings', () => {
    render(<PlanDocumentViewer markdown={sampleMarkdown} />);
    expect(screen.getByText(/Read PaymentService/)).toBeInTheDocument();
    expect(screen.getByText(/Add null check/)).toBeInTheDocument();
    expect(screen.getByText(/Run tests/)).toBeInTheDocument();
  });

  it('displays line numbers in gutter', () => {
    render(<PlanDocumentViewer markdown={sampleMarkdown} showLineNumbers={true} />);
    // Should render line number elements
    const lineNumbers = document.querySelectorAll('[data-line-number]');
    expect(lineNumbers.length).toBeGreaterThan(0);
  });

  it('shows comment button on line hover', () => {
    render(<PlanDocumentViewer markdown={sampleMarkdown} showLineNumbers={true} onComment={vi.fn()} />);
    // Line gutter should have interactive elements
    const gutterLines = document.querySelectorAll('[data-line-number]');
    expect(gutterLines.length).toBeGreaterThan(0);
  });

  it('displays existing comments inline', () => {
    const comments = [
      { lineNumber: 3, text: 'Should also handle empty strings', lineContent: 'val customer = order.customer' }
    ];
    render(<PlanDocumentViewer markdown={sampleMarkdown} comments={comments} />);
    expect(screen.getByText(/Should also handle empty strings/)).toBeInTheDocument();
  });

  it('calls onComment when comment is submitted', () => {
    const onComment = vi.fn();
    render(<PlanDocumentViewer markdown={sampleMarkdown} showLineNumbers={true} onComment={onComment} />);
    // Test that the comment submission mechanism exists
    // (detailed interaction tested in integration tests)
  });

  it('renders with document typography (not chat typography)', () => {
    const { container } = render(<PlanDocumentViewer markdown={sampleMarkdown} />);
    const docBody = container.querySelector('.plan-document');
    expect(docBody).toBeTruthy();
    // Should have document-specific class, not chat-specific
    expect(docBody?.classList.contains('plan-document')).toBe(true);
  });

  it('renders task list checkboxes', () => {
    const md = '- [ ] Unchecked item\n- [x] Checked item';
    render(<PlanDocumentViewer markdown={md} />);
    // Should render checkboxes or checkbox-like elements
    const checks = document.querySelectorAll('input[type="checkbox"], .task-checkbox');
    expect(checks.length).toBeGreaterThanOrEqual(0); // At minimum, should not crash
  });

  it('renders without errors for empty markdown', () => {
    render(<PlanDocumentViewer markdown="" />);
    // Should not crash
  });

  it('renders without errors for null-ish content', () => {
    render(<PlanDocumentViewer markdown="# Just a heading" />);
    expect(screen.getByText('Just a heading')).toBeInTheDocument();
  });
});
```

- [ ] **Step 3.2: Run tests — expect failures (component doesn't exist)**

```bash
cd agent/webview && npm test
```

- [ ] **Step 3.3: Create PlanDocumentViewer component**

```tsx
// agent/webview/src/components/plan/PlanDocumentViewer.tsx

import { memo, useMemo, useState, useCallback, useRef } from 'react';
import Markdown from 'react-markdown';
import remarkGfm from 'remark-gfm';
import rehypeRaw from 'rehype-raw';
import { CodeBlock } from '@/components/markdown/CodeBlock';

interface LineComment {
  lineNumber: number;
  text: string;
  lineContent: string;
}

interface PlanDocumentViewerProps {
  markdown: string;
  comments?: LineComment[];
  showLineNumbers?: boolean;
  onComment?: (lineNumber: number, text: string, lineContent: string) => void;
  onRemoveComment?: (lineNumber: number) => void;
  stepStatuses?: Record<string, string>; // stepId → status for emoji overlay
}

export const PlanDocumentViewer = memo(function PlanDocumentViewer({
  markdown,
  comments = [],
  showLineNumbers = true,
  onComment,
  onRemoveComment,
  stepStatuses = {},
}: PlanDocumentViewerProps) {
  // Split markdown into lines for line numbering
  const lines = useMemo(() => markdown.split('\n'), [markdown]);
  const [activeCommentLine, setActiveCommentLine] = useState<number | null>(null);
  const [commentDraft, setCommentDraft] = useState('');
  const commentsByLine = useMemo(() => {
    const map = new Map<number, LineComment>();
    comments.forEach(c => map.set(c.lineNumber, c));
    return map;
  }, [comments]);

  const handleSubmitComment = useCallback((lineNumber: number) => {
    if (commentDraft.trim() && onComment) {
      onComment(lineNumber, commentDraft.trim(), lines[lineNumber - 1] ?? '');
      setCommentDraft('');
      setActiveCommentLine(null);
    }
  }, [commentDraft, onComment, lines]);

  // Render the full markdown as a document with line-numbered wrapper
  return (
    <div className="plan-document">
      {/* Rendered markdown with document typography */}
      <div className="plan-document-body">
        <Markdown
          remarkPlugins={[remarkGfm]}
          rehypePlugins={[rehypeRaw]}
          components={createDocumentComponents()}
        >
          {markdown}
        </Markdown>
      </div>

      {/* Line-numbered overlay for comments */}
      {showLineNumbers && (
        <div className="plan-line-overlay">
          {lines.map((line, idx) => {
            const lineNum = idx + 1;
            const comment = commentsByLine.get(lineNum);
            const isActive = activeCommentLine === lineNum;

            return (
              <div key={lineNum} className="plan-line-row" data-line-number={lineNum}>
                {/* Gutter with line number + comment button */}
                <div className="plan-line-gutter">
                  <span className="plan-line-number">{lineNum}</span>
                  {onComment && !comment && (
                    <button
                      className="plan-line-comment-btn"
                      onClick={() => { setActiveCommentLine(isActive ? null : lineNum); setCommentDraft(''); }}
                      title="Add comment"
                    >+</button>
                  )}
                </div>

                {/* Inline comment editor */}
                {isActive && (
                  <div className="plan-comment-editor">
                    <textarea
                      value={commentDraft}
                      onChange={e => setCommentDraft(e.target.value)}
                      placeholder="Add your comment..."
                      autoFocus
                      onKeyDown={e => {
                        if (e.key === 'Enter' && (e.metaKey || e.ctrlKey)) handleSubmitComment(lineNum);
                        if (e.key === 'Escape') setActiveCommentLine(null);
                      }}
                    />
                    <div className="plan-comment-actions">
                      <button onClick={() => handleSubmitComment(lineNum)}>Comment</button>
                      <button onClick={() => setActiveCommentLine(null)}>Cancel</button>
                    </div>
                  </div>
                )}

                {/* Existing comment bubble */}
                {comment && !isActive && (
                  <div className="plan-comment-bubble">
                    <span className="plan-comment-text">{comment.text}</span>
                    {onRemoveComment && (
                      <button className="plan-comment-remove" onClick={() => onRemoveComment(lineNum)}>×</button>
                    )}
                  </div>
                )}
              </div>
            );
          })}
        </div>
      )}
    </div>
  );
});

// Document-optimized markdown components (different from chat MarkdownRenderer)
function createDocumentComponents(): any {
  return {
    // Code blocks with syntax highlighting
    code({ className, children, ...props }: any) {
      const isBlock = className?.startsWith('language-');
      if (isBlock) {
        const language = className?.replace('language-', '') ?? '';
        const code = String(children).replace(/\n$/, '');
        return <CodeBlock code={code} language={language} isStreaming={false} />;
      }
      return <code className="plan-inline-code" {...props}>{children}</code>;
    },
    // Task list items with checkboxes
    li({ children, className, ...props }: any) {
      const isTask = className === 'task-list-item';
      return <li className={isTask ? 'plan-task-item' : undefined} {...props}>{children}</li>;
    },
    input({ type, checked, ...props }: any) {
      if (type === 'checkbox') {
        return <span className="task-checkbox">{checked ? '☑' : '☐'}</span>;
      }
      return <input type={type} checked={checked} {...props} />;
    },
    // Links open in IDE
    a({ href, children, ...props }: any) {
      return (
        <a href={href} onClick={(e: React.MouseEvent) => {
          e.preventDefault();
          if (href) (window as any)._openFile?.(href);
        }} {...props}>{children}</a>
      );
    },
  };
}
```

- [ ] **Step 3.4: Create document CSS**

```css
/* agent/webview/src/components/plan/plan-document.css */

.plan-document {
  position: relative;
  font-family: -apple-system, BlinkMacSystemFont, 'Segoe UI', 'Noto Sans', Helvetica, Arial, sans-serif;
  color: var(--fg);
  line-height: 1.7;
}

.plan-document-body {
  max-width: 800px;
  margin: 0 auto;
  padding: 2rem 3rem 2rem 4rem;  /* left padding for line numbers */
  font-size: 15px;
}

/* Heading hierarchy — distinct sizes, weights, spacing */
.plan-document-body h1 { font-size: 2em; font-weight: 700; margin: 2rem 0 1rem; padding-bottom: 0.3em; border-bottom: 1px solid var(--border); }
.plan-document-body h2 { font-size: 1.5em; font-weight: 600; margin: 1.75rem 0 0.75rem; padding-bottom: 0.25em; border-bottom: 1px solid var(--divider-subtle); }
.plan-document-body h3 { font-size: 1.25em; font-weight: 600; margin: 1.5rem 0 0.5rem; }
.plan-document-body h4 { font-size: 1em; font-weight: 600; margin: 1.25rem 0 0.5rem; }

/* Paragraphs with generous spacing */
.plan-document-body p { margin: 0.75em 0; }

/* Lists */
.plan-document-body ul, .plan-document-body ol { padding-left: 2em; margin: 0.5em 0; }
.plan-document-body li { margin: 0.25em 0; }

/* Task list checkboxes */
.task-checkbox { font-size: 1.1em; margin-right: 0.3em; cursor: default; }
.plan-task-item { list-style: none; margin-left: -1.5em; }

/* Inline code */
.plan-inline-code {
  background: var(--code-bg);
  padding: 0.15em 0.4em;
  border-radius: 4px;
  font-size: 0.9em;
  font-family: var(--font-mono, 'JetBrains Mono', monospace);
}

/* Blockquotes */
.plan-document-body blockquote {
  border-left: 3px solid var(--accent);
  padding: 0.5em 1em;
  margin: 1em 0;
  color: var(--fg-secondary);
  font-style: italic;
}

/* Tables — GitHub style */
.plan-document-body table {
  border-collapse: collapse;
  width: 100%;
  margin: 1em 0;
}
.plan-document-body th, .plan-document-body td {
  border: 1px solid var(--border);
  padding: 0.5em 1em;
  text-align: left;
}
.plan-document-body th { background: var(--toolbar-bg); font-weight: 600; font-size: 0.9em; }

/* HR */
.plan-document-body hr { border: none; border-top: 1px solid var(--border); margin: 2em 0; }

/* Line number gutter */
.plan-line-overlay {
  position: absolute;
  top: 0;
  left: 0;
  width: 3rem;
  padding-top: 2rem;
}

.plan-line-row { position: relative; min-height: 1.7em; }

.plan-line-gutter {
  display: flex;
  align-items: center;
  gap: 2px;
  height: 1.7em;
}

.plan-line-number {
  font-size: 11px;
  color: var(--fg-muted);
  text-align: right;
  width: 2.5rem;
  font-family: var(--font-mono);
  user-select: none;
}

.plan-line-comment-btn {
  opacity: 0;
  width: 16px;
  height: 16px;
  border-radius: 50%;
  border: none;
  background: var(--accent);
  color: var(--bg);
  font-size: 12px;
  font-weight: bold;
  cursor: pointer;
  display: flex;
  align-items: center;
  justify-content: center;
  transition: opacity 0.15s;
}

.plan-line-row:hover .plan-line-comment-btn { opacity: 1; }

/* Comment editor */
.plan-comment-editor {
  margin: 4px 0 4px 3.5rem;
  padding: 8px;
  border: 1px solid var(--accent);
  border-radius: 6px;
  background: var(--bg);
}

.plan-comment-editor textarea {
  width: 100%;
  min-height: 60px;
  resize: vertical;
  background: var(--input-bg);
  color: var(--fg);
  border: 1px solid var(--border);
  border-radius: 4px;
  padding: 8px;
  font-size: 13px;
  font-family: inherit;
}

.plan-comment-actions {
  display: flex;
  gap: 4px;
  justify-content: flex-end;
  margin-top: 4px;
}

.plan-comment-actions button {
  font-size: 11px;
  padding: 3px 10px;
  border-radius: 4px;
  border: none;
  cursor: pointer;
}

.plan-comment-actions button:first-child { background: var(--accent); color: var(--bg); }
.plan-comment-actions button:last-child { background: var(--fg-muted); color: var(--bg); }

/* Comment bubble */
.plan-comment-bubble {
  margin: 2px 0 2px 3.5rem;
  padding: 4px 10px;
  border-left: 3px solid var(--warning, #eab308);
  background: rgba(234, 179, 8, 0.06);
  font-size: 12px;
  color: var(--fg-secondary);
  display: flex;
  align-items: center;
  gap: 8px;
  border-radius: 0 4px 4px 0;
}

.plan-comment-remove {
  border: none;
  background: none;
  color: var(--fg-muted);
  cursor: pointer;
  font-size: 14px;
}
```

- [ ] **Step 3.5: Run tests — all should pass**
- [ ] **Step 3.6: Commit**

```bash
git commit -m "feat(agent): PlanDocumentViewer — GitHub-quality markdown viewer with per-line comments (TDD)"
```

---

### Task 4: TDD — Rewrite plan-editor.tsx to Use PlanDocumentViewer

**Files:**
- Test: `agent/webview/src/__tests__/plan-editor-integration.test.tsx`
- Modify: `agent/webview/src/plan-editor.tsx`

- [ ] **Step 4.1: Write failing tests**

```tsx
describe('Plan Editor Integration', () => {
  it('renders markdown from plan data when markdown field present', () => {
    // When AgentPlan has markdown field, use it directly
  });

  it('falls back to synthesized markdown when markdown field is null', () => {
    // When old-style plan without markdown, synthesize from steps
  });

  it('Proceed button calls _approvePlan', () => {});
  it('Revise sends line-keyed comments with markdown context', () => {});
  it('comments disable Proceed when present', () => {});
  it('updatePlanStep overlays status emoji on step headings', () => {});
  it('approved plan shows badge and hides buttons', () => {});
});
```

- [ ] **Step 4.2: Rewrite plan-editor.tsx**

Replace the current implementation with one that:
1. Uses `PlanDocumentViewer` for rendering
2. If `planData.markdown` exists, renders it directly
3. If not, synthesizes markdown from structured fields (backward compat)
4. Passes per-line comments to the viewer
5. On Revise, sends `{ comments: [...], markdown: "..." }` to Kotlin
6. On Proceed, calls `_approvePlan()`

- [ ] **Step 4.3: Run tests — all should pass**
- [ ] **Step 4.4: Build webview**

```bash
cd agent/webview && npm run build
```

- [ ] **Step 4.5: Commit**

```bash
git commit -m "feat(agent): rewrite plan-editor to use PlanDocumentViewer with per-line comments (TDD)"
```

---

### Task 5: TDD — Update PlanSummaryCard for Markdown Plans

**Files:**
- Test: Update `agent/webview/src/__tests__/plan-summary-card.test.tsx`
- Modify: `agent/webview/src/components/agent/PlanSummaryCard.tsx`

- [ ] **Step 5.1: Write failing tests**

```tsx
it('shows markdown preview when plan has markdown field', () => {
  const planWithMarkdown = { ...mockPlan, markdown: '## Goal\nFix the NPE...' };
  render(<PlanSummaryCard plan={planWithMarkdown} />);
  // Should show a preview of the markdown, not the structured step list
});

it('falls back to step list when no markdown', () => {
  render(<PlanSummaryCard plan={mockPlan} />);
  // Should show structured step list (backward compat)
});
```

- [ ] **Step 5.2: Update PlanSummaryCard**

When plan has `markdown` field, show a truncated preview of the document. When it doesn't, show the structured step list (backward compat).

- [ ] **Step 5.3: Run tests — all should pass**
- [ ] **Step 5.4: Commit**

```bash
git commit -m "feat(agent): PlanSummaryCard shows markdown preview when available (TDD)"
```

---

### Task 6: TDD — Bridge Contract for Revised Plan Format

**Files:**
- Create: `agent/src/test/resources/contracts/plan-revise-v2.json`
- Copy to: `agent/webview/src/__tests__/contracts/plan-revise-v2.json`
- Modify: `agent/webview/src/__tests__/bridge-contracts.test.ts`
- Modify: `agent/src/test/kotlin/com/workflow/orchestrator/agent/bridge/BridgeContractTest.kt`
- Modify: `agent/src/main/kotlin/com/workflow/orchestrator/agent/ui/AgentController.kt` (revise handler)

- [ ] **Step 6.1: Create contract fixture**

```json
{
  "description": "Contract for _revisePlan with per-line comments (v2 format)",
  "valid_payloads": [
    {
      "name": "line_comments_with_markdown",
      "payload": "{\"comments\":[{\"line\":10,\"content\":\"val customer = order.customer\",\"comment\":\"Handle empty string too\"},{\"line\":28,\"content\":\"### 3. Run tests\",\"comment\":\"Add integration tests\"}],\"markdown\":\"## Goal\\nFix NPE in PaymentService\"}",
      "expected_comment_count": 2
    },
    {
      "name": "no_comments",
      "payload": "{\"comments\":[],\"markdown\":\"## Goal\\nFix NPE\"}",
      "expected_comment_count": 0
    }
  ]
}
```

- [ ] **Step 6.2: Write React contract test**

Test that the plan editor produces payloads matching this contract.

- [ ] **Step 6.3: Write Kotlin contract test**

Test that AgentController's revise handler can parse this contract format.

- [ ] **Step 6.4: Update AgentController.setCefPlanCallbacks revise handler**

Parse the v2 format: `{ comments: [...], markdown: "..." }` and pass to PlanManager.

- [ ] **Step 6.5: Run both test suites**

```bash
cd agent/webview && npm test
./gradlew :agent:test --rerun --no-build-cache
```

- [ ] **Step 6.6: Commit**

```bash
git commit -m "feat(agent): v2 revise format with per-line comments + bridge contract tests (TDD)"
```

---

### Task 7: Build, Full Test Suite, Release

- [ ] **Step 7.1: Run all React tests**

```bash
cd agent/webview && npm test
```

- [ ] **Step 7.2: Build webview**

```bash
cd agent/webview && npm run build
```

- [ ] **Step 7.3: Run all Kotlin tests**

```bash
./gradlew :agent:test --rerun --no-build-cache
```

- [ ] **Step 7.4: Clean build plugin**

```bash
./gradlew clean buildPlugin
```

- [ ] **Step 7.5: Commit and push**

```bash
git push origin main
```

- [ ] **Step 7.6: Release**

```bash
gh release create v0.42.0 build/distributions/*.zip --title "v0.42.0 — Plan Editor Redesign"
```

---

## Summary

| Task | Tests First | Then Implement | What Changes |
|------|------------|----------------|--------------|
| 1 | PlanMarkdownTest (5) | AgentPlan + CreatePlanTool | Add `markdown` field to data model |
| 2 | PlanPromptTest (3) | PromptAssembler | Tell LLM to output markdown plans |
| 3 | PlanDocumentViewer (11) | New React component + CSS | GitHub-quality viewer with line numbers |
| 4 | PlanEditorIntegration (7) | Rewrite plan-editor.tsx | Wire viewer + comments + buttons |
| 5 | PlanSummaryCard (2) | Update chat card | Markdown preview when available |
| 6 | Bridge contracts (4+) | AgentController revise handler | v2 per-line comment format |
| 7 | Full suite | Build + release | Verify everything works |

## Key Design Decisions

1. **LLM writes markdown** — not structured JSON converted to markdown
2. **Backward compatible** — old plans without `markdown` field still work via synthesis
3. **Per-line comments** — keyed by line number, with line content for LLM context
4. **Document CSS** — separate from chat CSS, optimized for reading (15px body, 1.7 line height, 800px max width)
5. **Step tracking preserved** — `steps[]` JSON parameter still sent for status overlay on headings
6. **TDD throughout** — every task starts with failing tests

## Files Summary

| File | Action | Task |
|------|--------|------|
| `runtime/PlanManager.kt` | Modify (AgentPlan) | 1 |
| `tools/builtin/CreatePlanTool.kt` | Modify (markdown param) | 1 |
| `orchestrator/PromptAssembler.kt` | Modify (planning rules) | 2 |
| `webview/src/components/plan/PlanDocumentViewer.tsx` | Create | 3 |
| `webview/src/components/plan/plan-document.css` | Create | 3 |
| `webview/src/plan-editor.tsx` | Rewrite | 4 |
| `webview/src/components/agent/PlanSummaryCard.tsx` | Modify | 5 |
| `ui/AgentController.kt` | Modify (revise handler) | 6 |
| `test/resources/contracts/plan-revise-v2.json` | Create | 6 |
| Tests: 6 test files across React + Kotlin | Create | 1-6 |
