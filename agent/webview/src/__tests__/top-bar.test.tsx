/**
 * Interaction coverage for the chat TopBar: New chat / Compact are gated on busy
 * state, the action buttons route to their bridges, the debug toggle flips store
 * state, and the "Waiting for approval" affordance only appears with a pending
 * approval and dispatches the scroll-to-approval event.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, act } from '@testing-library/react';
import { TopBar } from '@/components/chat/TopBar';
import { useChatStore } from '@/stores/chatStore';

vi.mock('@/bridge/jcef-bridge', () => ({
  kotlinBridge: {
    newChat: vi.fn(),
    compactContext: vi.fn(),
    viewInEditor: vi.fn(),
    requestHistory: vi.fn(),
  },
}));
import { kotlinBridge } from '@/bridge/jcef-bridge';

beforeEach(() => {
  vi.clearAllMocks();
  act(() => {
    useChatStore.getState().clearChat?.();
    useChatStore.getState().setBusy(false);
    useChatStore.getState().updateTokenBudget(20_000, 100_000); // make the budget UI (Compact btn) render
  });
});

describe('TopBar', () => {
  it('New chat calls the bridge and is disabled while busy', () => {
    render(<TopBar />);
    fireEvent.click(screen.getByRole('button', { name: /new conversation/i }));
    expect(kotlinBridge.newChat).toHaveBeenCalledTimes(1);

    act(() => useChatStore.getState().setBusy(true));
    expect(screen.getByRole('button', { name: /new conversation/i })).toBeDisabled();
  });

  it('Compact context calls the bridge and is disabled while busy', () => {
    render(<TopBar />);
    fireEvent.click(screen.getByRole('button', { name: /compact context/i }));
    expect(kotlinBridge.compactContext).toHaveBeenCalledWith(true);

    act(() => useChatStore.getState().setBusy(true));
    expect(screen.getByRole('button', { name: /compact context/i })).toBeDisabled();
  });

  it('the debug toggle flips debugLogVisible', () => {
    render(<TopBar />);
    expect(useChatStore.getState().debugLogVisible).toBe(false);
    fireEvent.click(screen.getByRole('button', { name: /toggle debug log/i }));
    expect(useChatStore.getState().debugLogVisible).toBe(true);
  });

  it('View in editor and history buttons route to their bridges', () => {
    render(<TopBar />);
    fireEvent.click(screen.getByRole('button', { name: /view in editor/i }));
    fireEvent.click(screen.getByRole('button', { name: /session history/i }));
    expect(kotlinBridge.viewInEditor).toHaveBeenCalledTimes(1);
    expect(kotlinBridge.requestHistory).toHaveBeenCalledTimes(1);
  });

  it('shows "Waiting for approval" only with a pending approval and dispatches the scroll event', () => {
    render(<TopBar />);
    expect(screen.queryByText(/waiting for approval/i)).toBeNull();

    act(() => useChatStore.getState().showApproval('edit_file', 'LOW', 'Edit a file'));

    const handler = vi.fn();
    document.addEventListener('scroll-to-approval', handler);
    fireEvent.click(screen.getByRole('button', { name: /waiting for approval/i }));
    expect(handler).toHaveBeenCalledTimes(1);
    document.removeEventListener('scroll-to-approval', handler);
  });
});
