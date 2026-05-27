import { memo, useCallback, useRef, useEffect, useState, useMemo } from 'react';
import { useChatStore, selectActiveSubAgents } from '@/stores/chatStore';
import { useShallow } from 'zustand/react/shallow';
import { AgentMessage, AnsweredQuestionsCard } from './AgentMessage';
import { ChatFooter } from './ChatFooter';
import { ErrorBoundary } from './ErrorBoundary';
import { ToolCallChain } from '@/components/agent/ToolCallChain';
import { SubAgentView } from '@/components/agent/SubAgentView';
import { CompactionMarker } from '@/components/agent/CompactionMarker';
import { CompactionOverlay } from '@/components/agent/CompactionOverlay';
import { ArtifactRenderer } from '@/components/rich/ArtifactRenderer';
import { ThinkingView } from '@/components/agent/ThinkingView';
import { CompletionCard } from '@/components/agent/CompletionCard';
import { PlanSummaryCard } from '@/components/agent/PlanSummaryCard';
import type { UiMessage, ToolCall, Plan } from '@/bridge/types';
import { MessageList, type MessageListHandle } from '@/components/chat/MessageList';
import { ScrollButton } from '@/components/ui/prompt-kit/scroll-button';

export const ChatView = memo(function ChatView() {
  const messages = useChatStore(s => s.messages);
  const streamingText = useChatStore(s => s.streamingText);
  const activeSubAgents = useChatStore(useShallow(selectActiveSubAgents));
  const editorTabMode = useChatStore(s => s.editorTabMode);

  const messageListRef = useRef<MessageListHandle>(null);
  const wasStreamingRef = useRef(false);
  const [isAtBottom, setIsAtBottom] = useState(true);

  // When streaming ends on a tall response, scroll to its TOP so the user can
  // read from the start instead of landing at the bottom. The just-finalized
  // message is at messages.length-1.
  useEffect(() => {
    const isStreaming = streamingText != null;
    const wasStreaming = wasStreamingRef.current;
    wasStreamingRef.current = isStreaming;
    if (!wasStreaming || isStreaming) return;

    const lastIndex = useChatStore.getState().messages.length - 1;
    if (lastIndex < 0) return;

    // Defer one frame so Virtuoso has materialized the now-finalized item.
    const raf = requestAnimationFrame(() => {
      const scroller = document.querySelector('[role="log"]');
      const item = document.querySelector(`[data-item-index="${lastIndex}"]`);
      if (!scroller || !(item instanceof HTMLElement)) return;
      const viewportHeight = scroller.clientHeight || window.innerHeight;
      if (item.offsetHeight > viewportHeight * 0.6) {
        messageListRef.current?.scrollToIndexStart(lastIndex);
      }
    });
    return () => cancelAnimationFrame(raf);
  }, [streamingText]);

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

  const renderItem = useCallback((index: number) => {
    const item = renderItems[index];
    if (!item) return null;

    if (item.kind === 'toolGroup') {
      const toolCalls: ToolCall[] = item.tools.map(t => ({
        id: t.toolCallData!.toolCallId,
        name: t.toolCallData!.toolName,
        args: t.toolCallData!.args ?? '',
        status: (t.toolCallData!.status as any) ?? 'COMPLETED',
        result: t.toolCallData!.result,
        output: t.toolCallData!.output,
        durationMs: t.toolCallData!.durationMs,
        diff: t.toolCallData!.diff,
        imageRefs: t.toolCallData!.imageRefs,
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

    if (msg.say === 'COMPACTION_MARKER' && msg.compactionMarker) {
      return (
        <ErrorBoundary key={key}>
          <CompactionMarker payload={msg.compactionMarker} />
        </ErrorBoundary>
      );
    }

    if (msg.say === 'SUBAGENT_STARTED' || msg.say === 'SUBAGENT_PROGRESS' || msg.say === 'SUBAGENT_COMPLETED') {
      // subagentData IS the SubAgentState (single source of truth since P4.T2).
      // activeSubAgents is a derived selector that reads from the same messages[].
      const subAgentState = activeSubAgents.get(msg.subagentData?.agentId ?? '') ?? msg.subagentData;
      if (!subAgentState) return null;
      return (
        <ErrorBoundary key={key}>
          <SubAgentView subAgent={subAgentState} />
        </ErrorBoundary>
      );
    }

    if (msg.say === 'ARTIFACT_RESULT' && msg.text) {
      return (
        <ErrorBoundary key={key}>
          <ArtifactRenderer source={msg.text} title="Artifact" renderId={msg.artifactId} />
        </ErrorBoundary>
      );
    }

    if (msg.say === 'REASONING') {
      return (
        <ErrorBoundary key={key}>
          <ThinkingView content={msg.text ?? ''} isStreaming={false} durationMs={msg.thinkingDurationMs} />
        </ErrorBoundary>
      );
    }

    if (msg.ask === 'COMPLETION_RESULT') {
      return (
        <ErrorBoundary key={key}>
          <CompletionCard data={msg.completionData ?? { kind: 'done' as const, result: msg.text ?? '' }} />
        </ErrorBoundary>
      );
    }

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

    if (msg.say === 'ERROR' || msg.say === 'STATUS' || msg.say === 'CONTEXT_COMPRESSED' ||
        msg.say === 'MEMORY_SAVED' || msg.say === 'STEERING_RECEIVED') {
      return (
        <div key={key} className="px-1 py-0.5 text-[11px]" style={{ color: 'var(--fg-muted, #888)' }}>
          {msg.text}
        </div>
      );
    }

    if (msg.ask === 'RESUME_TASK' || msg.ask === 'RESUME_COMPLETED_TASK') {
      return (
        <div key={key} className="px-1 py-0.5 text-[11px]" style={{ color: 'var(--fg-muted, #888)' }}>
          {msg.text || 'Session resumed'}
        </div>
      );
    }

    if (msg.ask === 'FOLLOWUP') {
      return (
        <ErrorBoundary key={key}>
          <AgentMessage message={msg} />
        </ErrorBoundary>
      );
    }

    // Plan updates render inline as a snapshot so they appear in chronological
    // order on resume. The live interactive plan lives in ChatFooter.
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

    if (msg.say === 'USER_MESSAGE' || msg.say === 'TEXT') {
      return (
        <ErrorBoundary key={key}>
          <AgentMessage message={msg} />
        </ErrorBoundary>
      );
    }

    if (msg.text) {
      return (
        <ErrorBoundary key={key}>
          <AgentMessage message={msg} />
        </ErrorBoundary>
      );
    }

    return null;
  }, [renderItems, activeSubAgents]);

  // Stable identity per row so Virtuoso keeps its measured-height cache across
  // re-renders (keeps the scrollbar thumb tracking the cursor while dragging).
  // Messages key on their unique ts; a tool group keys on its first tool's ts
  // (stable — unlike the start index, which shifts as rows are added/removed).
  const computeItemKey = useCallback((index: number) => {
    const item = renderItems[index];
    if (!item) return index;
    return item.kind === 'message' ? `m-${item.msg.ts}` : `tg-${item.tools[0]!.ts}`;
  }, [renderItems]);

  // Editor-tab mode: bypass Virtuoso so the single block isn't capped at its
  // natural content height. We render every item in a flex column where the
  // last one — the visualization — gets `flex-1` and absorbs all remaining
  // height. This is what lets the artifact iframe / mermaid SVG / etc. fill
  // the editor pane like a webpage.
  if (editorTabMode) {
    return (
      <div className="relative flex-1 min-h-0 flex flex-col">
        {renderItems.map((item, i) => (
          <div
            key={item.kind === 'message' ? `m-${item.msg.ts}` : `tg-${item.idx}`}
            className={i === renderItems.length - 1 ? 'flex-1 min-h-0 flex flex-col' : ''}
          >
            {renderItem(i)}
          </div>
        ))}
      </div>
    );
  }

  return (
    <div className="relative flex-1 min-h-0 flex flex-col">
      <CompactionOverlay />
      <div className="relative flex-1 min-h-0">
        <MessageList
          ref={messageListRef}
          count={renderItems.length}
          renderItem={renderItem}
          computeItemKey={computeItemKey}
          Footer={ChatFooter}
          atBottomChange={setIsAtBottom}
          ariaLabel="Agent chat messages"
        />
        <div className="absolute bottom-4 left-1/2 -translate-x-1/2 z-10">
          <ScrollButton
            atBottom={isAtBottom}
            onClick={() => messageListRef.current?.scrollToBottom()}
          />
        </div>
      </div>
    </div>
  );
});
