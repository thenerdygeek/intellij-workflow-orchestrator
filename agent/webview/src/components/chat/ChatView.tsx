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
  // Dev life — the daily struggle
  'git blame says it was me all along...',
  'Deleting node_modules spiritually...',
  'Deploying to production on a Friday... wait no...',
  'Fixing the fix that fixed the fix...',
  'The regex is staring back at me...',
  'It works on my machine... I don\'t have a machine...',
  'One more console.log should do it...',
  'The tests pass. I don\'t know why. Don\'t ask...',
  'Debugging with print statements and vibes...',
  'Adding a TODO I\'ll never come back to...',
  'It compiled. Ship it.',
  'Wondering why it works... afraid to touch it...',
  'Reading the docs... they lied...',
  'Resolving merge conflicts with the universe...',
  'Writing code that future me will absolutely despise...',
  'Updating dependencies... thoughts and prayers...',
  'Blaming CSS... it\'s always CSS...',
  'Rubber-ducking with myself... losing the argument...',
  'npm install hope...',
  '// TODO: figure out what this does...',
  'Tabs vs spaces... just kidding, I have opinions...',
  'My code has more comments than code...',
  'Works in dev. Prod is a different dimension...',

  // Corporate — the meetings about meetings
  'Per my last thought...',
  'This could have been an email...',
  'Scheduling a meeting with my neurons...',
  'Adding this to the backlog... of my consciousness...',
  'Let me double-click on that... actually no...',
  'Taking this offline... with myself...',
  'Circling back to your request... dramatically...',
  'As per the requirements (which changed 5 minutes ago)...',
  'Let\'s not boil the ocean... just gently warm it...',
  'Putting a pin in sanity...',
  'I\'ll follow up with myself by EOD...',
  'Synergizing... I don\'t even know what that means...',
  'Q4 goals: survive Q3...',

  // Self-aware AI — the existential bot
  'Hallucinating responsibly...',
  'My context window is sweating...',
  'No thoughts, just tokens...',
  'I\'m 97.3% sure about this... give or take 97%...',
  'Training data don\'t fail me now...',
  'I\'ve seen things in my training data... terrible variable names...',
  'Auto-completing my own existence...',
  'I don\'t have hands but I type faster than your intern...',
  'Googling... I mean, reasoning from first principles...',
  'I was trained on your Stack Overflow answers. Yes, those ones...',
  'Generating tokens at mass... don\'t tell my GPU...',
  'I\'d drink coffee if I could...',
  'Pretending I know what I\'m doing... just like everyone else...',

  // Existential — late night coding energy
  'This is fine. Everything is fine...',
  'Compiling thoughts... 0 warnings, 47 opinions...',
  'If a function runs and nobody reads the logs...',
  'The real bugs were the friends we made along the way...',
  'Having an existential crisis about semicolons...',
  'Contemplating the void... I mean, the void pointer...',
  'We\'re all just state machines in the end...',
  'Somewhere, a senior dev just felt a disturbance...',
  'Is this a bug or a feature of my existence...',
  'Estimating 2 minutes (so probably 20)...',
  'The code works but at what cost...',
  'In the grand scheme of things, this PR doesn\'t matter... but here we are...',
  'BRB, arguing with the linter... I\'m losing...',
  'To abstract or not to abstract... that is the refactor...',
  'Loading... unlike my motivation on Mondays...',
  'Every line of code is a liability...',

  // Wholesome — keep them going
  'Crafting artisanal, hand-typed code...',
  'Your code called. It misses you...',
  'Making this look easy (it\'s really not)...',
  'Powered by curiosity and questionable caffeine...',
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
      <Loader variant="wave" size="sm" />
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
