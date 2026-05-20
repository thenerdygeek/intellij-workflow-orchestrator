import { describe, it, expect, vi } from 'vitest';
import { render, fireEvent, cleanup } from '@testing-library/react';
import { CopyButton } from '../components/ui/copy-button';

// Mock the bridge so the synchronous JCEF path is taken (no navigator.clipboard Promise needed)
vi.mock('@/bridge/jcef-bridge', () => ({
  isJcefEnvironment: () => true,
  kotlinBridge: { copyToClipboard: vi.fn() },
}));

describe('CopyButton', () => {
  it('does not call setState after unmount', () => {
    vi.useFakeTimers();
    const warn = vi.spyOn(console, 'error').mockImplementation(() => {});
    const { getByRole, unmount } = render(<CopyButton text="hello" />);
    fireEvent.click(getByRole('button'));
    unmount();
    vi.advanceTimersByTime(3000);
    // React 18 doesn't warn on state-after-unmount, but if the timeout
    // fires after unmount, it should be a noop.
    expect(warn).not.toHaveBeenCalledWith(expect.stringMatching(/state on an unmounted/));
    vi.useRealTimers();
    cleanup();
  });

  it('clears any pending timeout when unmounted after copy', () => {
    vi.useFakeTimers();
    const clearSpy = vi.spyOn(window, 'clearTimeout');
    const { getByRole, unmount } = render(<CopyButton text="hello" />);
    fireEvent.click(getByRole('button'));
    // A timeout is now pending; unmount should clear it via the useEffect cleanup
    unmount();
    expect(clearSpy).toHaveBeenCalled();
    vi.useRealTimers();
    cleanup();
  });
});
