import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { UserMessageRevertButton } from '@/components/agent/UserMessageRevertButton';

describe('UserMessageRevertButton', () => {
  beforeEach(() => { (window as any)._revertToUserMessage = vi.fn(); });

  it('renders a button with the time-travel label', () => {
    render(<UserMessageRevertButton ts={12345} />);
    expect(screen.getByRole('button', { name: /time-travel/i })).toBeInTheDocument();
  });

  it('click invokes _revertToUserMessage with the ts', () => {
    render(<UserMessageRevertButton ts={12345} />);
    fireEvent.click(screen.getByRole('button', { name: /time-travel/i }));
    expect((window as any)._revertToUserMessage).toHaveBeenCalledWith(12345);
  });
});
