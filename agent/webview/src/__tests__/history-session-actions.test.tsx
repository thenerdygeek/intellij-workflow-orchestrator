/**
 * Interaction coverage for the history view's per-session actions (SessionCard +
 * HistoryView wiring): resume, favorite, single-session delete-with-confirm,
 * export-all, and the delegated-resume metadata stash. Bulk delete is covered by
 * history-bulk-delete.test.tsx.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, act, within } from '@testing-library/react';
import { HistoryView } from '@/components/history/HistoryView';
import { useChatStore } from '@/stores/chatStore';
import type { HistoryItem, DelegationMetadata } from '@/bridge/types';

vi.mock('@/bridge/jcef-bridge', () => ({
  kotlinBridge: {
    showSession: vi.fn(),
    deleteSession: vi.fn(),
    toggleFavorite: vi.fn(),
    exportAllSessions: vi.fn(),
    exportSession: vi.fn(),
    startNewSession: vi.fn(),
    bulkDeleteSessions: vi.fn(),
  },
}));
import { kotlinBridge } from '@/bridge/jcef-bridge';

function item(id: string, task: string, extra: Partial<HistoryItem> = {}): HistoryItem {
  return { id, ts: Date.now(), task, tokensIn: 0, tokensOut: 0, totalCost: 0, isFavorited: false, ...extra };
}

beforeEach(() => {
  vi.clearAllMocks();
  act(() => {
    useChatStore.getState().setHistoryItems([item('a', 'Alpha'), item('b', 'Beta')]);
    useChatStore.getState().setHistorySearch('');
    useChatStore.getState().setActiveSessionDelegated(null);
  });
});

/** Activate a card (reveals its Resume/Delete action bar) by clicking its title. */
function activateCard(task: string) {
  fireEvent.click(screen.getByText(task));
}

describe('history per-session actions', () => {
  it('Resume opens the session via the bridge', () => {
    render(<HistoryView />);
    activateCard('Alpha');
    fireEvent.click(screen.getByRole('button', { name: /resume/i }));
    expect(kotlinBridge.showSession).toHaveBeenCalledWith('a');
  });

  it('toggling the favorite star calls the bridge', () => {
    act(() => useChatStore.getState().setHistoryItems([item('a', 'Alpha')]));
    render(<HistoryView />);
    fireEvent.click(screen.getByRole('button', { name: /favorite/i }));
    expect(kotlinBridge.toggleFavorite).toHaveBeenCalledWith('a');
  });

  it('single delete requires confirmation, then deletes', () => {
    render(<HistoryView />);
    activateCard('Alpha');
    fireEvent.click(screen.getByRole('button', { name: /^delete$/i }));
    // Confirmation appears; nothing deleted yet.
    expect(kotlinBridge.deleteSession).not.toHaveBeenCalled();
    expect(screen.getByText(/delete this session\?/i)).toBeInTheDocument();

    fireEvent.click(screen.getByRole('button', { name: /confirm/i }));
    expect(kotlinBridge.deleteSession).toHaveBeenCalledWith('a');
  });

  it('cancelling a single delete confirmation deletes nothing', () => {
    render(<HistoryView />);
    activateCard('Alpha');
    fireEvent.click(screen.getByRole('button', { name: /^delete$/i }));
    fireEvent.click(screen.getByRole('button', { name: /cancel/i }));
    expect(kotlinBridge.deleteSession).not.toHaveBeenCalled();
    // Resume affordance is back (confirmation dismissed).
    expect(screen.getByRole('button', { name: /resume/i })).toBeInTheDocument();
  });

  it('Export All exports via the bridge', () => {
    render(<HistoryView />);
    fireEvent.click(screen.getByRole('button', { name: /export all/i }));
    expect(kotlinBridge.exportAllSessions).toHaveBeenCalledTimes(1);
  });

  it('resuming a delegated session stashes its delegation metadata before handoff', () => {
    const delegated: DelegationMetadata = { delegatorRepo: 'team/repo' } as DelegationMetadata;
    act(() => useChatStore.getState().setHistoryItems([item('a', 'Alpha', { delegated })]));
    render(<HistoryView />);
    activateCard('Alpha');
    fireEvent.click(screen.getByRole('button', { name: /resume/i }));

    expect(useChatStore.getState().activeSessionDelegated).toEqual(delegated);
    expect(kotlinBridge.showSession).toHaveBeenCalledWith('a');
  });
});
