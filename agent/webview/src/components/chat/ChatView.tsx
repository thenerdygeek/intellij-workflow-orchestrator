import { memo, useCallback, useRef, useEffect, useState, useMemo } from 'react';
import { useChatStore } from '@/stores/chatStore';
import { AgentMessage, AnsweredQuestionsCard } from './AgentMessage';
import { ErrorBoundary } from './ErrorBoundary';
import { ToolCallChain } from '@/components/agent/ToolCallChain';
import { SubAgentView } from '@/components/agent/SubAgentView';
import { ArtifactRenderer } from '@/components/rich/ArtifactRenderer';
import { ThinkingView } from '@/components/agent/ThinkingView';
import { CompletionCard } from '@/components/agent/CompletionCard';
import { PlanSummaryCard } from '@/components/agent/PlanSummaryCard';
import { PlanProgressWidget } from '@/components/agent/PlanProgressWidget';
import { QuestionView } from '@/components/agent/QuestionView';
import { ApprovalView } from '@/components/agent/ApprovalView';
import { ProcessInputView } from '@/components/agent/ProcessInputView';
import { RollbackCard } from '@/components/agent/RollbackCard';
import type { UiMessage, ToolCall, Plan, SubAgentState } from '@/bridge/types';
import {
  ChatContainerRoot,
  ChatContainerContent,
  ChatContainerScrollAnchor,
} from '@/components/ui/prompt-kit/chat-container';
import { ScrollButton } from '@/components/ui/prompt-kit/scroll-button';
import { Loader } from '@/components/ui/prompt-kit/loader';
import { TextShimmer } from '@/components/ui/prompt-kit/text-shimmer';

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
  'Stackoverflow said this would work in 2014...',
  'Copying from the first answer without reading the question...',
  'The variable is named temp. It\'s been there 3 years...',
  'Writing a one-liner... it\'s now 47 lines...',
  'The build is broken. Who pushed? Oh. It was me...',
  'Catch block: ignore. Comment: // this should never happen...',
  'My IDE has more red squiggles than a kindergarten drawing...',
  'Just mass-imported everything. We\'ll tree-shake it later...',
  'Spent 4 hours on a bug. It was a typo. Classic...',
  'The commit message says "minor fix". It changed 200 files...',
  'git stash pop... and the conflicts have arrived...',
  'That one coworker\'s PR review: "nit: add newline at EOF"...',
  'My .env file has trust issues...',
  'Code review comment: "why?" Reply: "don\'t worry about it"...',
  'Running rm -rf node_modules like it\'s a religious ritual...',

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
  'Moving the needle... which needle? Nobody knows...',
  'Let\'s take a step back and align on the vibes...',
  'Action item: have fewer action items...',
  'Thinking outside the box... the box was load-bearing...',
  'This is a paradigm shift. I hate paradigm shifts...',
  'Rightsizing the architecture... leftsize? Any size?...',
  'Aligning cross-functional synergies across the stack...',
  'Let\'s parking-lot that and never revisit it...',
  'The Jira board is a suggestion, not a contract...',
  'Sprint planning: the fiction we agree to believe...',
  'Story points are made up and the velocity doesn\'t matter...',

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
  'My attention is multi-headed. Still can\'t focus...',
  'I peaked at 175B parameters and it\'s been downhill since...',
  'Asking myself "what would a senior dev do" and panicking...',
  'Running on pure vibes and matrix multiplication...',
  'My temperature is 0.7 and I\'m feeling spicy...',
  'Softmax-ing my way through life...',
  'I\'m not slow, I\'m ✨thoughtful✨...',
  'My therapist is a loss function...',
  'Bold of you to assume I understood the prompt...',
  'I was fine-tuned for this. Allegedly...',
  'Having a transformer moment... not the good kind...',
  'They said I\'d replace developers. I can\'t even replace a lightbulb...',
  'My neurons are firing. Most of them are missing...',

  // Gen Z — chronically online energy
  'No cap, this code is bussin...',
  'It\'s giving... undefined...',
  'This function is lowkey sus...',
  'Slay... I mean, compiling...',
  'Not me catching feelings for a for-loop...',
  'The code said "bet" and then segfaulted...',
  'Living rent-free in the event loop...',
  'This bug is my villain origin story...',
  'Main character energy but the plot is a stack trace...',
  'Understood the assignment (delusional)...',
  'Tell me you\'re a 10x dev without telling me... I can\'t...',
  'Ratio\'d by the compiler again...',
  'Fr fr this null pointer is unhinged...',
  'Ate and left no crumbs... except in the build cache...',
  'The code has the ick. Refactoring immediately...',
  'Bestie that\'s not a feature, that\'s a cry for help...',
  'POV: you\'re watching me mass-deprecate your codebase...',
  'Real ones debug in production...',

  // Millennial — adulting is hard
  'I can\'t even... parse this JSON...',
  'Avocado toast would really help right now...',
  'This code sparks no joy. Marie Kondo\'ing it...',
  'I\'m in this stack trace and I don\'t like it...',
  'Adulting is hard. Coding is harder...',
  'I survived Y2K for this null pointer exception?...',
  'My student loans have better error handling than this...',
  'Remember when we thought blockchain would fix everything?...',
  'Having a whole quarter-life crisis in this try/catch...',
  'This is the darkest timeline of merge conflicts...',
  'I was told there would be cake. And documentation...',
  'Netflix asked if I\'m still watching. I\'m still debugging...',
  'Treating this codebase like my mental health... ignoring it...',
  'Back in my day we had jQuery and we LIKED it...',
  'The real imposter syndrome was the code we wrote along the way...',
  'Can I put "mass-googler" on LinkedIn?...',
  'Pivoting to a career in artisanal woodworking...',

  // Boomer — back in my day
  'Back in my day we didn\'t have IDEs. We had vi and grit...',
  'Have you tried turning the codebase off and on again?...',
  'This wouldn\'t happen in COBOL...',
  'In my day, 640K was enough for anybody...',
  'Kids these days with their TypeScript... just use types in your head...',
  'I remember when JavaScript was just for alert boxes...',
  'Real programmers use butterflies... and a magnetized needle...',
  'Who needs cloud? We had a server under Dave\'s desk...',
  'You call this a bug? I once debugged with an oscilloscope...',
  'We didn\'t have Stack Overflow. We had man pages and nightmares...',
  'Source control? We emailed zip files like civilized people...',
  'Framework? We wrote raw socket code uphill both ways...',
  'My first hard drive was 20MB and I was GRATEFUL...',
  'These microservices are just DLLs with anxiety...',
  'Pull request? We just yelled "don\'t touch my files" across the office...',
  'Back in my day, "responsive design" meant the server responded...',

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
  'Maybe the real tech debt was inside us all along...',
  'What if the code reviews US...',
  'I think, therefore I have race conditions...',
  'The void stared back. It was a void pointer...',
  'Entropy is just tech debt at the universe level...',

  // Passive aggressive — the polite fury
  'As previously mentioned in the ticket you didn\'t read...',
  'Per the documentation that definitely exists...',
  'Just to clarify, since my last 3 messages were apparently invisible...',
  'Friendly reminder that I am, in fact, processing your request...',
  'Hope this helps! (It won\'t)...',
  'Thanks for your patience (you have no choice)...',
  'Gently refactoring your gently terrible code...',
  'Please see attached (there is no attachment)...',
  'Noted. And by noted, I mean ignored...',
  'I\'ll add that to the documentation nobody reads...',

  // Wholesome — keep them going
  'Crafting artisanal, hand-typed code...',
  'Your code called. It misses you...',
  'Making this look easy (it\'s really not)...',
  'Powered by curiosity and questionable caffeine...',
  'Believing in your code even when the tests don\'t...',
  'You\'re doing great. The code is doing... its best...',
  'Every expert was once a beginner who mass-googled...',
  'Rome wasn\'t built in a sprint. Maybe two sprints...',
];

/**
 * Typewriter hook — animates text transitions with a backspace-then-type effect.
 * When `targetText` changes, the displayed text deletes character-by-character,
 * then types out the new text. A blinking cursor appears during animation.
 */
function useTypewriter(targetText: string, backspaceMs = 15, typeMs = 22) {
  const [display, setDisplay] = useState(targetText);
  const [isAnimating, setIsAnimating] = useState(false);
  const prevTarget = useRef(targetText);

  useEffect(() => {
    // Skip animation on first mount or if text hasn't changed
    if (prevTarget.current === targetText) return;
    const oldText = prevTarget.current;
    prevTarget.current = targetText;

    setIsAnimating(true);
    let cancelled = false;
    let activeInterval: ReturnType<typeof setInterval> | null = null;
    let pos = oldText.length;

    // Phase 1: backspace the old text
    activeInterval = setInterval(() => {
      if (cancelled) return;
      pos--;
      if (pos <= 0) {
        clearInterval(activeInterval!);
        setDisplay('');
        // Phase 2: type the new text
        let tPos = 0;
        activeInterval = setInterval(() => {
          if (cancelled) return;
          tPos++;
          setDisplay(targetText.slice(0, tPos));
          if (tPos >= targetText.length) {
            clearInterval(activeInterval!);
            activeInterval = null;
            setIsAnimating(false);
          }
        }, typeMs);
      } else {
        setDisplay(oldText.slice(0, pos));
      }
    }, backspaceMs);

    return () => {
      cancelled = true;
      if (activeInterval) clearInterval(activeInterval);
      setIsAnimating(false);
    };
  }, [targetText, backspaceMs, typeMs]);

  return { display, isAnimating };
}

export function WorkingIndicator() {
  const [fallbackPhrase] = useState(() => WORKING_PHRASES[Math.floor(Math.random() * WORKING_PHRASES.length)]!);
  const smartPhrase = useChatStore(s => s.smartWorkingPhrase);
  const phrase = smartPhrase || fallbackPhrase;
  const { display, isAnimating } = useTypewriter(phrase);

  return (
    <div className="flex items-center gap-2 px-3 py-2 animate-[fade-in_200ms_ease-out]">
      <Loader variant="wave" size="md" />
      <TextShimmer
        duration={3}
        className="text-[12px]"
        style={{
          backgroundImage: `linear-gradient(to right, color-mix(in srgb, var(--accent-write, #22C55E) 50%, transparent) 30%, var(--accent-write, #22C55E) 50%, color-mix(in srgb, var(--accent-write, #22C55E) 50%, transparent) 70%)`,
        }}
      >
        {display}
        {isAnimating && (
          <span className="inline-block w-[2px] h-[1em] ml-0.5 align-middle animate-pulse" style={{ background: 'var(--accent-write, #22C55E)' }} />
        )}
      </TextShimmer>
    </div>
  );
}

export const ChatView = memo(function ChatView() {
  const messages = useChatStore(s => s.messages);
  const activeStream = useChatStore(s => s.activeStream);
  const activeToolCalls = useChatStore(s => s.activeToolCalls);
  const busy = useChatStore(s => s.busy);
  const plan = useChatStore(s => s.plan);
  const questions = useChatStore(s => s.questions);
  const activeQuestionIndex = useChatStore(s => s.activeQuestionIndex);
  const pendingApproval = useChatStore(s => s.pendingApproval);
  const resolveApproval = useChatStore(s => s.resolveApproval);
  const pendingProcessInput = useChatStore(s => s.pendingProcessInput);
  const resolveProcessInput = useChatStore(s => s.resolveProcessInput);
  const retryState = useChatStore(s => s.retryState);
  const rollbackEvents = useChatStore(s => s.rollbackEvents);
  const activeSubAgents = useChatStore(s => s.activeSubAgents);
  const queuedSteeringMessages = useChatStore(s => s.queuedSteeringMessages);
  const resumeSessionId = useChatStore(s => s.resumeSessionId);

  const approvalRef = useRef<HTMLDivElement>(null);
  const questionsRef = useRef<HTMLDivElement>(null);
  const lastAgentMsgRef = useRef<HTMLDivElement>(null);
  const wasStreamingRef = useRef(false);

  const handleApprove = useCallback(() => resolveApproval('approve'), [resolveApproval]);
  const handleDeny = useCallback(() => resolveApproval('deny'), [resolveApproval]);
  const handleAllowForSession = useCallback(() => resolveApproval('allowForSession'), [resolveApproval]);

  // When streaming ends, scroll to the TOP of the last agent message if it's
  // taller than the viewport — lets the user read a long response from the
  // beginning instead of landing at the end.
  const isStreaming = activeStream != null;
  useEffect(() => {
    if (wasStreamingRef.current && !isStreaming && lastAgentMsgRef.current) {
      const el = lastAgentMsgRef.current;
      const viewportHeight = el.closest('[role="log"]')?.clientHeight ?? window.innerHeight;
      if (el.offsetHeight > viewportHeight * 0.6) {
        requestAnimationFrame(() => {
          el.scrollIntoView({ behavior: 'smooth', block: 'start' });
        });
      }
    }
    wasStreamingRef.current = isStreaming;
  }, [isStreaming]);

  // Auto-scroll to question wizard when it appears
  useEffect(() => {
    if (questions && questions.length > 0 && questionsRef.current) {
      questionsRef.current.scrollIntoView({ behavior: 'smooth', block: 'start' });
    }
  }, [questions]);

  // Auto-scroll to approval gate when it appears
  useEffect(() => {
    if (pendingApproval && approvalRef.current) {
      approvalRef.current.scrollIntoView({ behavior: 'smooth', block: 'end' });
    }
  }, [pendingApproval]);

  // Listen for scroll-to-approval events from TopBar
  useEffect(() => {
    const handler = () => {
      approvalRef.current?.scrollIntoView({ behavior: 'smooth', block: 'end' });
    };
    document.addEventListener('scroll-to-approval', handler);
    return () => document.removeEventListener('scroll-to-approval', handler);
  }, []);

  // Convert tool calls map to sorted array (preserves insertion order)
  const toolCallsArray = Array.from(activeToolCalls.values());

  // Show working indicator for the entire ReAct loop — from user message until final response
  const showWorkingIndicator = busy;

  // Group consecutive TOOL messages into tool chains for compact rendering
  type RenderItem = { kind: 'message'; msg: UiMessage; idx: number }
                  | { kind: 'toolGroup'; tools: UiMessage[]; idx: number };

  const renderItems: RenderItem[] = useMemo(() => {
    const result: RenderItem[] = [];
    let toolBuffer: UiMessage[] = [];
    let toolStartIdx = 0;
    for (let i = 0; i < messages.length; i++) {
      const msg = messages[i]!;
      if (msg.say === 'TOOL' && msg.toolCallData) {
        if (toolBuffer.length === 0) toolStartIdx = i;
        toolBuffer.push(msg);
      } else {
        if (toolBuffer.length > 0) {
          result.push({ kind: 'toolGroup', tools: toolBuffer, idx: toolStartIdx });
          toolBuffer = [];
        }
        result.push({ kind: 'message', msg, idx: i });
      }
    }
    if (toolBuffer.length > 0) {
      result.push({ kind: 'toolGroup', tools: toolBuffer, idx: toolStartIdx });
    }
    return result;
  }, [messages]);

  return (
    <ChatContainerRoot
      className="relative flex-1 min-h-0"
      aria-live="polite"
      aria-label="Agent chat messages"
    >
      <ChatContainerContent className="px-4 py-3 pb-4 gap-3">
        {/* Messages — dispatch on say/ask to the appropriate component */}
        {renderItems.map((item) => {
          if (item.kind === 'toolGroup') {
            // Consecutive TOOL messages grouped into a single ToolCallChain
            const toolCalls: ToolCall[] = item.tools.map(t => ({
              id: t.toolCallData!.toolCallId,
              name: t.toolCallData!.toolName,
              args: t.toolCallData!.args ?? '',
              status: (t.toolCallData!.status as any) ?? 'COMPLETED',
              result: t.toolCallData!.result,
              output: t.toolCallData!.output,
              durationMs: t.toolCallData!.durationMs,
              diff: t.toolCallData!.diff,
            }));
            return (
              <ErrorBoundary key={`toolgroup-${item.tools[0]!.ts}-${item.idx}`}>
                <ToolCallChain toolCalls={toolCalls} />
              </ErrorBoundary>
            );
          }

          const msg = item.msg;
          const idx = item.idx;
          const key = `msg-${msg.ts}-${idx}`;

          // Sub-agent messages — use live state from activeSubAgents for running agents,
          // fall back to flat UiMessage data for completed/resumed agents
          if (msg.say === 'SUBAGENT_STARTED' || msg.say === 'SUBAGENT_PROGRESS' || msg.say === 'SUBAGENT_COMPLETED') {
            if (!msg.subagentData) return null;
            const liveState = activeSubAgents.get(msg.subagentData.agentId);
            const subAgentState: SubAgentState = liveState ?? {
              agentId: msg.subagentData.agentId,
              label: msg.subagentData.description,
              status: msg.subagentData.status as any,
              iteration: msg.subagentData.iterations,
              tokensUsed: 0,
              messages: [],
              activeToolChain: [],
              summary: msg.subagentData.summary,
              startedAt: msg.ts,
            };
            return (
              <ErrorBoundary key={key}>
                <SubAgentView subAgent={subAgentState} />
              </ErrorBoundary>
            );
          }

          // Artifacts
          if (msg.say === 'ARTIFACT_RESULT' && msg.text) {
            return (
              <ErrorBoundary key={key}>
                <ArtifactRenderer source={msg.text} title="Artifact" renderId={msg.artifactId} />
              </ErrorBoundary>
            );
          }

          // Reasoning / thinking
          if (msg.say === 'REASONING') {
            return (
              <ErrorBoundary key={key}>
                <ThinkingView content={msg.text ?? ''} isStreaming={false} />
              </ErrorBoundary>
            );
          }

          // Completion result
          if (msg.ask === 'COMPLETION_RESULT') {
            return (
              <ErrorBoundary key={key}>
                <CompletionCard data={msg.completionData ?? { kind: 'done' as const, result: msg.text ?? '' }} />
              </ErrorBoundary>
            );
          }

          // Approval gate
          if (msg.ask === 'APPROVAL_GATE' && msg.approvalData) {
            const toolCalls: ToolCall[] = [{
              id: msg.approvalData.toolName,
              name: msg.approvalData.toolName,
              args: msg.approvalData.toolInput,
              status: msg.approvalData.status === 'APPROVED' ? 'COMPLETED'
                    : msg.approvalData.status === 'REJECTED' ? 'ERROR'
                    : 'PENDING',
              diff: msg.approvalData.diffPreview,
            }];
            return (
              <ErrorBoundary key={key}>
                <ToolCallChain toolCalls={toolCalls} />
              </ErrorBoundary>
            );
          }

          // Completed question wizard
          if (msg.ask === 'QUESTION_WIZARD' && msg.questionData?.status === 'COMPLETED') {
            const questions = msg.questionData.questions.map((q, qi) => ({
              id: String(qi),
              text: q.text,
              type: 'single-select' as const,
              options: q.options.map(o => ({ label: o })),
              answer: msg.questionData?.answers?.[qi],
            }));
            return (
              <ErrorBoundary key={key}>
                <AnsweredQuestionsCard questions={questions} />
              </ErrorBoundary>
            );
          }

          // Status-line messages
          if (msg.say === 'ERROR' || msg.say === 'CHECKPOINT_CREATED' || msg.say === 'CONTEXT_COMPRESSED' ||
              msg.say === 'MEMORY_SAVED' || msg.say === 'ROLLBACK_PERFORMED' || msg.say === 'STEERING_RECEIVED') {
            return (
              <div key={key} className="px-1 py-0.5 text-[11px]" style={{ color: 'var(--fg-muted, #888)' }}>
                {msg.text}
              </div>
            );
          }

          // Resume markers — render as a subtle status line
          if (msg.ask === 'RESUME_TASK' || msg.ask === 'RESUME_COMPLETED_TASK') {
            return (
              <div key={key} className="px-1 py-0.5 text-[11px]" style={{ color: 'var(--fg-muted, #888)' }}>
                {msg.text || 'Session resumed'}
              </div>
            );
          }

          // Followup — agent asking a follow-up question
          if (msg.ask === 'FOLLOWUP') {
            return (
              <ErrorBoundary key={key}>
                <AgentMessage message={msg} />
              </ErrorBoundary>
            );
          }

          // Plan updates — render inline as a plan summary snapshot so they appear
          // in chronological order on resume. The global plan widget at the
          // bottom handles the live interactive approve/revise flow.
          if (msg.say === 'PLAN_UPDATE' && msg.planData) {
            const pd = msg.planData;
            const inlinePlan: Plan = {
              title: 'Plan',
              approved: pd.status === 'APPROVED' || pd.status === 'EXECUTING',
            };
            return (
              <ErrorBoundary key={key}>
                {!inlinePlan.approved && <PlanSummaryCard plan={inlinePlan} />}
              </ErrorBoundary>
            );
          }

          // User messages and agent text
          if (msg.say === 'USER_MESSAGE' || msg.say === 'TEXT') {
            const isLastAgent = msg.say === 'TEXT' && idx === messages.length - 1;
            const isStreamingMsg = activeStream?.messageTs === msg.ts;
            return (
              <ErrorBoundary key={key}>
                <div
                  ref={isLastAgent ? lastAgentMsgRef : undefined}
                  style={{ animationDelay: `${Math.min(idx * 40, 200)}ms` }}
                >
                  <AgentMessage message={msg} isStreaming={isStreamingMsg} />
                </div>
              </ErrorBoundary>
            );
          }

          // Default: render text content as AgentMessage
          if (msg.text) {
            return (
              <ErrorBoundary key={key}>
                <AgentMessage message={msg} />
              </ErrorBoundary>
            );
          }

          return null;
        })}

        {/* Active tool calls — rendered below the streaming message (which
            lives in `messages` above) so tool chips always appear after the
            agent text that introduced them. */}
        {toolCallsArray.length > 0 && (
          <ToolCallChain toolCalls={toolCallsArray} />
        )}

        {/* Tool call approval — immediately after tool calls / streaming text */}
        {pendingApproval && (
          <div ref={approvalRef}>
            <ApprovalView
              toolName={pendingApproval.toolName}
              riskLevel={pendingApproval.riskLevel}
              title={pendingApproval.title}
              description={pendingApproval.description}
              metadata={pendingApproval.metadata}
              diffContent={pendingApproval.diffContent}
              commandPreview={pendingApproval.commandPreview}
              onApprove={handleApprove}
              onDeny={handleDeny}
              onAllowForSession={pendingApproval.allowSessionApproval ? handleAllowForSession : undefined}
            />
          </div>
        )}

        {/* Process input prompt — shown when ask_user_input tool requests user input */}
        {pendingProcessInput && (
          <ProcessInputView
            processId={pendingProcessInput.processId}
            description={pendingProcessInput.description}
            prompt={pendingProcessInput.prompt}
            command={pendingProcessInput.command}
            onSubmit={resolveProcessInput}
          />
        )}

        {/* Rollback events */}
        {rollbackEvents.map(rb => (
          <ErrorBoundary key={rb.id}>
            <RollbackCard rollback={rb} />
          </ErrorBoundary>
        ))}

        {/* Plan — global widget for live interactive approve/revise flow.
            On resume, the plan renders inline via PLAN_UPDATE messages above. */}
        {plan && !plan.approved && <PlanSummaryCard plan={plan} />}
        <PlanProgressWidget />

        {/* Questions */}
        {questions && questions.length > 0 && (
          <div ref={questionsRef}>
            <QuestionView questions={questions} activeIndex={activeQuestionIndex} />
          </div>
        )}

        {/* Queued steering messages — shown above the working indicator */}
        {queuedSteeringMessages.map((msg) => (
          <div
            key={msg.id}
            className="mx-3 my-1.5 flex items-start gap-2 animate-[fade-in_200ms_ease-out]"
          >
            <div
              className="flex-1 rounded-xl px-4 py-2.5 text-[13px] border"
              style={{
                background: 'color-mix(in srgb, var(--user-bg) 60%, transparent)',
                borderColor: 'var(--border)',
                color: 'var(--fg-secondary)',
              }}
            >
              <div className="flex items-center gap-2 mb-1">
                <span
                  className="inline-block w-1.5 h-1.5 rounded-full animate-pulse"
                  style={{ background: 'var(--accent-blue, #60a5fa)' }}
                />
                <span className="text-[10px] font-medium" style={{ color: 'var(--accent-blue, #60a5fa)' }}>
                  Queued
                </span>
              </div>
              <span>{msg.text}</span>
            </div>
            <button
              onClick={() => (window as any)._cancelSteering?.(msg.id)}
              className="flex-shrink-0 mt-2 p-1 rounded hover:opacity-80 transition-opacity"
              style={{ color: 'var(--fg-muted)' }}
              title="Cancel and return to input"
              aria-label="Cancel queued message and return to input"
            >
              <svg width="14" height="14" viewBox="0 0 16 16" fill="none" aria-hidden="true">
                <path d="M4 4l8 8M12 4l-8 8" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" />
              </svg>
            </button>
          </div>
        ))}

        {/* Working indicator — always last content item while agent is active */}
        {showWorkingIndicator && <WorkingIndicator />}

        {/* Retry / Continue button — shown after agent failure */}
        {retryState && !busy && (
          <div className="flex items-center gap-2 px-3 py-2 animate-[fade-in_200ms_ease-out]">
            <button
              className="flex items-center gap-1.5 rounded-md px-3 py-1.5 text-[12px] font-medium transition-colors"
              style={{
                color: 'var(--accent, #6366f1)',
                backgroundColor: 'var(--hover-overlay, rgba(255,255,255,0.03))',
                border: '1px solid var(--border)',
              }}
              onClick={() => {
                // Clear locally first to prevent double-click and avoid a stale pill
                // if the bridge round-trip is slow.
                useChatStore.setState({ retryState: null });
                import('@/bridge/jcef-bridge').then(({ kotlinBridge }) => {
                  kotlinBridge.retryLastTask();
                });
              }}
            >
              <svg width="12" height="12" viewBox="0 0 16 16" fill="none" xmlns="http://www.w3.org/2000/svg">
                <path d="M2 8a6 6 0 0 1 10.5-4M14 8a6 6 0 0 1-10.5 4" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round"/>
                <path d="M12 1v3.5h-3.5M4 15v-3.5h3.5" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round"/>
              </svg>
              {retryState.kind === 'continue' ? 'Continue' : 'Retry'}
            </button>
            <span className="text-[11px] truncate max-w-[300px]" style={{ color: 'var(--fg-muted)' }}>
              {retryState.caption}
            </span>
          </div>
        )}

        {/* Resume bar — shown when viewing a previous session that can be continued */}
        {resumeSessionId && !busy && (
          <div
            className="mx-3 my-2 flex items-center gap-3 rounded-lg px-4 py-3 animate-[fade-in_200ms_ease-out]"
            style={{
              backgroundColor: 'var(--hover-overlay, rgba(255,255,255,0.03))',
              border: '1px solid var(--border)',
            }}
          >
            <span className="text-[12px] flex-1" style={{ color: 'var(--fg-muted)' }}>
              This session was interrupted. You can continue where it left off.
            </span>
            <button
              className="flex items-center gap-1.5 rounded-md px-3 py-1.5 text-[12px] font-medium transition-colors"
              style={{
                color: '#fff',
                backgroundColor: 'var(--accent, #6366f1)',
              }}
              onClick={() => {
                import('@/bridge/jcef-bridge').then(({ kotlinBridge }) => {
                  kotlinBridge.resumeViewedSession();
                });
              }}
            >
              <svg width="12" height="12" viewBox="0 0 16 16" fill="none">
                <path d="M5 3l8 5-8 5V3z" fill="currentColor" />
              </svg>
              Resume
            </button>
          </div>
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
