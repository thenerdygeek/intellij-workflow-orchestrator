/**
 * Tests for the monitor indicator feature (Task 6G).
 *
 * Contracts:
 * 1. chatStore.setMonitorHandles updates monitorHandles state.
 * 2. MonitorIndicator renders nothing when monitorHandles is empty.
 * 3. MonitorIndicator renders a chip with count when handles are present.
 * 4. The chip shows a pulse dot when any monitor is RUNNING.
 */
import { describe, it, expect, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { chatState, resetChatStore } from './chat-store-test-utils';
import type { MonitorSnapshot } from '../bridge/globals';
import { MonitorIndicator } from '../components/chat/MonitorIndicator';

// ── Store slice tests ──

describe('chatStore — monitorHandles slice', () => {
  beforeEach(resetChatStore);

  it('initialises monitorHandles as an empty array', () => {
    expect(chatState().monitorHandles).toEqual([]);
  });

  it('setMonitorHandles replaces the slice with the given snapshot', () => {
    const snapshot: MonitorSnapshot[] = [
      { id: 'shell-abc123', label: 'gradle build', state: 'RUNNING' },
      { id: 'bamboo-def456', label: 'CI pipeline', state: 'EXITED' },
    ];
    chatState().setMonitorHandles(snapshot);
    expect(chatState().monitorHandles).toEqual(snapshot);
  });

  it('setMonitorHandles with empty array clears the slice', () => {
    chatState().setMonitorHandles([
      { id: 'shell-abc123', label: 'gradle build', state: 'RUNNING' },
    ]);
    chatState().setMonitorHandles([]);
    expect(chatState().monitorHandles).toEqual([]);
  });

  it('setMonitorHandles replaces the whole slice (not appends)', () => {
    chatState().setMonitorHandles([{ id: 'm1', label: 'first', state: 'RUNNING' }]);
    chatState().setMonitorHandles([{ id: 'm2', label: 'second', state: 'RUNNING' }]);
    const handles = chatState().monitorHandles;
    expect(handles).toHaveLength(1);
    expect(handles[0]!.id).toBe('m2');
  });
});

// ── Component render tests ──

describe('MonitorIndicator component', () => {
  beforeEach(resetChatStore);

  it('renders nothing when monitorHandles is empty', () => {
    const { container } = render(<MonitorIndicator />);
    expect(container.firstChild).toBeNull();
  });

  it('renders a chip with the count when handles are present', () => {
    chatState().setMonitorHandles([
      { id: 'shell-abc123', label: 'gradle build', state: 'RUNNING' },
      { id: 'bamboo-def456', label: 'CI pipeline', state: 'EXITED' },
    ]);
    render(<MonitorIndicator />);
    expect(screen.getByRole('button')).toBeTruthy();
    // Button text includes the count
    expect(screen.getByRole('button').textContent).toContain('2');
  });

  it('renders "1 monitor" (singular) for a single handle', () => {
    chatState().setMonitorHandles([
      { id: 'shell-xyz', label: 'watch test', state: 'RUNNING' },
    ]);
    render(<MonitorIndicator />);
    expect(screen.getByRole('button').textContent).toContain('1 monitor');
  });

  it('renders "2 monitors" (plural) for two handles', () => {
    chatState().setMonitorHandles([
      { id: 'm1', label: 'a', state: 'RUNNING' },
      { id: 'm2', label: 'b', state: 'EXITED' },
    ]);
    render(<MonitorIndicator />);
    expect(screen.getByRole('button').textContent).toContain('2 monitors');
  });
});
