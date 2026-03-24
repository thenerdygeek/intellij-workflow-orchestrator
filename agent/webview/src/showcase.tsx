import { StrictMode, useState, useEffect } from 'react';
import { createRoot } from 'react-dom/client';
import './index.css';
import { applyShowcaseTheme, getStoredTheme } from './showcase/theme-provider';
import {
  mockMessages, mockStreamingMessage, mockToolCalls,
  mockPlanPending, mockPlanInProgress, mockQuestions,
} from './showcase/mock-data';
import { AgentMessage } from './components/chat/AgentMessage';
import { ToolCallChain } from './components/agent/ToolCallChain';
import { ThinkingView } from './components/agent/ThinkingView';
import { ApprovalView } from './components/agent/ApprovalView';
import { QuestionView } from './components/agent/QuestionView';
import { PlanSummaryCard } from './components/agent/PlanSummaryCard';
import { PlanProgressWidget } from './components/agent/PlanProgressWidget';
import { Badge } from './components/ui/badge';
import { Button } from './components/ui/button';
import { Sun, Moon } from 'lucide-react';

// Install mock bridge globals so components don't crash
window._sendMessage = window._sendMessage ?? (() => {});
window._approvePlan = window._approvePlan ?? (() => alert('Plan approved'));
window._revisePlan = window._revisePlan ?? (() => alert('Plan revised'));
window._cancelTask = window._cancelTask ?? (() => {});
window._searchMentions = window._searchMentions ?? (() => {});
window._questionAnswered = window._questionAnswered ?? ((id: string, json: string) => alert(`Answered ${id}: ${json}`));
window._questionSkipped = window._questionSkipped ?? (() => {});
window._editQuestion = window._editQuestion ?? (() => {});

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

      <Section title="Tool Calls">
        <ToolCallChain toolCalls={mockToolCalls} />
      </Section>

      <Section title="Thinking / Reasoning">
        <ThinkingView content="Let me analyze the codebase structure to understand the authentication flow..." isStreaming={true} />
        <ThinkingView content="The authentication module uses JWT tokens with a 24-hour expiry. The refresh token mechanism is implemented in auth-middleware.ts." isStreaming={false} />
      </Section>

      <Section title="Approval Gate">
        <ApprovalView
          title="Delete Production Database"
          description="This will permanently remove all data from the production environment."
          commandPreview="DROP DATABASE production_db;"
          onApprove={() => alert('Approved')}
          onDeny={() => alert('Denied')}
        />
      </Section>

      <Section title="Question Wizard">
        <QuestionView questions={mockQuestions} activeIndex={0} />
      </Section>

      <Section title="Plan — Pending Review">
        <PlanSummaryCard plan={mockPlanPending} />
      </Section>

      <Section title="Plan — In Progress">
        <PlanProgressWidget plan={mockPlanInProgress} />
      </Section>

      <Section title="Context Chips (Badge)">
        <div className="flex flex-wrap gap-2">
          {(['file', 'folder', 'symbol', 'tool', 'skill'] as const).map(type => (
            <Badge key={type} variant="secondary" className="text-[11px]">
              {type === 'file' ? '📄' : type === 'folder' ? '📁' : type === 'symbol' ? '#' : type === 'tool' ? '🔧' : '✨'}
              <span className="ml-1">{type}</span>
            </Badge>
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
