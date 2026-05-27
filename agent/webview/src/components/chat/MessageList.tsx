import { forwardRef, useImperativeHandle, useMemo, useRef, type ComponentType, type ReactNode } from 'react';
import { Virtuoso, type ItemProps, type VirtuosoHandle } from 'react-virtuoso';

export interface MessageListHandle {
  scrollToBottom(): void;
  scrollToIndexStart(index: number): void;
}

interface MessageListProps {
  count: number;
  renderItem: (index: number) => ReactNode;
  /**
   * Trailing component rendered at the bottom of the scrolling region,
   * after the last virtualized item. Must be a stable component reference
   * (defined at module scope or wrapped in `memo`) — Virtuoso mounts it
   * once and does not reliably forward fresh props through its urx store
   * under high-frequency updates. Subscribe to your application state
   * directly inside the component.
   */
  Footer?: ComponentType;
  /** Fires with `true` when the viewport reaches the bottom, `false` when the user scrolls up. */
  atBottomChange?: (atBottom: boolean) => void;
  /** aria-label for the scrolling region. */
  ariaLabel?: string;
  /**
   * Stable identity per item. Without it Virtuoso cannot keep its measured-height
   * cache across the chat's frequent re-renders (streaming tokens, tool updates),
   * so it re-estimates total height on every change and the scrollbar thumb drifts
   * away from the cursor while dragging. Keys MUST be stable per logical item.
   */
  computeItemKey?: (index: number) => string | number;
}

// Per-item wrapper. Restores the `px-4` horizontal gutter and `pb-3` inter-item
// spacing that the pre-virtualization `<ChatContainerContent>` provided.
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

const BASE_COMPONENTS = {
  Item: ItemContainer,
  Header: HeaderSpacer,
};

export const MessageList = forwardRef<MessageListHandle, MessageListProps>(function MessageList(
  { count, renderItem, Footer, atBottomChange, ariaLabel, computeItemKey },
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

  // Virtuoso's Footer slot is typed `ComponentType<ContextProp<Context>>`,
  // but our consumers pass parameter-less components. Wrap in an adapter
  // that swallows Virtuoso's context prop. The adapter identity is stable
  // per Footer, so Virtuoso never remounts the slot during a session.
  const components = useMemo(() => {
    if (!Footer) return BASE_COMPONENTS;
    const FooterAdapter = () => <Footer />;
    return { ...BASE_COMPONENTS, Footer: FooterAdapter };
  }, [Footer]);

  return (
    <Virtuoso
      ref={virtuosoRef}
      className="flex-1 min-h-0"
      role="log"
      aria-live="polite"
      aria-label={ariaLabel ?? 'Agent chat messages'}
      totalCount={count}
      itemContent={renderItem}
      computeItemKey={computeItemKey}
      // Closer initial estimate than Virtuoso's default (it would otherwise size
      // every unmeasured row to the first item's height — tiny for a one-line
      // status, wildly off for the code/markdown blocks that dominate a chat —
      // making the thumb overshoot the cursor on the first scroll-through).
      defaultItemHeight={96}
      followOutput="auto"
      atBottomThreshold={120}
      atBottomStateChange={atBottomChange}
      increaseViewportBy={{ top: 400, bottom: 400 }}
      components={components}
    />
  );
});
