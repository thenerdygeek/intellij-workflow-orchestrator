/**
 * Regression tests for I-1 (integration review): _appendSubAgentThinking and
 * _endSubAgentThinking received their payload as a JSON STRING from Kotlin
 * (AgentCefPanel via JsEscape.toJsString) but accessed payload.agentId /
 * payload.delta WITHOUT JSON.parse. That caused agentId to be `undefined`, so
 * thinking deltas accumulated under store key "undefined" and
 * endSubAgentThinking("undefined") matched no card — thinking never rendered.
 *
 * Fix: defensive parse identical to sibling handlers (_receiveSessionStats, etc.):
 *   const p = typeof payload === 'string' ? JSON.parse(payload) : payload;
 *
 * These tests MUST FAIL on the pre-fix code (where payload.agentId === undefined).
 */
import { describe, it, expect, beforeEach } from 'vitest';
import { initBridge } from '@/bridge/jcef-bridge';
import { useChatStore } from '@/stores/chatStore';

function chatState() {
  return useChatStore.getState();
}

beforeEach(() => {
  initBridge({
    getChatStore: () => useChatStore.getState(),
    getThemeStore: () => ({}),
    getSettingsStore: () => ({}),
  });
  useChatStore.getState().clearChat();
});

describe('_appendSubAgentThinking bridge — string payload (I-1 regression)', () => {
  it('routes the delta to the correct agentId slice when payload is a JSON string', () => {
    // Spawn a real sub-agent card so the store has the agentId registered.
    chatState().spawnSubAgent(JSON.stringify({ agentId: 'sa-think-1', label: 'Thinker' }));

    // Simulate Kotlin calling window._appendSubAgentThinking with a JSON string.
    const stringPayload = JSON.stringify({ agentId: 'sa-think-1', delta: 'reasoning step' });
    window._appendSubAgentThinking!(stringPayload);

    // The delta must accumulate under the correct agentId, NOT under "undefined".
    const slice = chatState().subAgentStreams['sa-think-1'];
    expect(slice).toBeDefined();
    expect(slice!.thinking).toBe('reasoning step');

    // Crucially: no stale entry under "undefined" (the pre-fix bug).
    expect(chatState().subAgentStreams['undefined']).toBeUndefined();
  });

  it('accumulates multiple string-payload deltas under the correct agentId', () => {
    chatState().spawnSubAgent(JSON.stringify({ agentId: 'sa-think-2', label: 'Reasoner' }));

    window._appendSubAgentThinking!(JSON.stringify({ agentId: 'sa-think-2', delta: 'part A' }));
    window._appendSubAgentThinking!(JSON.stringify({ agentId: 'sa-think-2', delta: ' part B' }));

    expect(chatState().subAgentStreams['sa-think-2']?.thinking).toBe('part A part B');
    expect(chatState().subAgentStreams['undefined']).toBeUndefined();
  });
});

describe('_endSubAgentThinking bridge — string payload (I-1 regression)', () => {
  it('ends thinking for the correct agentId when payload is a JSON string', () => {
    chatState().spawnSubAgent(JSON.stringify({ agentId: 'sa-end-1', label: 'Thinker' }));
    // Accumulate thinking via the store directly (already covered by unit tests).
    chatState().appendSubAgentThinking('sa-end-1', 'final thought');

    // Simulate Kotlin calling window._endSubAgentThinking with a JSON string.
    const stringPayload = JSON.stringify({ agentId: 'sa-end-1' });
    window._endSubAgentThinking!(stringPayload);

    // endSubAgentThinking flushes the thinking into a REASONING child message.
    const msg = chatState().messages.find(m => m.subagentData?.agentId === 'sa-end-1');
    const reasoningMsgs = msg?.subagentData?.messages.filter(m => m.say === 'REASONING') ?? [];
    expect(reasoningMsgs).toHaveLength(1);
    expect(reasoningMsgs[0]!.text).toBe('final thought');

    // Side-channel thinking is cleared.
    expect(chatState().subAgentStreams['sa-end-1']?.thinking).toBeNull();
  });

  it('does not clear thinking for the wrong agent (pre-fix: "undefined" matched nothing)', () => {
    chatState().spawnSubAgent(JSON.stringify({ agentId: 'sa-end-2', label: 'Thinker' }));
    chatState().appendSubAgentThinking('sa-end-2', 'keep this');

    // Calling with the wrong agentId must not flush sa-end-2's thinking.
    window._endSubAgentThinking!(JSON.stringify({ agentId: 'sa-end-WRONG' }));

    // sa-end-2's side-channel thinking is untouched.
    expect(chatState().subAgentStreams['sa-end-2']?.thinking).toBe('keep this');
  });

  it('does not throw on malformed JSON payload', () => {
    expect(() => {
      window._endSubAgentThinking!('{not valid json');
    }).not.toThrow();
  });
});
