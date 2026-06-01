/**
 * Coverage for the LIVE delegated-session banner wiring (2026-05-30): Kotlin's
 * AgentController.pushActiveSessionDelegated drives the new `_setActiveSessionDelegated`
 * bridge, which feeds chatStore.activeSessionDelegated, which the top-bar DelegationBanner
 * renders. Previously this only populated when reopening a session from history.
 */
import { describe, it, expect, beforeEach } from 'vitest';
import { render, screen, act } from '@testing-library/react';
import { initBridge } from '@/bridge/jcef-bridge';
import { useChatStore } from '@/stores/chatStore';
import { DelegationBanner } from '@/components/history/DelegationBanner';
import type { DelegationMetadata } from '@/bridge/types';

const META: DelegationMetadata = {
  delegatorIde: 'IntelliJ IDEA',
  delegatorRepo: 'team/backend-service',
  delegatorSessionId: 'sess-a-1',
  startedAt: Date.now(),
};

beforeEach(() => {
  initBridge({
    getChatStore: () => useChatStore.getState(),
    getThemeStore: () => ({}),
    getSettingsStore: () => ({}),
  });
  act(() => {
    useChatStore.getState().setActiveSessionDelegated(null);
  });
});

describe('_setActiveSessionDelegated bridge', () => {
  it('populates the store from a metadata JSON payload', () => {
    act(() => {
      window._setActiveSessionDelegated!(JSON.stringify(META));
    });
    expect(useChatStore.getState().activeSessionDelegated).toEqual(META);
  });

  it('clears the store when handed the literal null', () => {
    act(() => {
      useChatStore.getState().setActiveSessionDelegated(META);
      window._setActiveSessionDelegated!('null');
    });
    expect(useChatStore.getState().activeSessionDelegated).toBeNull();
  });

  it('does not throw on a malformed payload (defensive)', () => {
    act(() => {
      useChatStore.getState().setActiveSessionDelegated(META);
      expect(() => window._setActiveSessionDelegated!('{not json')).not.toThrow();
    });
    // Store is left untouched on a parse failure.
    expect(useChatStore.getState().activeSessionDelegated).toEqual(META);
  });

  it('renders the banner with the delegator REPO once the bridge fires (no IDE id)', () => {
    const { container } = render(<DelegationBanner />);
    expect(screen.queryByText(/Delegated from/)).toBeNull();

    act(() => {
      window._setActiveSessionDelegated!(JSON.stringify(META));
    });

    // Repo-named: "Delegated from {repo}" — never the IDE process id / name.
    expect(screen.getByText(/Delegated from/)).toBeTruthy();
    expect(screen.getByText('team/backend-service')).toBeTruthy();
    // The delegator IDE identifier is no longer rendered (#1 fix).
    expect(screen.queryByText('IntelliJ IDEA')).toBeNull();
    expect(container.textContent).not.toMatch(/Delegated by/);
    expect(container.textContent).not.toMatch(/ide-\d+/i);
  });
});
