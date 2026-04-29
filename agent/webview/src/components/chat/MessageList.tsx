import { forwardRef, useImperativeHandle, useRef, type ReactNode } from 'react';
import { Virtuoso, type VirtuosoHandle } from 'react-virtuoso';

export interface MessageListHandle {
  scrollToBottom(): void;
}

interface MessageListProps {
  count: number;
  renderItem: (index: number) => ReactNode;
  /** Trailing UI rendered below the last message (working indicator, scroll anchor, etc.). */
  footer?: ReactNode;
  /** Fires with `true` when the viewport reaches the bottom, `false` when the user scrolls up. */
  atBottomChange?: (atBottom: boolean) => void;
  /** aria-label for the scrolling region. */
  ariaLabel?: string;
}

export const MessageList = forwardRef<MessageListHandle, MessageListProps>(function MessageList(
  { count, renderItem, footer, atBottomChange, ariaLabel },
  ref,
) {
  const virtuosoRef = useRef<VirtuosoHandle>(null);

  useImperativeHandle(ref, () => ({
    scrollToBottom() {
      virtuosoRef.current?.scrollToIndex({
        index: 'LAST',
        align: 'end',
        behavior: 'smooth',
      });
    },
  }), []);

  return (
    <Virtuoso
      ref={virtuosoRef}
      className="flex-1 min-h-0"
      role="log"
      aria-live="polite"
      aria-label={ariaLabel ?? 'Agent chat messages'}
      totalCount={count}
      itemContent={renderItem}
      followOutput="auto"
      atBottomThreshold={120}
      atBottomStateChange={atBottomChange}
      increaseViewportBy={{ top: 400, bottom: 400 }}
      components={footer ? { Footer: () => <>{footer}</> } : undefined}
    />
  );
});
