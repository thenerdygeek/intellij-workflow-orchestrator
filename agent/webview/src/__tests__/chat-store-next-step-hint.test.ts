/**
 * `nextStepHint` lifecycle in chatStore:
 *   - addCompletionCard with a non-blank nextStep populates the hint.
 *   - addCompletionCard with null/blank nextStep yields a null hint.
 *   - addCompletionCard overwrites a prior hint with the latest completion's value.
 *   - addUserMessage clears the hint (the user moved past it).
 *   - clearNextStepHint clears it on demand (e.g., right-arrow accept handler).
 *   - clearChat resets it to null.
 */
import { describe, it, expect, beforeEach, vi, afterEach } from 'vitest';
import type { CompletionData } from '@/bridge/types';
import { chatState, resetChatStore } from './chat-store-test-utils';

describe('chatStore — nextStepHint lifecycle', () => {
  let fakeNow = 2000000;
  beforeEach(() => {
    vi.spyOn(Date, 'now').mockImplementation(() => ++fakeNow);
    resetChatStore();
  });
  afterEach(() => vi.restoreAllMocks());

  it('starts as null', () => {
    expect(chatState().nextStepHint).toBeNull();
  });

  it('addCompletionCard with nextStep populates the hint', () => {
    const data: CompletionData = { kind: 'done', result: 'Done', nextStep: 'run the tests' };
    chatState().addCompletionCard(data);
    expect(chatState().nextStepHint).toBe('run the tests');
  });

  it('addCompletionCard without nextStep yields null hint', () => {
    const data: CompletionData = { kind: 'done', result: 'Done' };
    chatState().addCompletionCard(data);
    expect(chatState().nextStepHint).toBeNull();
  });

  it('blank nextStep normalizes to null', () => {
    chatState().addCompletionCard({ kind: 'done', result: 'Done', nextStep: '   ' });
    expect(chatState().nextStepHint).toBeNull();
  });

  it('addCompletionCard overwrites a prior hint', () => {
    chatState().addCompletionCard({ kind: 'done', result: 'A', nextStep: 'first' });
    expect(chatState().nextStepHint).toBe('first');
    chatState().addCompletionCard({ kind: 'done', result: 'B', nextStep: 'second' });
    expect(chatState().nextStepHint).toBe('second');
  });

  it('addUserMessage clears the hint', () => {
    chatState().addCompletionCard({ kind: 'done', result: 'A', nextStep: 'commit this' });
    expect(chatState().nextStepHint).toBe('commit this');
    chatState().addUserMessage('thanks');
    expect(chatState().nextStepHint).toBeNull();
  });

  it('clearNextStepHint resets without affecting other state', () => {
    chatState().addCompletionCard({ kind: 'done', result: 'A', nextStep: 'commit this' });
    const messageCountBefore = chatState().messages.length;
    chatState().clearNextStepHint();
    expect(chatState().nextStepHint).toBeNull();
    expect(chatState().messages.length).toBe(messageCountBefore);
  });

  it('clearChat resets the hint', () => {
    chatState().addCompletionCard({ kind: 'done', result: 'A', nextStep: 'commit this' });
    chatState().clearChat();
    expect(chatState().nextStepHint).toBeNull();
  });
});
