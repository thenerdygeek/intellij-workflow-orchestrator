/**
 * Bug #14 — history bulk-delete had no confirmation and `selectedIds` could
 * diverge from the filtered (visible) list.
 *
 *  - Destructive: `handleBulkDelete` fired `bulkDeleteSessions` immediately for
 *    N sessions with no confirm step.
 *  - Divergence: a selection made before a filter narrowed the list could delete
 *    sessions that are no longer visible — "what's deleted" ≠ "what's shown".
 *
 * Fix: a confirmation step before deletion, and the delete payload + count are
 * reconciled against the currently-visible (filtered) selection.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, act, within } from '@testing-library/react';
import { HistoryView } from '@/components/history/HistoryView';
import { useChatStore } from '@/stores/chatStore';
import type { HistoryItem } from '@/bridge/types';

// P1-16: HistoryView uses Virtuoso; replace with a shim that renders all items.
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
    bulkDeleteSessions: vi.fn(),
    startNewSession: vi.fn(),
    exportAllSessions: vi.fn(),
    showSession: vi.fn(),
    deleteSession: vi.fn(),
    toggleFavorite: vi.fn(),
    exportSession: vi.fn(),
  },
}));
import { kotlinBridge } from '@/bridge/jcef-bridge';

function item(id: string, task: string): HistoryItem {
  return { id, ts: 1, task, tokensIn: 0, tokensOut: 0, totalCost: 0, isFavorited: false };
}

beforeEach(() => {
  vi.clearAllMocks();
  act(() => {
    useChatStore.getState().setHistoryItems([item('a', 'Alpha'), item('b', 'Beta'), item('c', 'Gamma')]);
    useChatStore.getState().setHistorySearch('');
  });
});

describe('Bug #14 — bulk delete confirms and respects the visible selection', () => {
  it('requires confirmation before deleting, then deletes only the visible selection', () => {
    render(<HistoryView />);

    // Enter selection mode and select everything currently shown.
    fireEvent.click(screen.getByRole('button', { name: /^select$/i }));
    fireEvent.click(screen.getByRole('button', { name: /select all/i }));

    // Narrow the list so only "Alpha" (id 'a') is visible; b/c become hidden.
    act(() => useChatStore.getState().setHistorySearch('Alpha'));

    // First click must NOT delete — it asks for confirmation.
    fireEvent.click(screen.getByRole('button', { name: /delete selected/i }));
    expect(kotlinBridge.bulkDeleteSessions).not.toHaveBeenCalled();

    // Confirm inside the confirmation dialog.
    const dialog = screen.getByRole('alertdialog');
    fireEvent.click(within(dialog).getByRole('button', { name: /delete/i }));
    expect(kotlinBridge.bulkDeleteSessions).toHaveBeenCalledTimes(1);

    // Only the visible+selected id is deleted — not the hidden b/c.
    const payload = JSON.parse((kotlinBridge.bulkDeleteSessions as any).mock.calls[0][0]);
    expect(payload).toEqual(['a']);
  });

  it('cancelling the confirmation deletes nothing', () => {
    render(<HistoryView />);
    fireEvent.click(screen.getByRole('button', { name: /^select$/i }));
    fireEvent.click(screen.getByRole('button', { name: /select all/i }));
    fireEvent.click(screen.getByRole('button', { name: /delete selected/i }));

    const dialog = screen.getByRole('alertdialog');
    fireEvent.click(within(dialog).getByRole('button', { name: /cancel/i }));
    expect(kotlinBridge.bulkDeleteSessions).not.toHaveBeenCalled();
  });
});
