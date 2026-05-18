import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { UserMessageRevertButton } from '@/components/agent/UserMessageRevertButton';

describe('UserMessageRevertButton', () => {
  beforeEach(() => { (window as any)._revertToUserMessage = vi.fn(); });

  it('renders a button with the checkpoint label', () => {
    render(<UserMessageRevertButton ts={12345} />);
    expect(screen.getByRole('button', { name: /checkpoint to here/i })).toBeInTheDocument();
  });

  it('click invokes _revertToUserMessage with the ts when user confirms', () => {
    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(true);
    render(<UserMessageRevertButton ts={12345} />);
    fireEvent.click(screen.getByRole('button', { name: /checkpoint to here/i }));
    expect((window as any)._revertToUserMessage).toHaveBeenCalledWith(12345);
    confirmSpy.mockRestore();
  });

  it('click does NOT invoke _revertToUserMessage when user cancels', () => {
    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(false);
    render(<UserMessageRevertButton ts={12345} />);
    fireEvent.click(screen.getByRole('button', { name: /checkpoint to here/i }));
    expect((window as any)._revertToUserMessage).not.toHaveBeenCalled();
    confirmSpy.mockRestore();
  });
});
