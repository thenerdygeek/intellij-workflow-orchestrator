/**
 * Scenario tests for the manual context-compaction lifecycle in the chat store.
 *
 * Contracts pinned here:
 * - `compactionState` is a discrete slice, separate from `busy` (so working-
 *   indicator phrases don't fire during compaction).
 * - `setCompactionState(active, phase)` round-trips through the store.
 * - `insertCompactionMarker(...)` appends a `say='COMPACTION_MARKER'` UiMessage
 *   with the full payload — markers persist into `messages[]` so they survive
 *   session reload.
 * - The marker payload preserves both heuristic and LLM-summary cases via
 *   `ranLlmSummary: boolean`.
 * - Multiple compactions accumulate multiple markers (one per click).
 */
import { describe, it, expect, beforeEach } from 'vitest';
import { chatState, resetChatStore } from './chat-store-test-utils';

describe('chatStore — manual compaction lifecycle', () => {
  beforeEach(resetChatStore);

  it('initializes compactionState to inactive with empty phase', () => {
    expect(chatState().compactionState).toEqual({ active: false, phase: '' });
  });

  it('setCompactionState(true, phase) flips active and stores phase string', () => {
    const { setCompactionState } = chatState();
    setCompactionState(true, 'Compacting — running full pipeline...');

    expect(chatState().compactionState.active).toBe(true);
    expect(chatState().compactionState.phase).toBe('Compacting — running full pipeline...');
  });

  it('setCompactionState(false, "") clears active flag', () => {
    const { setCompactionState } = chatState();
    setCompactionState(true, 'in progress');
    setCompactionState(false, '');

    expect(chatState().compactionState).toEqual({ active: false, phase: '' });
  });

  it('compactionState is independent of busy (no cross-pollination)', () => {
    const { setCompactionState, setBusy } = chatState();
    setBusy(true);
    setCompactionState(true, 'compacting');

    expect(chatState().busy).toBe(true);
    expect(chatState().compactionState.active).toBe(true);

    setCompactionState(false, '');

    // setting compactionState false must NOT clear busy
    expect(chatState().busy).toBe(true);
    expect(chatState().compactionState.active).toBe(false);
  });

  it('setBusy(false) does not clear compactionState', () => {
    const { setBusy, setCompactionState } = chatState();
    setCompactionState(true, 'compacting');
    setBusy(false);

    expect(chatState().compactionState.active).toBe(true);
  });

  it('insertCompactionMarker appends a SAY message with the full payload', () => {
    const { insertCompactionMarker } = chatState();
    const before = chatState().messages.length;

    insertCompactionMarker({
      tokensBefore: 165432,
      tokensAfter: 51201,
      messagesBefore: 87,
      messagesAfter: 32,
      ranLlmSummary: true,
      ts: 1746115200000,
    });

    const messages = chatState().messages;
    expect(messages.length).toBe(before + 1);
    const marker = messages[messages.length - 1];
    expect(marker.type).toBe('SAY');
    expect(marker.say).toBe('COMPACTION_MARKER');
    expect(marker.compactionMarker).toEqual({
      tokensBefore: 165432,
      tokensAfter: 51201,
      messagesBefore: 87,
      messagesAfter: 32,
      ranLlmSummary: true,
      ts: 1746115200000,
    });
  });

  it('preserves ranLlmSummary=false (heuristic-only path)', () => {
    const { insertCompactionMarker } = chatState();
    insertCompactionMarker({
      tokensBefore: 50000,
      tokensAfter: 30000,
      messagesBefore: 40,
      messagesAfter: 20,
      ranLlmSummary: false,
      ts: 1746115300000,
    });

    const last = chatState().messages.slice(-1)[0];
    expect(last.compactionMarker?.ranLlmSummary).toBe(false);
  });

  it('multiple compactions accumulate multiple markers in order', () => {
    const { insertCompactionMarker } = chatState();
    insertCompactionMarker({
      tokensBefore: 100000,
      tokensAfter: 60000,
      messagesBefore: 80,
      messagesAfter: 40,
      ranLlmSummary: true,
      ts: 1746115200000,
    });
    insertCompactionMarker({
      tokensBefore: 80000,
      tokensAfter: 30000,
      messagesBefore: 60,
      messagesAfter: 20,
      ranLlmSummary: true,
      ts: 1746115400000,
    });

    const markers = chatState().messages.filter((m) => m.say === 'COMPACTION_MARKER');
    expect(markers).toHaveLength(2);
    expect(markers[0].compactionMarker?.ts).toBe(1746115200000);
    expect(markers[1].compactionMarker?.ts).toBe(1746115400000);
  });

  it('marker has a unique ts (not the payload ts) so React keys stay stable', () => {
    const { insertCompactionMarker } = chatState();
    insertCompactionMarker({
      tokensBefore: 100,
      tokensAfter: 50,
      messagesBefore: 10,
      messagesAfter: 5,
      ranLlmSummary: false,
      ts: 1746115200000,
    });
    insertCompactionMarker({
      tokensBefore: 100,
      tokensAfter: 50,
      messagesBefore: 10,
      messagesAfter: 5,
      ranLlmSummary: false,
      ts: 1746115200000, // same payload ts
    });

    const markers = chatState().messages.filter((m) => m.say === 'COMPACTION_MARKER');
    expect(markers).toHaveLength(2);
    // The outer message ts (the React key) must differ even when payload.ts collides
    expect(markers[0].ts).not.toBe(markers[1].ts);
  });

  it('marker survives clearChat (sanity: clearChat is the explicit reset)', () => {
    const { insertCompactionMarker, clearChat } = chatState();
    insertCompactionMarker({
      tokensBefore: 100,
      tokensAfter: 50,
      messagesBefore: 10,
      messagesAfter: 5,
      ranLlmSummary: true,
      ts: 1746115200000,
    });
    expect(chatState().messages.filter((m) => m.say === 'COMPACTION_MARKER')).toHaveLength(1);

    clearChat();

    // After clearChat, all messages including markers are gone — this is the
    // expected behavior for "new chat" / explicit reset, not for compaction.
    expect(chatState().messages.filter((m) => m.say === 'COMPACTION_MARKER')).toHaveLength(0);
  });
});
