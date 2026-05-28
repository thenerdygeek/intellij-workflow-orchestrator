// Copyright 2025-2026 Workflow Orchestrator Contributors
// SPDX-License-Identifier: Apache-2.0
import { render, screen, fireEvent, cleanup, act } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { IncomingDelegationBarPure, type IncomingDelegationEntry } from '../IncomingDelegationBar';
import { useChatStore } from '@/stores/chatStore';

// ---------------------------------------------------------------------------
// Helpers
// ---------------------------------------------------------------------------

function makeEntry(overrides?: Partial<IncomingDelegationEntry>): IncomingDelegationEntry {
  return {
    key: 'k1',
    delegatorRepo: 'acme',
    deadlineEpochMs: Date.now() + 60_000,
    ...overrides,
  };
}

// ---------------------------------------------------------------------------
// Tests
// ---------------------------------------------------------------------------

describe('IncomingDelegationBarPure', () => {
  beforeEach(() => {
    cleanup();
    vi.useFakeTimers();
  });

  afterEach(() => {
    cleanup();
    vi.useRealTimers();
  });

  it('renders nothing when delegations list is empty', () => {
    render(
      <IncomingDelegationBarPure delegations={[]} onStart={vi.fn()} />,
    );
    expect(screen.queryByTestId('incoming-delegation-bar')).not.toBeInTheDocument();
  });

  it('renders repo name and a countdown, Start fires onStart with the key', () => {
    const onStart = vi.fn();
    const entry = makeEntry({ key: 'k1', delegatorRepo: 'acme', deadlineEpochMs: Date.now() + 60_000 });

    render(<IncomingDelegationBarPure delegations={[entry]} onStart={onStart} />);

    // Bar present
    expect(screen.getByTestId('incoming-delegation-bar')).toBeInTheDocument();

    // Repo name shown
    expect(screen.getByText('acme')).toBeInTheDocument();

    // Countdown is visible (matches "Ns" where N >= 0)
    const countdown = screen.getByTestId('incoming-delegation-countdown');
    expect(countdown.textContent).toMatch(/^\d+s$/);

    // Click Start
    fireEvent.click(screen.getByTestId('incoming-delegation-start'));
    expect(onStart).toHaveBeenCalledOnce();
    expect(onStart).toHaveBeenCalledWith('k1');
  });

  it('countdown decrements every second', () => {
    const entry = makeEntry({ deadlineEpochMs: Date.now() + 5_000 });
    render(<IncomingDelegationBarPure delegations={[entry]} onStart={vi.fn()} />);

    const countdown = screen.getByTestId('incoming-delegation-countdown');
    // Should be 5 or close (rounding from ceiling)
    const initial = parseInt(countdown.textContent ?? '0', 10);
    expect(initial).toBeGreaterThanOrEqual(4);
    expect(initial).toBeLessThanOrEqual(5);

    act(() => { vi.advanceTimersByTime(1000); });
    const after1 = parseInt(countdown.textContent ?? '0', 10);
    expect(after1).toBeLessThan(initial);
  });

  it('calls onExpire when countdown reaches 0', () => {
    const onExpire = vi.fn();
    const entry = makeEntry({ deadlineEpochMs: Date.now() + 2_000 });

    render(<IncomingDelegationBarPure delegations={[entry]} onStart={vi.fn()} onExpire={onExpire} />);

    // Not yet expired
    expect(onExpire).not.toHaveBeenCalled();

    // Advance past deadline
    act(() => { vi.advanceTimersByTime(3_000); });

    expect(onExpire).toHaveBeenCalledOnce();
    expect(onExpire).toHaveBeenCalledWith('k1');
  });

  it('Start button is disabled when secondsLeft is 0', () => {
    const entry = makeEntry({ deadlineEpochMs: Date.now() - 1 }); // already expired
    render(<IncomingDelegationBarPure delegations={[entry]} onStart={vi.fn()} />);

    const btn = screen.getByTestId('incoming-delegation-start');
    expect(btn).toBeDisabled();
  });

  it('renders multiple delegations as separate pills', () => {
    const delegations = [
      makeEntry({ key: 'k1', delegatorRepo: 'acme' }),
      makeEntry({ key: 'k2', delegatorRepo: 'beta-corp', deadlineEpochMs: Date.now() + 30_000 }),
    ];
    render(<IncomingDelegationBarPure delegations={delegations} onStart={vi.fn()} />);

    expect(screen.getByText('acme')).toBeInTheDocument();
    expect(screen.getByText('beta-corp')).toBeInTheDocument();
    expect(screen.getAllByTestId('incoming-delegation-start')).toHaveLength(2);
  });
});

// ---------------------------------------------------------------------------
// Store slice tests
// ---------------------------------------------------------------------------

describe('chatStore.incomingDelegations slice', () => {
  beforeEach(() => {
    // Reset the store to clean state
    useChatStore.getState().clearIncomingDelegation('k1');
    useChatStore.getState().clearIncomingDelegation('k2');
  });

  it('starts empty', () => {
    // After clearing both test keys the map should not contain them
    expect(useChatStore.getState().incomingDelegations['k1']).toBeUndefined();
  });

  it('upsertIncomingDelegation adds an entry', () => {
    const deadline = Date.now() + 60_000;
    useChatStore.getState().upsertIncomingDelegation('k1', 'acme', deadline);

    const entry = useChatStore.getState().incomingDelegations['k1'];
    expect(entry).toBeDefined();
    expect(entry?.delegatorRepo).toBe('acme');
    expect(entry?.deadlineEpochMs).toBe(deadline);
  });

  it('clearIncomingDelegation removes the entry', () => {
    useChatStore.getState().upsertIncomingDelegation('k1', 'acme', Date.now() + 60_000);
    useChatStore.getState().clearIncomingDelegation('k1');
    expect(useChatStore.getState().incomingDelegations['k1']).toBeUndefined();
  });

  it('upsert updates an existing entry', () => {
    useChatStore.getState().upsertIncomingDelegation('k1', 'acme', 1000);
    useChatStore.getState().upsertIncomingDelegation('k1', 'acme-v2', 2000);

    const entry = useChatStore.getState().incomingDelegations['k1'];
    expect(entry?.delegatorRepo).toBe('acme-v2');
    expect(entry?.deadlineEpochMs).toBe(2000);
  });
});
