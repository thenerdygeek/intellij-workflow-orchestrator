/**
 * Scroll stability — ChatView must feed Virtuoso a STABLE, position-independent
 * computeItemKey so it keeps its measured-height cache across the chat's frequent
 * re-renders. Without it, Virtuoso re-estimates total height on every update and
 * the scrollbar thumb drifts away from the cursor while dragging.
 *
 * Can't assert the scroll *feel* in jsdom, but we can pin the contract that makes
 * it correct: each row's key is derived from its message ts (not its index), so
 * the same logical message keeps the same key as rows are added.
 */
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, act } from '@testing-library/react';
import type { UiMessage } from '@/bridge/types';
import { useChatStore } from '@/stores/chatStore';

// Capture the props Virtuoso receives so we can exercise computeItemKey.
const captured: { computeItemKey?: (i: number) => string | number; totalCount?: number } = {};
vi.mock('react-virtuoso', () => ({
  Virtuoso: (props: any) => {
    captured.computeItemKey = props.computeItemKey;
    captured.totalCount = props.totalCount;
    return <div role="log" />;
  },
}));
import { ChatView } from '@/components/chat/ChatView';

beforeEach(() => {
  act(() => useChatStore.getState().clearChat?.());
  captured.computeItemKey = undefined;
});

describe('message list scroll stability (computeItemKey)', () => {
  it('keys rows by message ts, distinct per row, and stable as rows are appended', () => {
    act(() => useChatStore.getState().hydrateFromUiMessages([
      { ts: 100, type: 'SAY', say: 'USER_MESSAGE', text: 'first' } as UiMessage,
      { ts: 200, type: 'SAY', say: 'TEXT', text: 'second' } as UiMessage,
    ]));
    render(<ChatView />);

    const key = captured.computeItemKey!;
    expect(key(0)).toBe('m-100');
    expect(key(1)).toBe('m-200');
    expect(key(0)).not.toBe(key(1));

    // Append a new message — the first row's key must NOT change (height cache
    // would otherwise be discarded → the jumpy thumb).
    act(() => useChatStore.getState().addUserMessage('third'));
    expect(captured.totalCount).toBe(3);
    expect(captured.computeItemKey!(0)).toBe('m-100');
  });
});
