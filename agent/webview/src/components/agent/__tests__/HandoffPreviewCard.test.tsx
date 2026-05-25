import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { HandoffPreviewCard } from '../HandoffPreviewCard';

describe('HandoffPreviewCard', () => {
  beforeEach(() => {
    (window as any)._handoffFork = vi.fn();
    (window as any)._handoffKeep = vi.fn();
  });

  it('renders the summary and two buttons', () => {
    render(<HandoffPreviewCard handoff={{ summary: '## Current Work\nRefactor auth' }} />);
    expect(screen.getByText(/Continue in a fresh session/i)).toBeTruthy();
    expect(screen.getByRole('button', { name: /Start fresh session/i })).toBeTruthy();
    expect(screen.getByRole('button', { name: /Keep chatting/i })).toBeTruthy();
  });

  it('Start fresh calls the bridge exactly once', () => {
    render(<HandoffPreviewCard handoff={{ summary: 'X' }} />);
    const btn = screen.getByRole('button', { name: /Start fresh session/i });
    fireEvent.click(btn);
    fireEvent.click(btn); // second click must be ignored (exactly-once guard)
    expect((window as any)._handoffFork).toHaveBeenCalledTimes(1);
  });

  it('Keep chatting calls the bridge exactly once', () => {
    render(<HandoffPreviewCard handoff={{ summary: 'X' }} />);
    const btn = screen.getByRole('button', { name: /Keep chatting/i });
    fireEvent.click(btn);
    fireEvent.click(btn);
    expect((window as any)._handoffKeep).toHaveBeenCalledTimes(1);
  });
});
