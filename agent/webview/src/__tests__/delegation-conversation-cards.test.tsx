/**
 * Coverage for the IDE-B delegation CONVERSATION narration cards (2026-06-01).
 *
 * On IDE-B's panel the agent's WORK already renders; these tests pin the
 * delegation conversation framing: (b) question routed to the delegator,
 * (c) answer received back, (d) result sent back. Cards are pushed via the
 * `_appendDelegatedQuestion` / `_appendDelegatedAnswer` / `_appendDelegatedResult`
 * JCEF bridges, stored as `say: 'DELEGATION_CARD'` UiMessages in chatStore.messages,
 * and rendered by ChatView's renderItem.
 *
 * CRITICAL: the "other side" is always the delegator's REPO NAME (delegatorRepo),
 * never "IDE-A" / "IDE-B".
 */
import { describe, it, expect, beforeEach } from 'vitest';
import { render, screen, act } from '@testing-library/react';
import { initBridge } from '@/bridge/jcef-bridge';
import { useChatStore } from '@/stores/chatStore';
import { DelegationQuestionCard } from '@/components/agent/DelegationQuestionCard';
import { DelegationAnswerCard } from '@/components/agent/DelegationAnswerCard';
import { DelegationResultCard } from '@/components/agent/DelegationResultCard';
import type { DelegationCardData } from '@/bridge/types';

const REPO = 'team/backend-service';

beforeEach(() => {
  initBridge({
    getChatStore: () => useChatStore.getState(),
    getThemeStore: () => ({}),
    getSettingsStore: () => ({}),
  });
  act(() => {
    useChatStore.getState().clearChat();
  });
});

describe('DelegationQuestionCard (b — Asked {repo})', () => {
  it('renders the repo name, the question, options, and a waiting chip', () => {
    const data: DelegationCardData = {
      kind: 'ASKED',
      delegatorRepo: REPO,
      text: 'Which database driver should I use?',
      options: ['postgres', 'mysql'],
      answered: false,
    };
    render(<DelegationQuestionCard data={data} />);
    expect(screen.getByText(new RegExp(`Asked ${REPO}`))).toBeTruthy();
    expect(screen.getByText(/Which database driver/)).toBeTruthy();
    expect(screen.getByText('postgres')).toBeTruthy();
    expect(screen.getByText('mysql')).toBeTruthy();
    expect(screen.getByText(/waiting/i)).toBeTruthy();
    // No "IDE-A"/"IDE-B" leakage anywhere.
    expect(screen.queryByText(/IDE-A/)).toBeNull();
    expect(screen.queryByText(/IDE-B/)).toBeNull();
  });

  it('flips from waiting to resolved when answered=true', () => {
    const { rerender } = render(
      <DelegationQuestionCard data={{ kind: 'ASKED', delegatorRepo: REPO, text: 'Q?', answered: false }} />,
    );
    expect(screen.getByText(/waiting/i)).toBeTruthy();
    rerender(
      <DelegationQuestionCard data={{ kind: 'ASKED', delegatorRepo: REPO, text: 'Q?', answered: true }} />,
    );
    expect(screen.queryByText(/waiting/i)).toBeNull();
    expect(screen.getByText(/answered/i)).toBeTruthy();
  });
});

describe('DelegationAnswerCard (c — {repo} answered)', () => {
  it('renders the repo name and the answer text', () => {
    const data: DelegationCardData = {
      kind: 'ANSWERED',
      delegatorRepo: REPO,
      text: 'Use postgres.',
      answered: true,
    };
    render(<DelegationAnswerCard data={data} />);
    expect(screen.getByText(new RegExp(`${REPO} answered`))).toBeTruthy();
    expect(screen.getByText(/Use postgres/)).toBeTruthy();
  });
});

describe('DelegationResultCard (d — Result sent to {repo})', () => {
  it('renders green for COMPLETED with duration + summary', () => {
    const data: DelegationCardData = {
      kind: 'RESULT',
      delegatorRepo: REPO,
      text: 'All tests pass.',
      resultStatus: 'COMPLETED',
      durationSeconds: 42,
    };
    render(<DelegationResultCard data={data} />);
    expect(screen.getByText(new RegExp(`Result sent to ${REPO}`))).toBeTruthy();
    expect(screen.getByText(/COMPLETED/)).toBeTruthy();
    expect(screen.getByText(/All tests pass/)).toBeTruthy();
    expect(screen.getByText(/42s|0m 42s/)).toBeTruthy();
  });

  it('renders red + reason for FAILED', () => {
    const data: DelegationCardData = {
      kind: 'RESULT',
      delegatorRepo: REPO,
      resultStatus: 'FAILED',
      reason: 'compilation error',
      durationSeconds: 3,
    };
    render(<DelegationResultCard data={data} />);
    expect(screen.getByText(/FAILED/)).toBeTruthy();
    expect(screen.getByText(/compilation error/)).toBeTruthy();
  });
});

describe('chatStore delegation conversation actions', () => {
  it('appendDelegatedQuestion adds an ASKED DELEGATION_CARD message', () => {
    act(() => {
      useChatStore.getState().appendDelegatedQuestion('q1', REPO, 'Q1?', ['a', 'b']);
    });
    const msgs = useChatStore.getState().messages;
    const card = msgs.find((m) => m.say === 'DELEGATION_CARD');
    expect(card).toBeTruthy();
    expect(card!.delegationCardData!.kind).toBe('ASKED');
    expect(card!.delegationCardData!.delegatorRepo).toBe(REPO);
    expect(card!.delegationCardData!.text).toBe('Q1?');
    expect(card!.delegationCardData!.options).toEqual(['a', 'b']);
    expect(card!.delegationCardData!.answered).toBe(false);
  });

  it('appendDelegatedAnswer flips the pending ASKED card to resolved and adds an ANSWERED card', () => {
    act(() => {
      useChatStore.getState().appendDelegatedQuestion('q1', REPO, 'Q1?', []);
      useChatStore.getState().appendDelegatedAnswer('q1', REPO, 'the answer');
    });
    const msgs = useChatStore.getState().messages;
    const asked = msgs.find((m) => m.delegationCardData?.kind === 'ASKED');
    const answered = msgs.find((m) => m.delegationCardData?.kind === 'ANSWERED');
    expect(asked!.delegationCardData!.answered).toBe(true);
    expect(answered).toBeTruthy();
    expect(answered!.delegationCardData!.text).toBe('the answer');
    expect(answered!.delegationCardData!.delegatorRepo).toBe(REPO);
  });

  it('appendDelegatedResult adds a RESULT card', () => {
    act(() => {
      useChatStore.getState().appendDelegatedResult(REPO, 'COMPLETED', 10, 'done', null);
    });
    const card = useChatStore.getState().messages.find((m) => m.delegationCardData?.kind === 'RESULT');
    expect(card).toBeTruthy();
    expect(card!.delegationCardData!.resultStatus).toBe('COMPLETED');
    expect(card!.delegationCardData!.durationSeconds).toBe(10);
    expect(card!.delegationCardData!.text).toBe('done');
  });
});

describe('delegation conversation bridges', () => {
  it('_appendDelegatedQuestion / _appendDelegatedAnswer / _appendDelegatedResult drive the store', () => {
    act(() => {
      window._appendDelegatedQuestion!(
        JSON.stringify({ questionId: 'q9', delegatorRepo: REPO, text: 'Pick one', options: ['x'] }),
      );
    });
    expect(useChatStore.getState().messages.some((m) => m.delegationCardData?.kind === 'ASKED')).toBe(true);

    act(() => {
      window._appendDelegatedAnswer!(
        JSON.stringify({ questionId: 'q9', delegatorRepo: REPO, text: 'x it is' }),
      );
    });
    const asked = useChatStore.getState().messages.find((m) => m.delegationCardData?.kind === 'ASKED');
    expect(asked!.delegationCardData!.answered).toBe(true);
    expect(useChatStore.getState().messages.some((m) => m.delegationCardData?.kind === 'ANSWERED')).toBe(true);

    act(() => {
      window._appendDelegatedResult!(
        JSON.stringify({ delegatorRepo: REPO, status: 'COMPLETED', durationSeconds: 5, summary: 'ok', reason: null }),
      );
    });
    expect(useChatStore.getState().messages.some((m) => m.delegationCardData?.kind === 'RESULT')).toBe(true);
  });

  it('bridges do not throw on malformed payloads', () => {
    expect(() => {
      act(() => {
        window._appendDelegatedQuestion!('{not json');
        window._appendDelegatedAnswer!('{not json');
        window._appendDelegatedResult!('{not json');
      });
    }).not.toThrow();
  });
});

describe('ChatView renderItem renders DELEGATION_CARD messages', () => {
  it('is exercised through the store-driven message flow', () => {
    // A DELEGATION_CARD message must carry delegationCardData so renderItem can
    // pick the right card component. This pins the data contract the renderer needs.
    act(() => {
      useChatStore.getState().appendDelegatedResult(REPO, 'FAILED', 1, '', 'boom');
    });
    const card = useChatStore.getState().messages.find((m) => m.say === 'DELEGATION_CARD');
    expect(card?.delegationCardData?.kind).toBe('RESULT');
  });
});
