import { memo, useCallback, useState, useEffect } from 'react';
import { useChatStore } from '@/stores/chatStore';
import { AgentMessage } from './AgentMessage';
import { ToolCallChain } from '@/components/agent/ToolCallChain';
import { PlanSummaryCard } from '@/components/agent/PlanSummaryCard';
import { PlanProgressWidget } from '@/components/agent/PlanProgressWidget';
import { QuestionView } from '@/components/agent/QuestionView';
import { ApprovalView } from '@/components/agent/ApprovalView';
import {
  ChatContainerRoot,
  ChatContainerContent,
  ChatContainerScrollAnchor,
} from '@/components/ui/prompt-kit/chat-container';
import { ScrollButton } from '@/components/ui/prompt-kit/scroll-button';
import { Loader } from '@/components/ui/prompt-kit/loader';
import { TextShimmer } from '@/components/ui/prompt-kit/text-shimmer';
import type { Message } from '@/bridge/types';

const WORKING_PHRASES = [
  'Working...',
  'Thinking...',
  'Pondering...',
  'Analyzing...',
  'Cooking something up...',
  'On it...',
  'Crunching the bits...',
  'Consulting the codebase...',
  'Reading the tea leaves...',
  'Assembling the pieces...',
  'Connecting the dots...',
  'Summoning the answer...',
  'Diving deep...',
  'Spinning up neurons...',
  'Brewing a response...',
  'Untangling the logic...',
  'Parsing the universe...',
  'Refactoring reality...',
  'Compiling thoughts...',
  'Running mental tests...',
];

function useRotatingPhrase(intervalMs = 3000): string {
  const [index, setIndex] = useState(() => Math.floor(Math.random() * WORKING_PHRASES.length));

  useEffect(() => {
    const timer = setInterval(() => {
      setIndex(prev => (prev + 1) % WORKING_PHRASES.length);
    }, intervalMs);
    return () => clearInterval(timer);
  }, [intervalMs]);

  return WORKING_PHRASES[index]!;
}

function WorkingIndicator() {
  const phrase = useRotatingPhrase(3000);
  return (
    <div className="flex items-center gap-2 px-2 py-2 animate-[fade-in_200ms_ease-out]">
      <Loader variant="typing" size="sm" />
      <TextShimmer key={phrase} duration={3} className="text-[12px]">
        {phrase}
      </TextShimmer>
    </div>
  );
}

export const ChatView = memo(function ChatView() {
  const messages = useChatStore(s => s.messages);
  const activeStream = useChatStore(s => s.activeStream);
  const activeToolCalls = useChatStore(s => s.activeToolCalls);
  const plan = useChatStore(s => s.plan);
  const questions = useChatStore(s => s.questions);
  const activeQuestionIndex = useChatStore(s => s.activeQuestionIndex);
  const pendingApproval = useChatStore(s => s.pendingApproval);
  const resolveApproval = useChatStore(s => s.resolveApproval);
  const busy = useChatStore(s => s.busy);

  const handleApprove = useCallback(() => resolveApproval(true), [resolveApproval]);
  const handleDeny = useCallback(() => resolveApproval(false), [resolveApproval]);

  // Convert tool calls map to sorted array
  const toolCallsArray = Array.from(activeToolCalls.values());

  // Stream placeholder message for rendering
  const streamPlaceholder: Message | null = activeStream
    ? {
        id: '__streaming__',
        role: 'agent',
        content: activeStream.text,
        timestamp: Date.now(),
      }
    : null;

  return (
    <ChatContainerRoot
      className="relative flex-1"
      aria-live="polite"
      aria-label="Agent chat messages"
    >
      <ChatContainerContent className="px-4 py-3 gap-3">
        {/* Messages */}
        {messages.map((msg, i) => (
          <div
            key={msg.id}
            style={{ animationDelay: `${Math.min(i * 40, 200)}ms` }}
          >
            <AgentMessage message={msg} />
          </div>
        ))}

        {/* Active tool calls as connected chain */}
        {toolCallsArray.length > 0 && (
          <ToolCallChain toolCalls={toolCallsArray} />
        )}

        {/* Tool call approval */}
        {pendingApproval && (
          <ApprovalView
            title={pendingApproval.title}
            description={pendingApproval.description}
            commandPreview={pendingApproval.commandPreview}
            onApprove={handleApprove}
            onDeny={handleDeny}
          />
        )}

        {/* Plan */}
        {plan && !plan.approved && <PlanSummaryCard plan={plan} />}
        {plan && plan.approved && <PlanProgressWidget plan={plan} />}

        {/* Questions */}
        {questions && questions.length > 0 && (
          <QuestionView questions={questions} activeIndex={activeQuestionIndex} />
        )}

        {/* Streaming message */}
        {streamPlaceholder && (
          <AgentMessage
            key="__streaming__"
            message={streamPlaceholder}
            isStreaming={activeStream?.isStreaming ?? false}
            streamText={activeStream?.text}
          />
        )}

        {/* Working indicator — shows while agent is busy and no streaming content yet */}
        {busy && !streamPlaceholder && toolCallsArray.length === 0 && (
          <WorkingIndicator />
        )}

        <ChatContainerScrollAnchor />
      </ChatContainerContent>

      {/* Scroll-to-bottom button */}
      <div className="absolute bottom-4 left-1/2 -translate-x-1/2 z-10">
        <ScrollButton />
      </div>
    </ChatContainerRoot>
  );
});
