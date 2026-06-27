/**
 * BUG-2 (footer scrollIntoView fighting followOutput → mid-stream JUMP).
 *
 * When an approval card / question appears, ChatFooter used to ALWAYS
 * `scrollIntoView`, even while Virtuoso's `followOutput` was already keeping the
 * (near-bottom) user pinned. The two scrolls fought and the view jumped. The fix
 * guards the footer's force-scroll behind a distance-from-bottom threshold: only
 * force-scroll when the user is genuinely far from the bottom; otherwise let
 * `followOutput` manage it.
 *
 * The "near bottom → does NOT scroll" assertion FAILS against the pre-fix
 * unconditional `scrollIntoView`.
 */
import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { render, act, cleanup } from '@testing-library/react';
import { useChatStore } from '@/stores/chatStore';
import { footerShouldForceScroll } from '@/components/chat/ChatFooter';

// ApprovalView pulls in Shiki/DiffHtml (flaky in jsdom) and is irrelevant to the
// scroll guard — stub it so only the footer's effect wiring is exercised.
vi.mock('@/components/agent/ApprovalView', () => ({
  ApprovalView: () => <div data-testid="approval-stub" />,
}));

import { ChatFooter } from '@/components/chat/ChatFooter';

// A fresh object each call: pendingApproval is keyed by reference, so reusing a
// shared constant across tests would make the second `setState` a no-op (the
// effect only re-runs when the reference changes), masking the guard behavior.
const makeApproval = () => ({
  toolName: 'edit_file',
  riskLevel: 'medium',
  title: 'Edit file',
  allowSessionApproval: true,
}) as any;

/**
 * Install a fake Virtuoso scroller whose distance-from-bottom
 * (`scrollHeight - scrollTop - clientHeight`) equals `distancePx`.
 */
function installScroller(distancePx: number) {
  const el = document.createElement('div');
  el.setAttribute('role', 'log');
  Object.defineProperty(el, 'clientHeight', { value: 400, configurable: true });
  Object.defineProperty(el, 'scrollHeight', { value: 400 + distancePx, configurable: true });
  Object.defineProperty(el, 'scrollTop', { value: 0, writable: true, configurable: true });
  document.body.appendChild(el);
  return el;
}

beforeEach(() => {
  cleanup();
  act(() => useChatStore.getState().clearChat?.());
  // clearChat() does NOT reset pendingApproval, so null it explicitly to keep
  // tests isolated (a leaked approval would fire the effect on the next mount).
  act(() => useChatStore.setState({ pendingApproval: null }));
  // jsdom has no layout/scrollIntoView — install a spy on the prototype.
  (Element.prototype as any).scrollIntoView = vi.fn();
});

afterEach(() => {
  document.querySelectorAll('[role="log"]').forEach((e) => e.remove());
});

describe('footerShouldForceScroll', () => {
  it('returns false near the bottom and true far from the bottom (200px threshold)', () => {
    installScroller(50);
    expect(footerShouldForceScroll()).toBe(false);
    document.querySelectorAll('[role="log"]').forEach((e) => e.remove());

    installScroller(600);
    expect(footerShouldForceScroll()).toBe(true);
  });

  it('fails open (true) when no scroller is present', () => {
    expect(footerShouldForceScroll()).toBe(true);
  });
});

describe('ChatFooter approval scrollIntoView is distance-guarded', () => {
  it('does NOT force-scroll when the user is near the bottom', () => {
    installScroller(50); // < 200px → near bottom → followOutput owns it
    const spy = Element.prototype.scrollIntoView as ReturnType<typeof vi.fn>;
    render(<ChatFooter />);
    spy.mockClear();

    act(() => useChatStore.setState({ pendingApproval: makeApproval() }));

    expect(spy).not.toHaveBeenCalled();
  });

  it('DOES force-scroll when the user is far from the bottom', () => {
    installScroller(600); // > 200px → far → surface the approval card
    const spy = Element.prototype.scrollIntoView as ReturnType<typeof vi.fn>;
    render(<ChatFooter />);
    spy.mockClear();

    act(() => useChatStore.setState({ pendingApproval: makeApproval() }));

    expect(spy).toHaveBeenCalled();
  });
});
