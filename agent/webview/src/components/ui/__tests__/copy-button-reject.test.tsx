/**
 * #16: a rejected clipboard write (lost focus / denied permission) must be
 * handled, not leak an unhandled promise rejection, and must not flip the
 * button into the "Copied" state.
 */
import { describe, it, expect, vi, afterEach, beforeEach } from 'vitest';
import { render, fireEvent, screen } from '@testing-library/react';
import { CopyButton } from '../copy-button';

const originalClipboard = navigator.clipboard;

afterEach(() => {
  vi.restoreAllMocks();
  Object.defineProperty(navigator, 'clipboard', { value: originalClipboard, configurable: true });
  document.body.innerHTML = '';
});

beforeEach(() => {
  vi.spyOn(console, 'warn').mockImplementation(() => {});
});

describe('CopyButton clipboard failure', () => {
  it('handles a rejected writeText without throwing and stays in idle state', async () => {
    const writeText = vi.fn().mockRejectedValue(new Error('NotAllowedError'));
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true });

    render(<CopyButton text="payload" />);
    fireEvent.click(screen.getByRole('button', { name: /copy/i }));

    // let the rejected promise + .catch settle
    await Promise.resolve();
    await Promise.resolve();

    expect(writeText).toHaveBeenCalledWith('payload');
    expect(console.warn).toHaveBeenCalled();
    // Button must NOT show the success ("Copied") state after a failed copy.
    expect(screen.queryByRole('button', { name: /copied/i })).toBeNull();
    expect(screen.getByRole('button', { name: /^copy$/i })).toBeInTheDocument();
  });

  it('shows the Copied state on a successful writeText', async () => {
    const writeText = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, 'clipboard', { value: { writeText }, configurable: true });

    render(<CopyButton text="payload" />);
    fireEvent.click(screen.getByRole('button', { name: /copy/i }));
    await Promise.resolve();
    await Promise.resolve();

    expect(screen.getByRole('button', { name: /copied/i })).toBeInTheDocument();
  });
});
