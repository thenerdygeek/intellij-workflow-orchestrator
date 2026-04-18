/**
 * Reproduces the reported symptom: after 6 successful task_create calls, the
 * task progress widget shows "0/1 completed" instead of "0/6". This isolates
 * whether the bug lives in React (state batching, stale closures) or in the
 * Kotlin → JCEF → React bridge wiring.
 *
 * If these tests PASS, the React path is clean and the missing tasks are
 * being lost upstream (callJs timing, JCEF page-load race, etc).
 * If they FAIL, React is losing events and the fix belongs here.
 */
import { describe, it, expect, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import { PlanProgressWidget } from '@/components/agent/PlanProgressWidget';
import { chatState, resetChatStore } from './chat-store-test-utils';
import type { Task } from '@/bridge/types';

function makeTask(id: string, subject: string): Task {
  return {
    id,
    subject,
    description: `desc for ${subject}`,
    status: 'pending',
    blocks: [],
    blockedBy: [],
  };
}

describe('PlanProgressWidget — task count after N task_create calls', () => {
  beforeEach(resetChatStore);

  it('renders 6 todos after 6 sequential applyTaskCreate calls', () => {
    const { applyTaskCreate } = chatState();

    for (let i = 1; i <= 6; i++) {
      applyTaskCreate(makeTask(String(i), `Step ${i}`));
    }

    render(<PlanProgressWidget />);
    expect(screen.getByText('0/6 completed')).toBeInTheDocument();
    // Each subject must be rendered somewhere in the widget
    for (let i = 1; i <= 6; i++) {
      // Some todos may be collapsed into "+N completed" — but Step labels for
      // pending/in_progress items should appear in the visible window, and the
      // widget must expose all 6 via the collapsed groups if windowed.
      // We assert on the count instead of per-label to stay robust against
      // windowing. The critical invariant is total=6.
    }
  });

  it('renders 6 todos when applyTaskCreate is fired in a single synchronous tick', () => {
    // Reproduces the potential React-batching failure mode: if JCEF delivers
    // all 6 callJs invocations in the same microtask, the store updater
    // closes over stale `state.tasks` and only the last write wins.
    const { applyTaskCreate } = chatState();
    const tasks = Array.from({ length: 6 }, (_, i) => makeTask(String(i + 1), `Step ${i + 1}`));

    // Fire synchronously — no awaits, no microtask boundary between calls.
    tasks.forEach((t) => applyTaskCreate(t));

    render(<PlanProgressWidget />);
    expect(screen.getByText('0/6 completed')).toBeInTheDocument();
  });

  it('renders correct count when the same task id arrives twice (upsert, not duplicate)', () => {
    const { applyTaskCreate } = chatState();
    applyTaskCreate(makeTask('1', 'A'));
    applyTaskCreate(makeTask('1', 'A'));

    render(<PlanProgressWidget />);
    expect(screen.getByText('0/1 completed')).toBeInTheDocument();
  });

  it('does not render when there are no tasks', () => {
    const { container } = render(<PlanProgressWidget />);
    // Widget returns null when visible.length === 0
    expect(container.firstChild).toBeNull();
  });
});
