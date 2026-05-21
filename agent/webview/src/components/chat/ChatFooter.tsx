import { memo, useCallback, useEffect, useRef } from 'react';
import { useChatStore } from '@/stores/chatStore';
import { AgentMessage } from './AgentMessage';
import { ErrorBoundary } from './ErrorBoundary';
import { WorkingIndicator } from './WorkingIndicator';
import { ToolCallChain } from '@/components/agent/ToolCallChain';
import { ApprovalView } from '@/components/agent/ApprovalView';
import { ProcessInputView } from '@/components/agent/ProcessInputView';
import { PlanSummaryCard } from '@/components/agent/PlanSummaryCard';
import { PlanProgressWidget } from '@/components/agent/PlanProgressWidget';
import { QuestionView } from '@/components/agent/QuestionView';
import { ThinkingView } from '@/components/agent/ThinkingView';
import { StreamingEditPreviewView } from '@/components/agent/StreamingEditPreviewView';

/**
 * Trailing UI that flows below the last finalized message inside the
 * scrolling region: streaming bubble, active tool calls, approval gate,
 * process input, plan, questions, queued steering, working indicator,
 * retry, resume.
 *
 * Architectural contract:
 * - Mounted exactly once as Virtuoso's `components.Footer` so it never
 *   remounts on parent re-render. Internal state (e.g. typewriter
 *   animations, expand/collapse flags inside `ToolCallChain`) survives
 *   message-list churn.
 * - Subscribes directly to chatStore. Never receive content via prop —
 *   prop forwarding through Virtuoso's urx store does not flush reliably
 *   under high-frequency streaming, which is why the previous
 *   `Footer` + `context` approach was abandoned.
 * - Owns its own refs and scroll-into-view side effects so callers don't
 *   need to thread refs through MessageList.
 */
export const ChatFooter = memo(function ChatFooter() {
  const streamingText = useChatStore(s => s.streamingText);
  const streamingMsgTs = useChatStore(s => s.streamingMsgTs);
  const streamingThinkingText = useChatStore(s => s.streamingThinkingText);
  const streamingThinkingTs = useChatStore(s => s.streamingThinkingTs);
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
  const queuedSteeringMessages = useChatStore(s => s.queuedSteeringMessages);
  const resumeSessionId = useChatStore(s => s.resumeSessionId);

  const approvalRef = useRef<HTMLDivElement>(null);
  const questionsRef = useRef<HTMLDivElement>(null);

  const handleApprove = useCallback(() => resolveApproval('approve'), [resolveApproval]);
  const handleDeny = useCallback(() => resolveApproval('deny'), [resolveApproval]);
  const handleAllowForSession = useCallback(() => resolveApproval('allowForSession'), [resolveApproval]);

  const toolCallsArray = Array.from(activeToolCalls.values());

  useEffect(() => {
    if (questions && questions.length > 0 && questionsRef.current) {
      questionsRef.current.scrollIntoView({ behavior: 'smooth', block: 'start' });
    }
  }, [questions]);

  useEffect(() => {
    if (pendingApproval && approvalRef.current) {
      approvalRef.current.scrollIntoView({ behavior: 'smooth', block: 'end' });
    }
  }, [pendingApproval]);

  useEffect(() => {
    const handler = () => {
      approvalRef.current?.scrollIntoView({ behavior: 'smooth', block: 'end' });
    };
    document.addEventListener('scroll-to-approval', handler);
    return () => document.removeEventListener('scroll-to-approval', handler);
  }, []);

  return (
    <div className="px-4 pb-4 flex flex-col gap-3">
      {/* Live thinking bubble — rendered ABOVE the prose stream because the LLM
          typically emits <thinking>...</thinking> before any prose. Once the
          closing tag arrives, endThinking() moves this content into `messages`
          where ChatView renders it with isStreaming=false (auto-collapses). */}
      {streamingThinkingText != null && streamingThinkingTs != null && (
        <ErrorBoundary key={`streaming-thinking-${streamingThinkingTs}`}>
          <ThinkingView content={streamingThinkingText} isStreaming={true} />
        </ErrorBoundary>
      )}

      {streamingText != null && streamingMsgTs != null && (
        <ErrorBoundary key={`stream-${streamingMsgTs}`}>
          <AgentMessage
            message={{ ts: streamingMsgTs, type: 'SAY', say: 'TEXT', text: streamingText, partial: true }}
            isStreaming
          />
        </ErrorBoundary>
      )}

      {/* Live streaming-diff preview for in-flight edit_file tool calls. Renders
          one card per active preview from `chatStore.streamingEdits`; quiet when
          the map is empty. Sits below the prose stream and above the tool-call
          chain so the user sees the diff growing as the LLM emits new_string. */}
      <StreamingEditPreviewView />

      {toolCallsArray.length > 0 && <ToolCallChain toolCalls={toolCallsArray} />}

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
            originAgentId={pendingApproval.originAgentId}
            originLabel={pendingApproval.originLabel}
          />
        </div>
      )}

      {pendingProcessInput && (
        <ProcessInputView
          processId={pendingProcessInput.processId}
          description={pendingProcessInput.description}
          prompt={pendingProcessInput.prompt}
          command={pendingProcessInput.command}
          onSubmit={resolveProcessInput}
        />
      )}

      {plan && !plan.approved && <PlanSummaryCard plan={plan} />}
      <PlanProgressWidget />

      {questions && questions.length > 0 && (
        <div ref={questionsRef}>
          <QuestionView questions={questions} activeIndex={activeQuestionIndex} />
        </div>
      )}

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

      {busy && <WorkingIndicator />}

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
              // Set busy:true immediately so the working indicator + stop button
              // appear during the Kotlin-side cleanup window (cancelCurrentTask +
              // cleanEmptyArtifactsBeforeRetry + invokeLater dispatch). Without
              // this, the UI sat in limbo — retry banner gone, no spinner, no
              // stop button — for the multi-hundred-millisecond gap before
              // executeTask("continue", …) flips busy:true itself.
              useChatStore.setState({ retryState: null, busy: true });
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
    </div>
  );
});
