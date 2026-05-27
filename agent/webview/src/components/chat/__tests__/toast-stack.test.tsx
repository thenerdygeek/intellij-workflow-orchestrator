/**
 * #1: toasts were written to the store but no component rendered them — every
 * showToast (attachment validation, vision-disabled, etc.) was silently
 * swallowed. ToastStack is the missing renderer.
 */
import { describe, it, expect, beforeEach } from 'vitest';
import { render, screen, fireEvent, act } from '@testing-library/react';
import { ToastStack } from '../ToastStack';
import { useChatStore } from '@/stores/chatStore';

beforeEach(() => {
  useChatStore.setState({ toasts: [] } as never);
});

describe('ToastStack', () => {
  it('renders a queued toast', () => {
    act(() => useChatStore.getState().showToast('Image too large (max 5 MB).', 'error', 0));
    render(<ToastStack />);
    expect(screen.getByRole('alert')).toHaveTextContent('Image too large (max 5 MB).');
  });

  it('renders nothing when there are no toasts', () => {
    const { container } = render(<ToastStack />);
    expect(container.firstChild).toBeNull();
  });

  it('dismiss button removes the toast', () => {
    act(() => useChatStore.getState().showToast('dismiss me', 'info', 0));
    render(<ToastStack />);
    expect(screen.getByText('dismiss me')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: /dismiss/i }));
    expect(screen.queryByText('dismiss me')).toBeNull();
  });
});
