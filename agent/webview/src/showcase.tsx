import { StrictMode, useState, useEffect, useRef } from 'react';
import { createRoot } from 'react-dom/client';
import './index.css';
import './styles/animations.css';
import { applyShowcaseTheme, getStoredTheme } from './showcase/theme-provider';
import {
  mockMessages, mockStreamingMessage, mockToolCalls,
  mockPlanPending, mockPlanInProgress, mockQuestions,
  mockMentions,
  mockTableData, mockTimelineData, mockProgressData,
  mockOutputData, mockFlowWithGroups,
  mockAnimatedFlow, mockSequenceDiagram,
  // New mocks
  mockAnsiText, mockChartData, mockDiffSource, mockImageSource,
  mockMathLatex, mockMathLatexInline, mockInteractiveHtml,
  mockEditOldLines, mockEditNewLines,
  mockPlanCompleted, mockPlanWithFailure, mockPlanLarge,
  mockApprovalEditMetadata, mockApprovalCommandMetadata, mockApprovalDestructiveMetadata,
  mockPlanEditorData, mockPlanMarkdownHtml,
} from './showcase/mock-data';
import { AgentMessage } from './components/chat/AgentMessage';
import { ToolCallChain } from './components/agent/ToolCallChain';
import { ThinkingView } from './components/agent/ThinkingView';
import { ApprovalView } from './components/agent/ApprovalView';
import { QuestionView } from './components/agent/QuestionView';
import { PlanSummaryCard } from './components/agent/PlanSummaryCard';
import { PlanProgressWidget } from './components/agent/PlanProgressWidget';
import { CompletionCard } from './components/agent/CompletionCard';
import { EditDiffView } from './components/agent/EditDiffView';
import { ProcessInputView } from './components/agent/ProcessInputView';
import { TopBar } from './components/chat/TopBar';
import { SkillBanner } from './components/chat/SkillBanner';
import { DebugPanel } from './components/chat/DebugPanel';
import { InputBar } from './components/input/InputBar';
import { ActionToolbar } from './components/input/ActionToolbar';
import { ContextChip } from './components/input/ContextChip';
import { Loader } from './components/ui/prompt-kit/loader';
import { TextShimmer } from './components/ui/prompt-kit/text-shimmer';
import { Badge } from './components/ui/badge';
import { Button } from './components/ui/button';
import { DataTable } from './components/rich/DataTable';
import { TimelineView } from './components/rich/TimelineView';
import { ProgressView } from './components/rich/ProgressView';
import { CollapsibleOutput } from './components/rich/CollapsibleOutput';
import { FlowDiagram } from './components/rich/FlowDiagram';
import { MermaidDiagram } from './components/rich/MermaidDiagram';
import { AnsiOutput } from './components/rich/AnsiOutput';
import { ChartView } from './components/rich/ChartView';
import { DiffHtml } from './components/rich/DiffHtml';
import { ImageView } from './components/rich/ImageView';
import { InteractiveHtml } from './components/rich/InteractiveHtml';
import { ArtifactRenderer } from './components/rich/ArtifactRenderer';
import { MathBlock } from './components/rich/MathBlock';
import { useChatStore } from './stores/chatStore';
import { useThemeStore } from './stores/themeStore';
import { Sun, Moon } from 'lucide-react';

// ── Mock bridge globals ───────────────────────────────────────────────────────
window._sendMessage = window._sendMessage ?? (() => {});
window._sendMessageWithMentions = window._sendMessageWithMentions ?? (() => {});
window._approvePlan = window._approvePlan ?? (() => alert('Plan approved'));
window._revisePlan = window._revisePlan ?? (() => alert('Plan revised'));
window._cancelTask = window._cancelTask ?? (() => alert('Task cancelled'));
window._searchMentions = window._searchMentions ?? (() => {});
window._questionAnswered = window._questionAnswered ?? ((id: string, json: string) => alert(`Answered ${id}: ${json}`));
window._questionSkipped = window._questionSkipped ?? (() => {});
window._editQuestion = window._editQuestion ?? (() => {});
window._questionsSubmitted = window._questionsSubmitted ?? (() => alert('All questions submitted'));
window._questionsCancelled = window._questionsCancelled ?? (() => {});
window._chatAboutOption = window._chatAboutOption ?? ((_qid: string, label: string, msg: string) => alert(`Chat about "${label}": ${msg}`));
window._newChat = window._newChat ?? (() => alert('New chat'));
window._requestUndo = window._requestUndo ?? (() => alert('Undo'));
window._requestViewTrace = window._requestViewTrace ?? (() => alert('View traces'));
window._openSettings = window._openSettings ?? (() => alert('Settings'));
window._changeModel = window._changeModel ?? ((id: string) => alert(`Model: ${id}`));
window._togglePlanMode = window._togglePlanMode ?? ((v: boolean) => alert(`Plan mode: ${v}`));
window._activateSkill = window._activateSkill ?? ((name: string) => alert(`Skill: ${name}`));
window._deactivateSkill = window._deactivateSkill ?? (() => alert('Skill deactivated'));
window._navigateToFile = window._navigateToFile ?? ((path: string) => alert(`Navigate: ${path}`));
window._openInEditorTab = window._openInEditorTab ?? (() => alert('Open in editor tab'));
window._acceptDiffHunk = window._acceptDiffHunk ?? (() => alert('Hunk accepted'));

// ── Shared primitives ─────────────────────────────────────────────────────────

function Section({ title, children, description }: { title: string; children: React.ReactNode; description?: string }) {
  return (
    <div className="mb-8">
      <h2 className="text-lg font-semibold mb-1 pb-1 border-b" style={{ color: 'var(--fg)', borderColor: 'var(--border)' }}>
        {title}
      </h2>
      {description && (
        <p className="text-[11px] mb-3" style={{ color: 'var(--fg-muted)' }}>{description}</p>
      )}
      <div className="space-y-3 mt-3">{children}</div>
    </div>
  );
}

function SubLabel({ text }: { text: string }) {
  return <p className="text-[10px] mt-1" style={{ color: 'var(--fg-muted)' }}>{text}</p>;
}

type TabId = 'components' | 'plan' | 'chat-flow' | 'plan-editor';

function TabBar({ active, onChange }: { active: TabId; onChange: (t: TabId) => void }) {
  const tabs: { id: TabId; label: string }[] = [
    { id: 'components', label: 'Components' },
    { id: 'plan', label: 'Plan' },
    { id: 'chat-flow', label: 'Chat Flow' },
    { id: 'plan-editor', label: 'Plan Editor' },
  ];
  return (
    <div
      className="flex gap-1 mb-8 border-b"
      style={{ borderColor: 'var(--border)' }}
    >
      {tabs.map(t => (
        <button
          key={t.id}
          onClick={() => onChange(t.id)}
          className="px-4 py-2 text-[13px] font-medium transition-colors relative"
          style={{
            color: active === t.id ? 'var(--accent, #3b82f6)' : 'var(--fg-secondary)',
            borderBottom: active === t.id ? '2px solid var(--accent, #3b82f6)' : '2px solid transparent',
            marginBottom: '-1px',
            background: 'transparent',
            border: 'none',
            borderBottomWidth: '2px',
            borderBottomStyle: 'solid',
            borderBottomColor: active === t.id ? 'var(--accent, #3b82f6)' : 'transparent',
            cursor: 'pointer',
          }}
        >
          {t.label}
        </button>
      ))}
    </div>
  );
}

// ── Components Tab ────────────────────────────────────────────────────────────

function ComponentsTab() {
  return (
    <>
      {/* ── Top Bar ── */}
      <Section title="Top Bar" description="Token budget progress bar, approval indicator, debug toggle, and New Chat button.">
        <div className="overflow-hidden rounded-lg border" style={{ borderColor: 'var(--border)' }}>
          <TopBar />
        </div>
        <SubLabel text="Token budget color: <80% normal · 80% warning · 88%+ error · 97%+ error + pulse" />
      </Section>

      {/* ── Skill Banner ── */}
      <Section title="Skill Banner" description="Shown below TopBar when a skill is active. Includes skill name and Deactivate button.">
        <div className="overflow-hidden rounded-lg border" style={{ borderColor: 'var(--border)' }}>
          <SkillBanner />
        </div>
        <SubLabel text="Mock store initialized with skillBanner='systematic-debugging'" />
      </Section>

      {/* ── Chat Input ── */}
      <Section title="Chat Input" description="Auto-expanding textarea with mention/ticket/skill dropdowns and glassmorphic glow on focus.">
        <div className="border rounded-lg overflow-hidden" style={{ borderColor: 'var(--border)' }}>
          <InputBar />
        </div>
        <SubLabel text="Type @ for mentions, # for Jira tickets, / for skills. Model/Plan/Skills chips at bottom-left." />
      </Section>

      {/* ── Action Toolbar ── */}
      <Section title="Action Toolbar">
        <div className="border rounded-lg p-2" style={{ borderColor: 'var(--border)', backgroundColor: 'var(--tool-bg)' }}>
          <SubLabel text="Hovered state (shows all actions):" />
          <ActionToolbar isHovered={true} />
        </div>
      </Section>

      {/* ── Context Chips ── */}
      <Section title="Context Chips" description="Displayed in the input bar for active @mentions.">
        <div className="flex flex-wrap gap-2">
          {mockMentions.map((m, i) => (
            <ContextChip key={i} mention={m} onRemove={() => alert(`Remove: ${m.label}`)} />
          ))}
        </div>
      </Section>

      {/* ── Working Indicator ── */}
      <Section title="Working Indicator">
        <div className="flex items-center gap-2 px-2 py-2 border rounded-lg" style={{ borderColor: 'var(--border)' }}>
          <Loader variant="wave" size="sm" />
          <TextShimmer duration={3} className="text-[12px]">
            git blame says it was me all along...
          </TextShimmer>
        </div>
        <SubLabel text="Shown while agent is busy. Rotates through 72 phrases every 3 seconds." />
      </Section>

      {/* ── Messages ── */}
      <Section title="Messages">
        {mockMessages.map(msg => (
          <AgentMessage key={msg.ts} message={msg} />
        ))}
        <SubLabel text="Streaming message:" />
        <AgentMessage message={mockStreamingMessage} isStreaming={true} />
      </Section>

      {/* ── Tool Calls ── */}
      <Section title="Tool Calls" description="Sequential tool call chain with status badges and durations.">
        <ToolCallChain toolCalls={mockToolCalls} />
      </Section>

      {/* ── Thinking / Reasoning ── */}
      <Section title="Thinking / Reasoning">
        <ThinkingView content="Let me analyze the codebase structure to understand the authentication flow..." isStreaming={true} />
        <ThinkingView content="The authentication module uses JWT tokens with a 24-hour expiry. The refresh token mechanism is implemented in auth-middleware.ts." isStreaming={false} />
      </Section>

      {/* ── Approval Gate ── */}
      <Section title="Approval Gate" description="Risk-colored cards that block tool execution pending user decision.">
        <ApprovalView
          toolName="run_command"
          riskLevel="DESTRUCTIVE"
          title="Approve run_command? (DESTRUCTIVE)"
          description="This will permanently remove all data from the production database."
          metadata={mockApprovalDestructiveMetadata}
          onApprove={() => alert('Approved')}
          onDeny={() => alert('Denied')}
          onAllowForSession={() => alert('Allowed for session')}
        />
        <ApprovalView
          toolName="run_command"
          riskLevel="HIGH"
          title="Approve run_command? (HIGH)"
          description="Run a database migration against the production environment."
          metadata={mockApprovalCommandMetadata}
          onApprove={() => alert('Approved')}
          onDeny={() => alert('Denied')}
          onAllowForSession={() => alert('Allowed for session')}
        />
      </Section>

      {/* ── Completion Card ── */}
      <Section title="Completion Card" description="Shown when agent calls attempt_completion. Supports optional verify command.">
        <CompletionCard
          result="**Authentication refactor complete.** Extracted `TokenService`, added 12 unit tests, updated all 3 API routes, and removed deprecated middleware. Coverage increased from 68% to 84%."
          verifyCommand="./gradlew :auth:test && ./gradlew :auth:check"
        />
        <CompletionCard
          result="Created the feature branch `feature/auth-refactor` and opened PR #47 for review. Two reviewers assigned."
        />
      </Section>

      {/* ── Edit Diff View ── */}
      <Section title="Edit Diff View" description="Inline LCS diff rendered directly in chat (no external library). Three states: pending, applied, rejected.">
        <SubLabel text="Pending:" />
        <EditDiffView
          filePath="src/auth/token-service.ts"
          oldLines={mockEditOldLines}
          newLines={mockEditNewLines}
          accepted={null}
        />
        <SubLabel text="Applied:" />
        <EditDiffView
          filePath="src/auth/token-service.ts"
          oldLines={mockEditOldLines}
          newLines={mockEditNewLines}
          accepted={true}
        />
        <SubLabel text="Rejected:" />
        <EditDiffView
          filePath="src/auth/token-service.ts"
          oldLines={mockEditOldLines}
          newLines={mockEditNewLines}
          accepted={false}
        />
      </Section>

      {/* ── Process Input ── */}
      <Section title="Process Input" description="Inline stdin prompt when a running shell process requires user input.">
        <ProcessInputView
          processId="proc-1"
          description="The migration script is asking whether to proceed with destructive changes."
          prompt="Are you sure you want to DROP and recreate the schema? [y/N]"
          command="npm run db:migrate --env staging"
          onSubmit={(input) => alert(`Sent to stdin: ${JSON.stringify(input)}`)}
        />
      </Section>

      {/* ── Question Wizard ── */}
      <Section title="Question Wizard" description="Supports single-select, multi-select, text input. Has Skip, Chat about this, and Cancel.">
        <QuestionView questions={mockQuestions} activeIndex={0} />
      </Section>

      {/* ── Plan — Pending Review ── */}
      <Section title="Plan — Pending Review">
        <PlanSummaryCard plan={mockPlanPending} />
      </Section>

      {/* ── Plan — In Progress ── */}
      <Section title="Plan — In Progress">
        <PlanProgressWidget plan={mockPlanInProgress} />
      </Section>

      {/* ── Debug Panel ── */}
      <Section title="Debug Panel" description="Collapsible log below the chat. Toggled by the terminal icon in TopBar.">
        <div className="overflow-hidden rounded-lg border" style={{ borderColor: 'var(--border)' }}>
          <DebugPanel />
        </div>
        <SubLabel text="Mock store initialized with debugLogVisible=true and 3 sample entries." />
      </Section>

      {/* ── ANSI Terminal Output ── */}
      <Section title="ANSI Terminal Output" description="Command output with preserved terminal colors via ansi_up. Copy button strips escape codes.">
        <AnsiOutput text={mockAnsiText} />
      </Section>

      {/* ── Chart ── */}
      <Section title="Chart (Chart.js)" description="Animated Chart.js with easeOutQuart per data-point stagger. Lazy-loaded on first render.">
        <ChartView source={mockChartData} />
      </Section>

      {/* ── Diff (diff2html) ── */}
      <Section title="Diff (diff2html)" description="Side-by-side diff rendered by diff2html with Accept/Edit/Reject buttons per hunk. Lazy-loaded.">
        <DiffHtml
          diffSource={mockDiffSource}
          onAcceptHunk={(i, content) => alert(`Accepted hunk ${i}${content ? ' (edited)' : ''}`)}
          onRejectHunk={(i) => alert(`Rejected hunk ${i}`)}
        />
      </Section>

      {/* ── Math (KaTeX) ── */}
      <Section title="Math (KaTeX)" description="LaTeX rendering in block or inline mode. Lazy-loaded on first render.">
        <MathBlock latex={mockMathLatex} displayMode={true} />
        <div className="flex items-center gap-2 text-[13px]" style={{ color: 'var(--fg)' }}>
          <span>Inline:</span>
          <MathBlock latex={mockMathLatexInline} displayMode={false} />
        </div>
      </Section>

      {/* ── Image ── */}
      <Section title="Image" description="Click to zoom in a modal dialog. Shows skeleton while loading.">
        <ImageView imageSource={mockImageSource} />
      </Section>

      {/* ── Interactive HTML ── */}
      <Section title="Interactive HTML" description="Custom HTML/CSS/JS in a sandboxed iframe with injected theme CSS variables.">
        <InteractiveHtml htmlContent={mockInteractiveHtml} height={180} />
      </Section>

      {/* ── Artifact (React Sandbox) ── */}
      <Section title="Artifact (React Sandbox)" description="React component rendered via react-runner in sandboxed iframe. Tests the ArtifactRenderer deadlock fix.">
        <ArtifactRenderer
          title="Module Dependencies"
          source={`
const App = () => {
  const [count, setCount] = useState(0);
  return (
    <Card>
      <CardHeader>
        <CardTitle>Artifact Sandbox Test</CardTitle>
      </CardHeader>
      <CardContent>
        <div className="space-y-3">
          <div className="flex items-center gap-2">
            <Badge variant="success">Rendered</Badge>
            <span className="text-xs" style={{ color: 'var(--fg-muted)' }}>
              If you see this, the deadlock fix works
            </span>
          </div>
          <Progress value={65} variant="default" />
          <button
            className="rounded px-3 py-1 text-xs font-medium"
            style={{ background: 'var(--accent)', color: '#fff' }}
            onClick={() => setCount(c => c + 1)}
          >
            Clicked {count} times
          </button>
        </div>
      </CardContent>
    </Card>
  );
};
export default App;
`}
        />
      </Section>

      {/* ── Data Table ── */}
      <Section title="Data Table" description="Sortable/filterable table. Click column headers to sort.">
        <DataTable tableSource={mockTableData} />
      </Section>

      {/* ── Timeline ── */}
      <Section title="Timeline">
        <TimelineView timelineSource={mockTimelineData} />
      </Section>

      {/* ── Progress ── */}
      <Section title="Progress">
        <ProgressView progressSource={mockProgressData} />
      </Section>

      {/* ── Collapsible Output ── */}
      <Section title="Collapsible Output">
        <CollapsibleOutput outputSource={mockOutputData} />
      </Section>

      {/* ── Animated Flow ── */}
      <Section title="Animated Flow (Request Path)" description="Click Play to step through. Nodes glow on activation; glowing dots show data flow direction.">
        <FlowDiagram source={mockAnimatedFlow} />
      </Section>

      {/* ── Animated Sequence Diagram ── */}
      <Section title="Animated Sequence Diagram" description="Messages reveal one at a time. Click Play or use step controls.">
        <MermaidDiagram source={mockSequenceDiagram} />
      </Section>

      {/* ── Flow Diagram (with Groups) ── */}
      <Section title="Flow Diagram (with Groups)">
        <FlowDiagram source={mockFlowWithGroups} />
      </Section>

      {/* ── Badges ── */}
      <Section title="Badges">
        <div className="flex flex-wrap gap-2">
          <Badge>Default</Badge>
          <Badge variant="secondary">Secondary</Badge>
          <Badge variant="destructive">Destructive</Badge>
          <Badge variant="outline">Outline</Badge>
          {(['file', 'folder', 'symbol', 'tool', 'skill'] as const).map(type => (
            <Badge key={type} variant="secondary" className="text-[11px]">
              {type === 'file' ? '📄' : type === 'folder' ? '📁' : type === 'symbol' ? '#' : type === 'tool' ? '🔧' : '✨'}
              <span className="ml-1">{type}</span>
            </Badge>
          ))}
        </div>
      </Section>

      {/* ── Loader Variants ── */}
      <Section title="Loader Variants">
        <div className="grid grid-cols-3 gap-4">
          {(['circular', 'classic', 'pulse', 'dots', 'typing', 'wave', 'bars', 'terminal', 'text-blink', 'text-shimmer', 'loading-dots'] as const).map(variant => (
            <div key={variant} className="flex items-center gap-2 rounded-lg border px-3 py-2" style={{ borderColor: 'var(--border)' }}>
              <Loader variant={variant} size="sm" text="Loading" />
              <span className="text-[10px]" style={{ color: 'var(--fg-muted)' }}>{variant}</span>
            </div>
          ))}
        </div>
      </Section>
    </>
  );
}

// ── Plan iframe loader ────────────────────────────────────────────────────────

function PlanEditorIframe({ planJson, height = 520 }: { planJson: string; height?: number | string }) {
  const iframeRef = useRef<HTMLIFrameElement>(null);
  const retriesRef = useRef(0);
  const timerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  // Track isDark so we can re-push theme vars whenever the toggle changes
  const isDark = useThemeStore(s => s.isDark);

  // Copy all CSS custom properties from the parent root into the iframe root.
  // The iframe is a separate document — var(--bg), var(--fg) etc. are not
  // inherited across the boundary, so we must mirror them manually.
  const injectTheme = () => {
    const iframeRoot = iframeRef.current?.contentDocument?.documentElement;
    if (!iframeRoot) return;
    const parentStyle = document.documentElement.style;
    for (let i = 0; i < parentStyle.length; i++) {
      const prop = parentStyle.item(i);
      if (prop && prop.startsWith('--')) {
        iframeRoot.style.setProperty(prop, parentStyle.getPropertyValue(prop));
      }
    }
  };

  const tryInject = () => {
    try {
      const win = iframeRef.current?.contentWindow as any;
      if (win?.renderPlan) {
        injectTheme();
        win.renderPlan(planJson);
        retriesRef.current = 0;
        return;
      }
    } catch {
      // cross-origin guard
    }
    // renderPlan not ready yet — React inside iframe still mounting
    if (retriesRef.current < 30) {
      retriesRef.current += 1;
      timerRef.current = setTimeout(tryInject, 100);
    }
  };

  const onLoad = () => {
    retriesRef.current = 0;
    if (timerRef.current) clearTimeout(timerRef.current);
    timerRef.current = setTimeout(tryInject, 50);
  };

  // Re-push theme vars when the showcase theme toggle changes
  useEffect(() => {
    injectTheme();
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [isDark]);

  useEffect(() => {
    return () => { if (timerRef.current) clearTimeout(timerRef.current); };
  }, []);

  return (
    <iframe
      ref={iframeRef}
      src="/plan-editor.html"
      onLoad={onLoad}
      className="w-full rounded-lg border"
      style={{ height, borderColor: 'var(--border)' }}
      title="Plan Editor"
    />
  );
}

// ── Plan Tab ──────────────────────────────────────────────────────────────────

function PlanTab() {
  return (
    <>
      {/* ── Overview ── */}
      <div
        className="mb-8 rounded-lg border p-4 text-[12px] leading-relaxed space-y-2"
        style={{ borderColor: 'var(--border)', backgroundColor: 'var(--tool-bg)', color: 'var(--fg-secondary)' }}
      >
        <p className="font-semibold text-[13px]" style={{ color: 'var(--fg)' }}>There are 3 plan components — only 2 are live.</p>
        <div className="grid gap-2" style={{ gridTemplateColumns: '1fr 1fr 1fr' }}>
          <div className="rounded border p-3" style={{ borderColor: 'var(--accent)', borderLeftWidth: 3 }}>
            <p className="font-medium mb-1" style={{ color: 'var(--accent)' }}>① Chat cards (live)</p>
            <p><code className="text-[11px]">PlanSummaryCard</code> + <code className="text-[11px]">PlanProgressWidget</code></p>
            <p className="mt-1" style={{ color: 'var(--fg-muted)' }}>Rendered inline in the JCEF chat window. Shown automatically when the agent calls <code className="text-[11px]">create_plan</code> or <code className="text-[11px]">update_plan_step</code>.</p>
          </div>
          <div className="rounded border p-3" style={{ borderColor: 'var(--success)', borderLeftWidth: 3 }}>
            <p className="font-medium mb-1" style={{ color: 'var(--success)' }}>② Plan Editor tab (live)</p>
            <p><code className="text-[11px]">AgentPlanEditor</code> + <code className="text-[11px]">plan-editor.tsx</code></p>
            <p className="mt-1" style={{ color: 'var(--fg-muted)' }}>Opens as an IDE editor tab when user clicks "View Implementation Plan". Full markdown view with per-line commenting, Revise and Proceed buttons.</p>
          </div>
          <div className="rounded border p-3" style={{ borderColor: 'var(--error)', borderLeftWidth: 3 }}>
            <p className="font-medium mb-1" style={{ color: 'var(--error)' }}>③ PlanMarkdownRenderer (dead code)</p>
            <p><code className="text-[11px]">PlanMarkdownRenderer.kt</code></p>
            <p className="mt-1" style={{ color: 'var(--fg-muted)' }}>Legacy Swing <code className="text-[11px]">JEditorPane</code> component. Zero usages in the codebase — never wired up after the JCEF migration. Renders the older <code className="text-[11px]">AgentTask</code> model, not the current <code className="text-[11px]">Plan</code> model.</p>
          </div>
        </div>
      </div>

      {/* ── ① Chat Cards ── */}
      <Section
        title="① Chat Cards — PlanSummaryCard + PlanProgressWidget"
        description="These live inside the main JCEF chat window. SummaryCard blocks for approval; ProgressWidget updates step-by-step as the agent works."
      >
        <SubLabel text="PlanSummaryCard — Awaiting Approval (agent just called create_plan):" />
        <PlanSummaryCard plan={mockPlanPending} />

        <SubLabel text="PlanProgressWidget — In Progress (approved, agent executing steps):" />
        <PlanProgressWidget plan={mockPlanInProgress} />

        <SubLabel text="PlanProgressWidget — Large plan, 10 steps:" />
        <PlanProgressWidget plan={mockPlanLarge} />

        <SubLabel text="PlanProgressWidget — With failed step:" />
        <PlanProgressWidget plan={mockPlanWithFailure} />

        <SubLabel text="PlanProgressWidget — All steps completed:" />
        <PlanProgressWidget plan={mockPlanCompleted} />
      </Section>

      {/* ── ② Plan Editor Tab ── */}
      <Section
        title="② Plan Editor Tab — AgentPlanEditor (plan-editor.tsx)"
        description="Opens as an IDE editor tab when user clicks 'View Implementation Plan' in the chat card. Full markdown rendering with per-line inline commenting. Hover any line to add a revision note. Click Revise to send all comments back to the agent."
      >
        <PlanEditorIframe planJson={mockPlanEditorData} />
        <SubLabel text="Mock data includes 2 pre-existing comments to show the annotation UI." />
      </Section>

      {/* ── ③ Legacy PlanMarkdownRenderer ── */}
      <Section
        title="③ Legacy — PlanMarkdownRenderer.kt (dead code)"
        description="A Swing JEditorPane component that generates HTML task lists. Uses the old AgentTask model (id, description, action, target, dependsOn, resultSummary) — predates the current Plan model. Has zero usages in the codebase; the JCEF plan-editor replaced it. Shown below as a reconstructed HTML render of what it would have looked like."
      >
        <InteractiveHtml htmlContent={mockPlanMarkdownHtml} height={260} />
        <div
          className="mt-2 flex items-center gap-1.5 rounded-md px-3 py-2 text-[11px]"
          style={{ backgroundColor: 'color-mix(in srgb, var(--error) 8%, transparent)', color: 'var(--error)', border: '1px solid color-mix(in srgb, var(--error) 20%, transparent)' }}
        >
          <svg width="12" height="12" viewBox="0 0 16 16" fill="none"><path d="M8 1L1 14h14L8 1z" stroke="currentColor" strokeWidth="1.2" strokeLinecap="round" strokeLinejoin="round"/><path d="M8 6v4M8 12v.5" stroke="currentColor" strokeWidth="1.2" strokeLinecap="round"/></svg>
          Dead code — <code className="font-mono">PlanMarkdownRenderer.kt</code> has no callers. Safe to delete.
        </div>
      </Section>

      {/* ── Approval & Execution States ── */}
      <Section
        title="Approval Gates (during plan execution)"
        description="ApprovalView variants shown in chat when the agent performs risky operations as part of a plan."
      >
        <SubLabel text="MEDIUM risk — edit_file with diff:" />
        <ApprovalView
          toolName="edit_file"
          riskLevel="MEDIUM"
          title="Approve edit_file?"
          description="Modify token expiry and add refresh token support."
          metadata={mockApprovalEditMetadata}
          diffContent={mockDiffSource}
          onApprove={() => alert('Edit approved')}
          onDeny={() => alert('Edit denied')}
          onAllowForSession={() => alert('edit_file allowed for session')}
        />
        <SubLabel text="HIGH risk — run_command:" />
        <ApprovalView
          toolName="run_command"
          riskLevel="HIGH"
          title="Approve run_command? (HIGH)"
          description="Run the database migration against the staging environment."
          metadata={mockApprovalCommandMetadata}
          onApprove={() => alert('Approved')}
          onDeny={() => alert('Denied')}
          onAllowForSession={() => alert('Allowed for session')}
        />
        <SubLabel text="DESTRUCTIVE:" />
        <ApprovalView
          toolName="run_command"
          riskLevel="DESTRUCTIVE"
          title="Approve run_command? (DESTRUCTIVE)"
          description="This will permanently drop the users table. This action cannot be undone."
          metadata={mockApprovalDestructiveMetadata}
          onApprove={() => alert('Approved')}
          onDeny={() => alert('Denied')}
          onAllowForSession={() => alert('Allowed for session')}
        />
      </Section>

      {/* ── Edit Diff ── */}
      <Section
        title="Edit Diff — All 3 States"
        description="Inline LCS diff shown in chat when edit_file is called."
      >
        <SubLabel text="Pending:" />
        <EditDiffView filePath="src/auth/token-service.ts" oldLines={mockEditOldLines} newLines={mockEditNewLines} accepted={null} />
        <SubLabel text="Applied:" />
        <EditDiffView filePath="src/auth/token-service.ts" oldLines={mockEditOldLines} newLines={mockEditNewLines} accepted={true} />
        <SubLabel text="Rejected:" />
        <EditDiffView filePath="src/auth/token-service.ts" oldLines={mockEditOldLines} newLines={mockEditNewLines} accepted={false} />
      </Section>

      {/* ── Completion ── */}
      <Section
        title="Completion Card"
        description="Shown when agent calls attempt_completion after all plan steps verified."
      >
        <CompletionCard
          result="**Authentication refactor complete.** Extracted `TokenService`, added 12 unit tests (coverage 68% → 84%), updated all 3 API routes, removed deprecated middleware. No breaking changes."
          verifyCommand="./gradlew :auth:test && ./gradlew :auth:check"
        />
      </Section>
    </>
  );
}

// ── Chat Flow Tab ─────────────────────────────────────────────────────────────
//
// Simulates a complete agent session in the exact same layout as the real
// agent tab: TopBar → SkillBanner → scrollable messages → InputBar → DebugPanel.
// Every component shown here is used in the live agentic chat flow.

const CHAT_FLOW_TOOL_CALLS_1: typeof mockToolCalls = [
  { id: 'cf-1', name: 'read_file', args: '{"path": "src/auth/token-service.ts"}', status: 'COMPLETED', result: 'File read (89 lines)', durationMs: 95 },
  { id: 'cf-2', name: 'search_code', args: '{"query": "jwt.decode", "glob": "**/*.ts"}', status: 'COMPLETED', result: '4 matches in 3 files', durationMs: 210 },
];

const CHAT_FLOW_TOOL_CALLS_2: typeof mockToolCalls = [
  { id: 'cf-3', name: 'edit_file', args: '{"path": "src/auth/token-service.ts"}', status: 'COMPLETED', result: 'File updated (+4/-2 lines)', durationMs: 80 },
  { id: 'cf-4', name: 'diagnostics', args: '{"path": "src/auth/token-service.ts"}', status: 'COMPLETED', result: 'No errors', durationMs: 340 },
  { id: 'cf-5', name: 'run_command', args: '{"command": "npm test -- --testPathPattern=token"}', status: 'RUNNING', result: undefined, durationMs: undefined },
];

const CHAT_FLOW_TOOL_CALLS_3: typeof mockToolCalls = [
  { id: 'cf-6', name: 'bamboo_recent_builds', args: '{"planKey": "AUTH-MAIN"}', status: 'COMPLETED', result: 'Last 5 builds fetched', durationMs: 430 },
  { id: 'cf-7', name: 'sonar_quality_gate', args: '{"projectKey": "com.example:auth"}', status: 'COMPLETED', result: 'PASSED — coverage 84%', durationMs: 290 },
  { id: 'cf-8', name: 'attempt_completion', args: '{}', status: 'COMPLETED', result: 'Task completed', durationMs: 12 },
];

const MSG_USER_1: (typeof mockMessages)[0] = {
  ts: Date.now() - 180000, type: 'SAY', say: 'USER_MESSAGE',
  text: 'Refactor the auth module — extract token validation into a separate service, add tests, and make sure CI passes.',
};

const MSG_AGENT_1: (typeof mockMessages)[0] = {
  ts: Date.now() - 175000, type: 'SAY', say: 'TEXT',
  text: "I'll start by reading the current auth implementation and searching for all JWT usages before making any changes.",
};

const MSG_AGENT_2: (typeof mockMessages)[0] = {
  ts: Date.now() - 160000, type: 'SAY', say: 'TEXT',
  text: "Found 4 usages of `jwt.decode` across 3 files. I'll now extract this into a dedicated `TokenService`. Here's the proposed change:",
};

const MSG_AGENT_3: (typeof mockMessages)[0] = {
  ts: Date.now() - 130000, type: 'SAY', say: 'TEXT',
  text: "Edit applied and diagnostics are clean. Before running the full test suite, let me check if there are any questions about the approach:",
};

const MSG_AGENT_4: (typeof mockMessages)[0] = {
  ts: Date.now() - 60000, type: 'SAY', say: 'TEXT',
  text: "All tests passing. Here's a summary of the CI build and quality gate status:",
};

const MOCK_CHART_CI = JSON.stringify({
  type: 'bar',
  data: {
    labels: ['#452', '#453', '#454', '#455', '#456'],
    datasets: [{
      label: 'Build Duration (s)',
      data: [145, 132, 141, 78, 138],
      backgroundColor: ['rgba(34,197,94,0.5)', 'rgba(34,197,94,0.5)', 'rgba(34,197,94,0.5)', 'rgba(239,68,68,0.5)', 'rgba(34,197,94,0.5)'],
      borderColor: ['rgba(34,197,94,0.8)', 'rgba(34,197,94,0.8)', 'rgba(34,197,94,0.8)', 'rgba(239,68,68,0.8)', 'rgba(34,197,94,0.8)'],
      borderWidth: 1,
    }],
  },
});

function ChatFlowTab() {
  return (
    <div>
      <p className="text-[12px] mb-4" style={{ color: 'var(--fg-muted)' }}>
        A simulated agent session showing every UI component in the order it appears in the real chat. Layout matches the actual agent tab exactly.
      </p>

      {/* ── Agent tab shell ── */}
      <div
        className="rounded-lg overflow-hidden flex flex-col"
        style={{
          border: '1px solid var(--border)',
          height: 860,
        }}
      >
        {/* TopBar */}
        <TopBar />

        {/* SkillBanner */}
        <SkillBanner />

        {/* Scrollable chat content */}
        <div className="flex-1 overflow-y-auto px-4 py-4 space-y-4" style={{ background: 'var(--bg)' }}>

          {/* 1. User message */}
          <AgentMessage message={MSG_USER_1} />

          {/* 2. Agent reply + first tool chain */}
          <AgentMessage message={MSG_AGENT_1} />
          <ToolCallChain toolCalls={CHAT_FLOW_TOOL_CALLS_1} />

          {/* 3. Thinking block */}
          <ThinkingView
            content="4 usages of jwt.decode found. All in service-layer files. Safe to centralise into TokenService. No circular dependency risk — token-service.ts doesn't import any route files."
            isStreaming={false}
          />

          {/* 4. Agent proposes edit + approval gate */}
          <AgentMessage message={MSG_AGENT_2} />
          <ApprovalView
            toolName="edit_file"
            riskLevel="MEDIUM"
            title="Approve edit_file?"
            description="Extract JWT logic into TokenService and update callers."
            metadata={mockApprovalEditMetadata}
            diffContent={mockDiffSource}
            onApprove={() => alert('Edit approved')}
            onDeny={() => alert('Edit denied')}
            onAllowForSession={() => alert('Allowed for session')}
          />

          {/* 5. EditDiffView (applied) */}
          <EditDiffView
            filePath="src/auth/token-service.ts"
            oldLines={mockEditOldLines}
            newLines={mockEditNewLines}
            accepted={true}
          />

          {/* 6. Second tool chain (edit + diagnostics + test) */}
          <ToolCallChain toolCalls={CHAT_FLOW_TOOL_CALLS_2} />

          {/* 7. Plan */}
          <AgentMessage message={MSG_AGENT_3} />
          <PlanSummaryCard plan={mockPlanPending} />

          {/* 8. Plan in progress (after approval) */}
          <PlanProgressWidget plan={mockPlanInProgress} />

          {/* 9. Question wizard */}
          <QuestionView questions={mockQuestions} activeIndex={0} />

          {/* 10. Process input (stdin) */}
          <ProcessInputView
            processId="proc-test"
            description="The test runner is asking whether to update snapshots."
            prompt="Update 3 outdated snapshots? [y/N]"
            command="npm test -- --updateSnapshot"
            onSubmit={(v) => alert(`stdin: ${v}`)}
          />

          {/* 11. Rich blocks — all types that appear in chat */}

          {/* ANSI terminal output */}
          <AnsiOutput text={mockAnsiText} />

          {/* Chart */}
          <AgentMessage message={MSG_AGENT_4} />
          <ChartView source={MOCK_CHART_CI} />

          {/* Diff (diff2html) */}
          <DiffHtml
            diffSource={mockDiffSource}
            onAcceptHunk={(i) => alert(`Accept hunk ${i}`)}
            onRejectHunk={(i) => alert(`Reject hunk ${i}`)}
          />

          {/* Math */}
          <MathBlock latex={mockMathLatex} displayMode={true} />

          {/* Mermaid sequence */}
          <MermaidDiagram source={mockSequenceDiagram} />

          {/* Flow diagram */}
          <FlowDiagram source={mockAnimatedFlow} />

          {/* Data table */}
          <DataTable tableSource={mockTableData} />

          {/* Timeline */}
          <TimelineView timelineSource={mockTimelineData} />

          {/* Progress */}
          <ProgressView progressSource={mockProgressData} />

          {/* Collapsible output */}
          <CollapsibleOutput outputSource={mockOutputData} />

          {/* Interactive HTML */}
          <InteractiveHtml htmlContent={mockInteractiveHtml} height={160} />

          {/* Image */}
          <ImageView imageSource={mockImageSource} />

          {/* 12. Third tool chain (CI + completion) */}
          <ToolCallChain toolCalls={CHAT_FLOW_TOOL_CALLS_3} />

          {/* 13. Completion card */}
          <CompletionCard
            result="**Authentication refactor complete.** Extracted `TokenService`, added 12 unit tests (coverage 68% → 84%), updated all 3 API routes, removed deprecated middleware. CI build #456 passing."
            verifyCommand="./gradlew :auth:test && ./gradlew :auth:check"
          />

          {/* Working indicator (shown while agent is busy) */}
          <div className="flex items-center gap-2 px-1 py-1">
            <Loader variant="wave" size="sm" />
            <TextShimmer duration={3} className="text-[12px]">
              Updating the CHANGELOG so future me has someone to blame...
            </TextShimmer>
          </div>

        </div>

        {/* InputBar */}
        <div style={{ borderTop: '1px solid var(--border)' }}>
          <InputBar />
        </div>

        {/* DebugPanel */}
        <DebugPanel />
      </div>

      {/* Component index */}
      <div className="mt-4 rounded-lg border p-4" style={{ borderColor: 'var(--border)' }}>
        <p className="text-[11px] font-semibold mb-2" style={{ color: 'var(--fg-secondary)' }}>Components used in the chat flow above (top → bottom):</p>
        <div className="grid grid-cols-3 gap-x-6 gap-y-1">
          {[
            'TopBar', 'SkillBanner', 'AgentMessage (user)',
            'AgentMessage (agent)', 'ToolCallChain', 'ThinkingView',
            'ApprovalView (MEDIUM)', 'EditDiffView', 'PlanSummaryCard',
            'PlanProgressWidget', 'QuestionView', 'ProcessInputView',
            'AnsiOutput', 'ChartView', 'DiffHtml',
            'MathBlock', 'MermaidDiagram', 'FlowDiagram',
            'DataTable', 'TimelineView', 'ProgressView',
            'CollapsibleOutput', 'InteractiveHtml', 'ImageView',
            'CompletionCard', 'Working indicator', 'InputBar',
            'DebugPanel',
          ].map(name => (
            <span key={name} className="text-[10px] font-mono" style={{ color: 'var(--fg-muted)' }}>· {name}</span>
          ))}
        </div>
      </div>
    </div>
  );
}

// ── Plan Editor Tab — full-screen, mirrors the IDE editor tab experience ──────

function PlanEditorTab() {
  return (
    <div
      className="mt-4 rounded-lg overflow-hidden border"
      style={{
        borderColor: 'var(--border)',
        // Mimic IDE editor tab: full viewport height minus top chrome
        height: 'calc(100vh - 140px)',
        minHeight: 600,
      }}
    >
      {/* IDE-style tab strip header */}
      <div
        className="flex items-center gap-2 px-4 py-2 border-b text-[11px]"
        style={{ backgroundColor: 'var(--toolbar-bg)', borderColor: 'var(--border)', color: 'var(--fg-muted)' }}
      >
        <span style={{ color: 'var(--accent)' }}>📄</span>
        <span style={{ color: 'var(--fg)' }}>Implementation Plan</span>
        <span className="ml-auto opacity-50">AgentPlanEditor · plan-editor.html</span>
      </div>
      {/* Full-height iframe — no padding, no container, exactly like the IDE tab */}
      <PlanEditorIframe planJson={mockPlanEditorData} height="100%" />
    </div>
  );
}

// ── Root Showcase ─────────────────────────────────────────────────────────────

function Showcase() {
  const [dark, setDark] = useState(getStoredTheme);
  const [activeTab, setActiveTab] = useState<TabId>('components');

  // Sync dark mode with both CSS vars and themeStore
  useEffect(() => {
    applyShowcaseTheme(dark);
    useThemeStore.setState({ isDark: dark });
  }, [dark]);

  // Initialize chatStore with mock data for store-dependent components
  useEffect(() => {
    useChatStore.setState({
      skillBanner: 'systematic-debugging',
      tokenBudget: { used: 45000, max: 190000 },
      busy: false,
      debugLogVisible: true,
      debugLogEntries: [
        { ts: Date.now() - 5200, level: 'info', event: 'tool_call', detail: 'read_file(src/config.ts)', meta: { duration: 120 } },
        { ts: Date.now() - 3100, level: 'info', event: 'tool_call', detail: 'search_code(deprecated)', meta: { duration: 340 } },
        { ts: Date.now() - 900, level: 'error', event: 'tool_error', detail: 'run_command: Permission denied', meta: { duration: 50 } },
      ],
      pendingApproval: null,
    });
    applyShowcaseTheme(dark);
    useThemeStore.setState({ isDark: dark });
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const toggleTheme = () => setDark(prev => !prev);

  const isPlanEditor = activeTab === 'plan-editor';

  return (
    <div
      className={`min-h-screen ${isPlanEditor ? 'p-4' : 'p-6 max-w-3xl mx-auto'}`}
      style={{ backgroundColor: 'var(--bg)', color: 'var(--fg)' }}
    >
      {/* Theme toggle */}
      <div className="fixed top-4 right-4 z-50">
        <Button variant="outline" size="sm" onClick={toggleTheme}>
          {dark ? <Sun className="h-4 w-4" /> : <Moon className="h-4 w-4" />}
          <span className="ml-2 text-xs">{dark ? 'Light' : 'Dark'}</span>
        </Button>
      </div>

      {!isPlanEditor && (
        <>
          <h1 className="text-2xl font-bold mb-2">Agent UI — Component Showcase</h1>
          <p className="text-[12px] mb-6" style={{ color: 'var(--fg-muted)' }}>
            All UI components used in the agent tab, rendered with mock data and a live theme toggle.
          </p>
        </>
      )}

      <TabBar active={activeTab} onChange={setActiveTab} />

      {activeTab === 'components' && <ComponentsTab />}
      {activeTab === 'plan' && <PlanTab />}
      {activeTab === 'chat-flow' && <ChatFlowTab />}
      {activeTab === 'plan-editor' && <PlanEditorTab />}
    </div>
  );
}

const root = createRoot(document.getElementById('root')!);
root.render(
  <StrictMode>
    <Showcase />
  </StrictMode>
);
