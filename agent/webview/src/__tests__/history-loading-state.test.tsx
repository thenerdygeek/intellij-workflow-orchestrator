/**
 * GLITCH-2 — History view showed "No sessions yet" (or blank star-only cards)
 * while the async disk read was in flight, because there was no `historyLoading`
 * flag to distinguish "loading" from "truly empty".
 *
 * Fix: `historyLoading: boolean` added to chatStore.  HistoryView renders a
 * "Loading conversations…" affordance whenever `historyLoading` is true,
 * regardless of whether `historyItems` is empty or stale.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, act } from '@testing-library/react';
import { HistoryView } from '@/components/history/HistoryView';
import { useChatStore } from '@/stores/chatStore';
import type { HistoryItem } from '@/bridge/types';

// HistoryView uses Virtuoso — replace with a shim that renders all items.
vi.mock('react-virtuoso', () => ({
  Virtuoso: ({
    totalCount,
    itemContent,
    computeItemKey,
    components,
  }: {
    totalCount: number;
    itemContent: (i: number) => React.ReactNode;
    computeItemKey?: (i: number) => string | number;
    components?: { Header?: React.ComponentType; Footer?: React.ComponentType };
  }) => {
    const Header = components?.Header;
    const Footer = components?.Footer;
    return (
      <div>
        {Header && <Header />}
        {Array.from({ length: totalCount }, (_, i) => (
          <div key={computeItemKey ? computeItemKey(i) : i}>{itemContent(i)}</div>
        ))}
        {Footer && <Footer />}
      </div>
    );
  },
}));

vi.mock('@/bridge/jcef-bridge', () => ({
  kotlinBridge: {
    startNewSession: vi.fn(),
    showSession: vi.fn(),
    deleteSession: vi.fn(),
    toggleFavorite: vi.fn(),
    exportSession: vi.fn(),
    exportAllSessions: vi.fn(),
    bulkDeleteSessions: vi.fn(),
  },
}));

function makeItem(id: string, task: string): HistoryItem {
  return { id, ts: 1, task, tokensIn: 0, tokensOut: 0, totalCost: 0, isFavorited: false };
}

beforeEach(() => {
  act(() => {
    useChatStore.getState().setHistoryItems([]);
    useChatStore.getState().setHistoryLoading(false);
    useChatStore.getState().setHistorySearch('');
  });
});

describe('GLITCH-2: History loading state', () => {
  it('shows loading affordance (not "No sessions yet") while historyLoading is true with empty items', () => {
    act(() => {
      useChatStore.getState().setHistoryItems([]);
      useChatStore.getState().setHistoryLoading(true);
    });

    render(<HistoryView />);

    expect(screen.getByTestId('history-loading')).toBeInTheDocument();
    expect(screen.getByText('Loading conversations…')).toBeInTheDocument();
    expect(screen.queryByText('No sessions yet')).not.toBeInTheDocument();
  });

  it('shows "No sessions yet" only after loading completes with empty items', () => {
    act(() => {
      useChatStore.getState().setHistoryItems([]);
      useChatStore.getState().setHistoryLoading(false);
    });

    render(<HistoryView />);

    expect(screen.queryByTestId('history-loading')).not.toBeInTheDocument();
    expect(screen.getByText('No sessions yet')).toBeInTheDocument();
  });

  it('shows real session cards (not loading affordance) once items arrive', () => {
    act(() => {
      useChatStore.getState().setHistoryItems([
        makeItem('s1', 'Fix the login bug'),
        makeItem('s2', 'Add dark mode'),
      ]);
      useChatStore.getState().setHistoryLoading(false);
    });

    render(<HistoryView />);

    expect(screen.queryByTestId('history-loading')).not.toBeInTheDocument();
    expect(screen.queryByText('No sessions yet')).not.toBeInTheDocument();
    expect(screen.getByText('Fix the login bug')).toBeInTheDocument();
    expect(screen.getByText('Add dark mode')).toBeInTheDocument();
  });

  it('shows loading affordance even if stale items are still in the store (historyLoading=true)', () => {
    // Stale items with empty task — the original "blank skeleton with star" scenario.
    act(() => {
      useChatStore.getState().setHistoryItems([makeItem('stale', '')]);
      useChatStore.getState().setHistoryLoading(true);
    });

    render(<HistoryView />);

    expect(screen.getByTestId('history-loading')).toBeInTheDocument();
    expect(screen.getByText('Loading conversations…')).toBeInTheDocument();
    // No card should be rendered while loading
    expect(screen.queryByText('No sessions yet')).not.toBeInTheDocument();
  });

  it('transitions: loading affordance disappears and cards appear when load completes', () => {
    act(() => {
      useChatStore.getState().setHistoryItems([]);
      useChatStore.getState().setHistoryLoading(true);
    });

    const { rerender } = render(<HistoryView />);

    // Loading state
    expect(screen.getByTestId('history-loading')).toBeInTheDocument();

    // Simulate loadSessionHistory arriving from Kotlin
    act(() => {
      useChatStore.getState().setHistoryItems([makeItem('s1', 'Refactor auth module')]);
      useChatStore.getState().setHistoryLoading(false);
    });
    rerender(<HistoryView />);

    expect(screen.queryByTestId('history-loading')).not.toBeInTheDocument();
    expect(screen.getByText('Refactor auth module')).toBeInTheDocument();
  });
});
