import { StrictMode, useState, useEffect } from 'react';
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
} from './showcase/mock-data';
import { AgentMessage } from './components/chat/AgentMessage';
import { ToolCallChain } from './components/agent/ToolCallChain';
import { ThinkingView } from './components/agent/ThinkingView';
import { ApprovalView } from './components/agent/ApprovalView';
import { QuestionView } from './components/agent/QuestionView';
import { PlanSummaryCard } from './components/agent/PlanSummaryCard';
import { PlanProgressWidget } from './components/agent/PlanProgressWidget';
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
import { Sun, Moon } from 'lucide-react';

// Install mock bridge globals so components don't crash
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
window._navigateToFile = window._navigateToFile ?? ((path: string) => alert(`Navigate: ${path}`));
window._openInEditorTab = window._openInEditorTab ?? (() => alert('Open in editor tab'));

function Section({ title, children }: { title: string; children: React.ReactNode }) {
  return (
    <div className="mb-8">
      <h2 className="text-lg font-semibold mb-3 pb-1 border-b" style={{ color: 'var(--fg)', borderColor: 'var(--border)' }}>
        {title}
      </h2>
      <div className="space-y-3">{children}</div>
    </div>
  );
}

function Showcase() {
  const [dark, setDark] = useState(getStoredTheme);

  const toggleTheme = () => {
    const next = !dark;
    setDark(next);
    applyShowcaseTheme(next);
  };

  useEffect(() => { applyShowcaseTheme(dark); }, [dark]);

  return (
    <div className="min-h-screen p-6 max-w-3xl mx-auto" style={{ backgroundColor: 'var(--bg)', color: 'var(--fg)' }}>
      {/* Theme toggle */}
      <div className="fixed top-4 right-4 z-50">
        <Button variant="outline" size="sm" onClick={toggleTheme}>
          {dark ? <Sun className="h-4 w-4" /> : <Moon className="h-4 w-4" />}
          <span className="ml-2 text-xs">{dark ? 'Light' : 'Dark'}</span>
        </Button>
      </div>

      <h1 className="text-2xl font-bold mb-6">Agent UI — Component Showcase</h1>

      {/* ── Chat Input ── */}
      <Section title="Chat Input">
        <div className="border rounded-lg overflow-hidden" style={{ borderColor: 'var(--border)' }}>
          <InputBar />
        </div>
        <p className="text-[10px] mt-1" style={{ color: 'var(--fg-muted)' }}>
          Type @ to trigger mention dropdown. Model/Plan/Skills chips in bottom-left. Send/Stop buttons in bottom-right.
        </p>
      </Section>

      {/* ── Action Toolbar ── */}
      <Section title="Action Toolbar">
        <div className="border rounded-lg p-2" style={{ borderColor: 'var(--border)', backgroundColor: 'var(--tool-bg)' }}>
          <p className="text-[10px] mb-2" style={{ color: 'var(--fg-muted)' }}>Hovered state (shows all actions):</p>
          <ActionToolbar isHovered={true} />
        </div>
      </Section>

      {/* ── Context Chips ── */}
      <Section title="Context Chips">
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
        <p className="text-[10px] mt-1" style={{ color: 'var(--fg-muted)' }}>
          Shows while agent is busy. Rotates through 72 funny phrases every 3 seconds.
        </p>
      </Section>

      {/* ── Messages ── */}
      <Section title="Messages">
        {mockMessages.map(msg => (
          <AgentMessage key={msg.id} message={msg} />
        ))}
        <p className="text-xs mt-2" style={{ color: 'var(--fg-muted)' }}>Streaming message:</p>
        <AgentMessage
          message={mockStreamingMessage}
          isStreaming={true}
          streamText={mockStreamingMessage.content}
        />
      </Section>

      {/* ── Tool Calls ── */}
      <Section title="Tool Calls">
        <ToolCallChain toolCalls={mockToolCalls} />
      </Section>

      {/* ── Thinking / Reasoning ── */}
      <Section title="Thinking / Reasoning">
        <ThinkingView content="Let me analyze the codebase structure to understand the authentication flow..." isStreaming={true} />
        <ThinkingView content="The authentication module uses JWT tokens with a 24-hour expiry. The refresh token mechanism is implemented in auth-middleware.ts." isStreaming={false} />
      </Section>

      {/* ── Approval Gate ── */}
      <Section title="Approval Gate">
        <ApprovalView
          toolName="run_command"
          riskLevel="DESTRUCTIVE"
          title="Approve run_command? (DESTRUCTIVE risk)"
          description="This will permanently remove all data from the production environment."
          metadata={[{ key: 'Command', value: 'DROP DATABASE production_db;' }]}
          onApprove={() => alert('Approved')}
          onDeny={() => alert('Denied')}
          onAllowForSession={() => alert('Allowed for session')}
        />
      </Section>

      {/* ── Question Wizard ── */}
      <Section title="Question Wizard">
        <QuestionView questions={mockQuestions} activeIndex={0} />
        <p className="text-[10px] mt-1" style={{ color: 'var(--fg-muted)' }}>
          Supports single-select, multi-select, text input. Has Skip, Chat about this, Cancel. Summary page after all answered.
        </p>
      </Section>

      {/* ── Plan — Pending Review ── */}
      <Section title="Plan — Pending Review">
        <PlanSummaryCard plan={mockPlanPending} />
      </Section>

      {/* ── Plan — In Progress ── */}
      <Section title="Plan — In Progress">
        <PlanProgressWidget plan={mockPlanInProgress} />
      </Section>

      {/* ── Data Table ── */}
      <Section title="Data Table">
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

      {/* ── Animated Flow (Request Path) ── */}
      <Section title="Animated Flow (Request Path)">
        <p className="text-[10px] mb-2" style={{ color: 'var(--fg-muted)' }}>
          Click Play to step through the authentication flow. Nodes glow on activation, traveling dots show data flow direction. Uses highlightPath for request + response.
        </p>
        <FlowDiagram source={mockAnimatedFlow} />
      </Section>

      {/* ── Animated Sequence Diagram ── */}
      <Section title="Animated Sequence Diagram">
        <p className="text-[10px] mb-2" style={{ color: 'var(--fg-muted)' }}>
          Messages reveal one at a time. Click Play or use step controls.
        </p>
        <MermaidDiagram source={mockSequenceDiagram} />
      </Section>

      {/* ── Flow Diagram (with Groups) ── */}
      <Section title="Flow Diagram (with Groups)">
        <FlowDiagram source={mockFlowWithGroups} />
      </Section>

      {/* ── Badge Variants ── */}
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
    </div>
  );
}

const root = createRoot(document.getElementById('root')!);
root.render(
  <StrictMode>
    <Showcase />
  </StrictMode>
);
