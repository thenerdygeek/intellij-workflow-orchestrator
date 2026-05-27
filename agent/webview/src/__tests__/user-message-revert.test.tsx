import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, act } from '@testing-library/react';
import { UserMessageRevertButton } from '@/components/agent/UserMessageRevertButton';

// The button must NOT rely on native window.confirm — it does not work inside
// the JCEF (embedded Chromium) webview (no CefJSDialogHandler is registered, so
// confirm() returns false and the revert never fires). It uses an inline
// two-step confirm instead, mirroring SessionCard's delete affordance.
describe('UserMessageRevertButton', () => {
  beforeEach(() => { (window as any)._revertToUserMessage = vi.fn(); });

  it('renders the checkpoint label in its idle state', () => {
    render(<UserMessageRevertButton ts={12345} />);
    expect(screen.getByRole('button', { name: /checkpoint to here/i })).toBeInTheDocument();
  });

  it('first click does NOT revert immediately — it asks for confirmation inline', () => {
    render(<UserMessageRevertButton ts={12345} />);
    fireEvent.click(screen.getByRole('button', { name: /checkpoint to here/i }));
    expect((window as any)._revertToUserMessage).not.toHaveBeenCalled();
    expect(screen.getByRole('button', { name: /confirm checkpoint revert/i })).toBeInTheDocument();
    expect(screen.getByRole('button', { name: /cancel checkpoint revert/i })).toBeInTheDocument();
  });

  it('confirm invokes _revertToUserMessage with the ts (no window.confirm)', () => {
    const confirmSpy = vi.spyOn(window, 'confirm');
    render(<UserMessageRevertButton ts={12345} />);
    fireEvent.click(screen.getByRole('button', { name: /checkpoint to here/i }));
    fireEvent.click(screen.getByRole('button', { name: /confirm checkpoint revert/i }));
    expect((window as any)._revertToUserMessage).toHaveBeenCalledWith(12345);
    expect(confirmSpy).not.toHaveBeenCalled();
    confirmSpy.mockRestore();
  });

  it('cancel does NOT revert and returns to the idle label', () => {
    render(<UserMessageRevertButton ts={12345} />);
    fireEvent.click(screen.getByRole('button', { name: /checkpoint to here/i }));
    fireEvent.click(screen.getByRole('button', { name: /cancel checkpoint revert/i }));
    expect((window as any)._revertToUserMessage).not.toHaveBeenCalled();
    expect(screen.getByRole('button', { name: /checkpoint to here/i })).toBeInTheDocument();
  });

  it('auto-reverts to the idle label if confirmation is left unanswered', () => {
    vi.useFakeTimers();
    try {
      render(<UserMessageRevertButton ts={12345} />);
      fireEvent.click(screen.getByRole('button', { name: /checkpoint to here/i }));
      expect(screen.getByRole('button', { name: /confirm checkpoint revert/i })).toBeInTheDocument();
      act(() => { vi.advanceTimersByTime(4500); });
      expect(screen.getByRole('button', { name: /checkpoint to here/i })).toBeInTheDocument();
      expect((window as any)._revertToUserMessage).not.toHaveBeenCalled();
    } finally {
      vi.useRealTimers();
    }
  });
});
