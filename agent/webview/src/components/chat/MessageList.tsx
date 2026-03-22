import { useChatStore } from '@/stores/chatStore';
import { MessageCard } from './MessageCard';
import { ToolCallList } from '@/components/agent/ToolCallList';
import { useAutoScroll } from '@/hooks/useAutoScroll';
import { useVirtualScroll } from '@/hooks/useVirtualScroll';

const VIRTUAL_SCROLL_THRESHOLD = 100;

export function MessageList() {
  const messages = useChatStore(s => s.messages);
  const activeStream = useChatStore(s => s.activeStream);
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

  const allItems = [...messages];
  const streamPlaceholder = activeStream
    ? {
        id: '__streaming__',
        role: 'agent' as const,
        content: activeStream.text,
        timestamp: Date.now(),
      }
    : null;

  const renderMessage = (index: number) => {
    if (index < messages.length) {
      return (
        <MessageCard
          key={messages[index]!.id}
          message={messages[index]!}
        />
      );
    }
    if (streamPlaceholder) {
      return (
        <MessageCard
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
            {allItems.map((msg, i) => (
              <div
                key={msg.id}
                style={{ animationDelay: `${Math.min(i * 40, 200)}ms` }}
              >
                <MessageCard message={msg} />
              </div>
            ))}
            <ToolCallList />
            {streamPlaceholder && (
              <MessageCard
                key="__streaming__"
                message={streamPlaceholder}
                isStreaming={activeStream?.isStreaming ?? false}
                streamText={activeStream?.text}
              />
            )}
          </div>
        )}
      </div>

      {isScrolledUp && (
        <button
          onClick={() => scrollToBottom(true)}
          className="
            absolute bottom-4 left-1/2 -translate-x-1/2
            flex items-center gap-2 rounded-full
            border border-[var(--border)] bg-[var(--toolbar-bg)]
            px-3 py-1.5 text-[12px] text-[var(--fg)]
            shadow-md backdrop-blur-sm
            transition-all duration-200
            hover:border-[var(--accent,#6366f1)] hover:shadow-lg
            animate-[message-enter_200ms_ease_both]
          "
        >
          <svg
            width="14"
            height="14"
            viewBox="0 0 16 16"
            fill="none"
            className="text-[var(--fg-muted)]"
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
}
