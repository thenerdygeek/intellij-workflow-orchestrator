import { forwardRef, useImperativeHandle, useRef, type ReactNode } from 'react';
import { Virtuoso, type ItemProps, type VirtuosoHandle } from 'react-virtuoso';

export interface MessageListHandle {
  scrollToBottom(): void;
  scrollToIndexStart(index: number): void;
}

interface MessageListProps {
  count: number;
  renderItem: (index: number) => ReactNode;
  /** Trailing UI rendered below the virtualized list (streaming bubble, live ToolCallChain, approval gate, etc.). */
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

const STABLE_COMPONENTS = {
  Item: ItemContainer,
  Header: HeaderSpacer,
};

// `footer` is rendered as a plain sibling below Virtuoso, not via
// `components.Footer` + `context` plumbing. Reasoning:
//   1. The Virtuoso `Footer` slot routes dynamic content through its urx
//      reactive store, which under load (rapid `appendToolOutput` chunks
//      every ~16ms) was failing to flush a fresh `context.footer` to the
//      mounted Footer component — the active ToolCallChain's `TerminalContent`
//      was Zustand-subscribed and updating internally, but its parent
//      footer subtree never got the new ReactNode and the live tail
//      appeared frozen.
//   2. Putting `footer` outside the virtualized list keeps the no-remount
//      property that the `StableFooter` pattern was trying to win
//      (parent re-renders no longer recreate the footer's component type),
//      while letting plain React reconciliation deliver the latest
//      streaming bubble / ToolCallChain / approval gate.
//   3. The original engineering intent — "Streaming bubble lives outside
//      the virtualized list so per-token updates don't touch the message
//      list" (see ChatView.tsx) — is preserved: the footer never enters
//      Virtuoso's render pipeline.

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

  return (
    <div className="flex-1 min-h-0 flex flex-col">
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
        components={STABLE_COMPONENTS}
      />
      {footer != null && <div className="shrink-0">{footer}</div>}
    </div>
  );
});
