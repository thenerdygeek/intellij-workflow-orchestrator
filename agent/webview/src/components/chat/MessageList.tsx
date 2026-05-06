import { forwardRef, useImperativeHandle, useMemo, useRef, type ReactNode } from 'react';
import { Virtuoso, type ItemProps, type VirtuosoHandle } from 'react-virtuoso';

export interface MessageListHandle {
  scrollToBottom(): void;
  scrollToIndexStart(index: number): void;
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

// Per-item wrapper. Restores the `px-4` horizontal gutter and `gap-3` inter-item
// spacing that the pre-virtualization `<ChatContainerContent>` provided. Without
// this, bordered cards (CompletionCard, SubAgentView, etc.) sit flush against
// the scroller edges.
function ItemContainer({ children, ...props }: ItemProps<unknown>) {
  return (
    <div {...props} className="px-4 pb-3">
      {children}
    </div>
  );
}

// 12px spacer above the first item — replaces the old `py-3` top padding.
function HeaderSpacer() {
  return <div className="h-3" />;
}

// Dynamic footer content is delivered via Virtuoso's `context` prop, not by
// rebuilding `components.Footer`. If we passed `() => <>{footer}</>` here,
// React would see a different component type on every parent re-render and
// unmount the entire footer subtree — collapsing any `useState` inside (e.g.
// the expand/collapse state of the active ToolCallChain). Keeping `Footer`
// stable lets re-renders propagate normally without a remount.
type FooterContext = { footer: ReactNode };
function StableFooter({ context }: { context?: FooterContext }) {
  return <>{context?.footer ?? null}</>;
}

const STABLE_COMPONENTS = {
  Item: ItemContainer,
  Header: HeaderSpacer,
  Footer: StableFooter,
};

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
    scrollToIndexStart(index: number) {
      virtuosoRef.current?.scrollToIndex({
        index,
        align: 'start',
        behavior: 'smooth',
      });
    },
  }), []);

  const context = useMemo<FooterContext>(() => ({ footer }), [footer]);

  return (
    <Virtuoso<unknown, FooterContext>
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
      components={STABLE_COMPONENTS}
      context={context}
    />
  );
});
