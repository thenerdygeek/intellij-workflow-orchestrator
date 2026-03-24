import { memo } from 'react';
import { useChatStore } from '@/stores/chatStore';
import { AgentMessage } from './AgentMessage';
import { ToolCallView } from '@/components/agent/ToolCallView';
import { PlanSummaryCard } from '@/components/agent/PlanSummaryCard';
import { PlanProgressWidget } from '@/components/agent/PlanProgressWidget';
import { QuestionView } from '@/components/agent/QuestionView';
import { useAutoScroll } from '@/hooks/useAutoScroll';
import { useVirtualScroll } from '@/hooks/useVirtualScroll';
import type { Message } from '@/bridge/types';

const VIRTUAL_SCROLL_THRESHOLD = 100;

export const ChatView = memo(function ChatView() {
  const messages = useChatStore(s => s.messages);
  const activeStream = useChatStore(s => s.activeStream);
  const activeToolCalls = useChatStore(s => s.activeToolCalls);
  const plan = useChatStore(s => s.plan);
  const questions = useChatStore(s => s.questions);
  const activeQuestionIndex = useChatStore(s => s.activeQuestionIndex);

  const messageCount = messages.length + (activeStream ? 1 : 0);
  const useVirtual = messageCount >= VIRTUAL_SCROLL_THRESHOLD;

  const {
    containerRef,
    isScrolledUp,
    hasNewMessages,
    scrollToBottom,
  } = useAutoScroll({ dependency: messageCount });

  const {
    parentRef,
    virtualItems,
    totalSize,
    measureElement,
  } = useVirtualScroll({
    count: messageCount,
    enabled: useVirtual,
  });

  const setRef = (el: HTMLDivElement | null) => {
    (containerRef as React.MutableRefObject<HTMLDivElement | null>).current = el;
    (parentRef as React.MutableRefObject<HTMLDivElement | null>).current = el;
  };

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

  const renderMessage = (index: number) => {
    if (index < messages.length) {
      return (
        <AgentMessage
          key={messages[index]!.id}
          message={messages[index]!}
        />
      );
    }
    if (streamPlaceholder) {
      return (
        <AgentMessage
          key="__streaming__"
          message={streamPlaceholder}
          isStreaming={activeStream?.isStreaming ?? false}
          streamText={activeStream?.text}
        />
      );
    }
    return null;
  };

  return (
    <div className="relative flex-1 overflow-hidden">
      <div
        ref={setRef}
        className="h-full overflow-y-auto scroll-smooth px-4 py-3"
        role="log"
        aria-live="polite"
        aria-label="Agent chat messages"
      >
        {useVirtual ? (
          <div
            style={{
              height: `${totalSize}px`,
              width: '100%',
              position: 'relative',
            }}
          >
            {virtualItems.map(virtualItem => (
              <div
                key={virtualItem.key}
                data-index={virtualItem.index}
                ref={measureElement}
                style={{
                  position: 'absolute',
                  top: 0,
                  left: 0,
                  width: '100%',
                  transform: `translateY(${virtualItem.start}px)`,
                }}
              >
                <div className="py-1.5">
                  {renderMessage(virtualItem.index)}
                </div>
              </div>
            ))}
          </div>
        ) : (
          <div className="flex flex-col gap-3">
            {messages.map((msg, i) => (
              <div
                key={msg.id}
                style={{ animationDelay: `${Math.min(i * 40, 200)}ms` }}
              >
                <AgentMessage message={msg} />
              </div>
            ))}

            {/* Active tool calls */}
            {toolCallsArray.map((tc, idx) => (
              <ToolCallView
                key={tc.name + idx}
                toolCall={tc}
                isLatest={idx === toolCallsArray.length - 1}
              />
            ))}

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
          </div>
        )}
      </div>

      {/* Scroll to bottom */}
      {isScrolledUp && (
        <button
          onClick={() => scrollToBottom(true)}
          className="
            absolute bottom-4 left-1/2 -translate-x-1/2
            flex items-center gap-2 rounded-full
            border px-3 py-1.5 text-[12px]
            shadow-md backdrop-blur-sm
            transition-all duration-200
            animate-[message-enter_200ms_ease_both]
          "
          style={{
            borderColor: 'var(--border)',
            backgroundColor: 'var(--toolbar-bg)',
            color: 'var(--fg)',
          }}
        >
          <svg
            width="14"
            height="14"
            viewBox="0 0 16 16"
            fill="none"
            style={{ color: 'var(--fg-muted)' }}
          >
            <path
              d="M8 3v10m0 0l-4-4m4 4l4-4"
              stroke="currentColor"
              strokeWidth="1.5"
              strokeLinecap="round"
              strokeLinejoin="round"
            />
          </svg>
          {hasNewMessages ? 'New messages' : 'Scroll to bottom'}
        </button>
      )}
    </div>
  );
});
