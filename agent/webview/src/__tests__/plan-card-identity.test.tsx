/**
 * Bug #11 — Plan card stuck "Approving…" on a same-title revision.
 *
 * The button-reset effect keyed on `${plan.title}:${plan.approved}`. A revised
 * plan with the SAME title that is still unapproved produced an identical key,
 * so the effect never re-fired and `pending` stayed 'approve' → button frozen.
 *
 * Root: a plan has no stable identity. `setPlan` now assigns a monotonic
 * `revision`, bumped whenever the plan content changes, preserved when an
 * identical plan is re-pushed. The card keys its reset effect on `plan.revision`.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent, act } from '@testing-library/react';
import { PlanSummaryCard } from '@/components/agent/PlanSummaryCard';
import { useChatStore } from '@/stores/chatStore';
import type { Plan } from '@/bridge/types';

vi.mock('@/bridge/jcef-bridge', () => ({
  openInEditorTab: vi.fn(),
  kotlinBridge: { dismissPlan: vi.fn() },
}));

beforeEach(() => {
  vi.clearAllMocks();
  (window as any)._approvePlan = vi.fn();
  // Reset plan state between tests.
  act(() => useChatStore.getState().clearChat?.());
});

describe('Bug #11 (component) — Approve resets on a same-title revision', () => {
  it('clears the "Approving…" pending state when a revised plan arrives', () => {
    const v1: Plan = { title: 'Refactor auth', approved: false, summary: 'v1', revision: 1 };
    const { rerender } = render(<PlanSummaryCard plan={v1} />);

    fireEvent.click(screen.getByRole('button', { name: /approve/i }));
    expect(screen.getByText('Approving…')).toBeInTheDocument();

    // A revision: same title, still unapproved, new content → new identity.
    const v2: Plan = { title: 'Refactor auth', approved: false, summary: 'v2 revised', revision: 2 };
    rerender(<PlanSummaryCard plan={v2} />);

    // Button must reset — not stay frozen on "Approving…".
    expect(screen.queryByText('Approving…')).toBeNull();
    expect(screen.getByRole('button', { name: /approve/i })).not.toBeDisabled();
  });
});

describe('Bug #11 (store) — setPlan assigns a stable monotonic revision', () => {
  it('bumps revision when a same-title plan is revised (different content)', () => {
    const store = useChatStore.getState();
    act(() => store.setPlan({ title: 'X', approved: false, summary: 'a' }));
    const r1 = useChatStore.getState().plan!.revision;
    act(() => useChatStore.getState().setPlan({ title: 'X', approved: false, summary: 'b' }));
    const r2 = useChatStore.getState().plan!.revision;

    expect(r1).toBeTypeOf('number');
    expect(r2).toBeTypeOf('number');
    expect(r2).not.toBe(r1);
  });

  it('preserves revision when an identical plan is re-pushed', () => {
    const store = useChatStore.getState();
    act(() => store.setPlan({ title: 'X', approved: false, summary: 'a', markdown: 'm' }));
    const r1 = useChatStore.getState().plan!.revision;
    act(() => useChatStore.getState().setPlan({ title: 'X', approved: false, summary: 'a', markdown: 'm' }));
    const r2 = useChatStore.getState().plan!.revision;

    expect(r2).toBe(r1);
  });

  it('keeps revisions unique across clearPlan (no identity reuse within a session)', () => {
    act(() => useChatStore.getState().setPlan({ title: 'A', approved: false, summary: 'a' }));
    const r1 = useChatStore.getState().plan!.revision;
    act(() => useChatStore.getState().clearPlan());
    act(() => useChatStore.getState().setPlan({ title: 'B', approved: false, summary: 'b' }));
    const r2 = useChatStore.getState().plan!.revision;

    expect(r2).not.toBe(r1);
  });
});
