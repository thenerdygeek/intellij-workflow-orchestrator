/**
 * TDD tests for P0-3 sub-agent stream side-channel.
 *
 * P0-3: appendSubAgentStreamDelta and appendSubAgentThinking previously did
 * state.messages.map(...) returning a NEW messages array per 16ms batch.
 * New array identity fired ChatView's s=>s.messages selector → renderItems
 * useMemo re-walked → every visible row re-rendered at up to 60fps.
 *
 * Fix: live-stream buffers move to subAgentStreams[agentId] (the side-channel).
 * - appendSubAgentStreamDelta: accumulates in side-channel.text
 * - appendSubAgentThinking:    accumulates in side-channel.thinking
 * - updateSubAgentIteration:   updates side-channel.iteration (if stream open)
 * - setSubAgentStatusNote:     updates side-channel.statusNote (if stream open)
 * - messages[] NEVER changes reference during streaming deltas.
 * - completeSubAgent:          commits accumulated text into messages[] and
 *                              clears the side-channel entry.
 * - killSubAgent:              clears the side-channel entry.
 */
import { describe, it, expect, beforeEach } from 'vitest';
import { chatState, resetChatStore } from './chat-store-test-utils';

describe('P0-3 — sub-agent stream side-channel', () => {
  beforeEach(resetChatStore);

  // ── Core invariant: messages[] stays stable during streaming deltas ──

  it('appendSubAgentStreamDelta does NOT change messages[] reference', () => {
    chatState().spawnSubAgent(JSON.stringify({ agentId: 'sa-1', label: 'Coder' }));
    const msgsBefore = chatState().messages;

    chatState().appendSubAgentStreamDelta(JSON.stringify({ agentId: 'sa-1', delta: 'Hello' }));
    chatState().appendSubAgentStreamDelta(JSON.stringify({ agentId: 'sa-1', delta: ', world' }));

    // Critical: same reference — ChatView's s=>s.messages selector must NOT fire.
    expect(chatState().messages).toBe(msgsBefore);
  });

  it('appendSubAgentThinking does NOT change messages[] reference', () => {
    chatState().spawnSubAgent(JSON.stringify({ agentId: 'sa-2', label: 'Analyzer' }));
    const msgsBefore = chatState().messages;

    chatState().appendSubAgentThinking('sa-2', 'reasoning about the code');
    chatState().appendSubAgentThinking('sa-2', ' more reasoning');

    expect(chatState().messages).toBe(msgsBefore);
  });

  it('updateSubAgentIteration with active stream does NOT change messages[] reference', () => {
    chatState().spawnSubAgent(JSON.stringify({ agentId: 'sa-3', label: 'Tooler' }));
    chatState().appendSubAgentStreamDelta(JSON.stringify({ agentId: 'sa-3', delta: 'token' }));
    const msgsBefore = chatState().messages;

    chatState().updateSubAgentIteration(JSON.stringify({ agentId: 'sa-3', iteration: 2 }));

    expect(chatState().messages).toBe(msgsBefore);
  });

  it('setSubAgentStatusNote with active stream does NOT change messages[] reference', () => {
    chatState().spawnSubAgent(JSON.stringify({ agentId: 'sa-4', label: 'Reviewer' }));
    chatState().appendSubAgentStreamDelta(JSON.stringify({ agentId: 'sa-4', delta: 'token' }));
    const msgsBefore = chatState().messages;

    chatState().setSubAgentStatusNote(JSON.stringify({ agentId: 'sa-4', note: 'Compacting…' }));

    expect(chatState().messages).toBe(msgsBefore);
  });

  // ── Side-channel content accumulates correctly ──

  it('appendSubAgentStreamDelta accumulates text in subAgentStreams[agentId].text', () => {
    chatState().spawnSubAgent(JSON.stringify({ agentId: 'sa-5', label: 'Writer' }));
    chatState().appendSubAgentStreamDelta(JSON.stringify({ agentId: 'sa-5', delta: 'foo' }));
    chatState().appendSubAgentStreamDelta(JSON.stringify({ agentId: 'sa-5', delta: 'bar' }));

    const slice = chatState().subAgentStreams['sa-5'];
    expect(slice?.text).toBe('foobar');
  });

  it('appendSubAgentThinking accumulates thinking in subAgentStreams[agentId].thinking', () => {
    chatState().spawnSubAgent(JSON.stringify({ agentId: 'sa-6', label: 'Thinker' }));
    chatState().appendSubAgentThinking('sa-6', 'plan A');
    chatState().appendSubAgentThinking('sa-6', ' then plan B');

    const slice = chatState().subAgentStreams['sa-6'];
    expect(slice?.thinking).toBe('plan A then plan B');
  });

  it('updateSubAgentIteration with active stream updates iteration in side-channel', () => {
    chatState().spawnSubAgent(JSON.stringify({ agentId: 'sa-7', label: 'Worker' }));
    chatState().appendSubAgentStreamDelta(JSON.stringify({ agentId: 'sa-7', delta: 't' }));
    chatState().updateSubAgentIteration(JSON.stringify({ agentId: 'sa-7', iteration: 5 }));

    expect(chatState().subAgentStreams['sa-7']?.iteration).toBe(5);
    // messages[].subagentData.iteration must NOT be updated during streaming.
    const msg = chatState().messages.find(m => m.subagentData?.agentId === 'sa-7');
    expect(msg?.subagentData?.iteration).toBe(1); // unchanged from spawnSubAgent default
  });

  it('setSubAgentStatusNote with active stream updates statusNote in side-channel', () => {
    chatState().spawnSubAgent(JSON.stringify({ agentId: 'sa-8', label: 'Worker' }));
    chatState().appendSubAgentStreamDelta(JSON.stringify({ agentId: 'sa-8', delta: 't' }));
    chatState().setSubAgentStatusNote(JSON.stringify({ agentId: 'sa-8', note: 'timeout — retrying' }));

    expect(chatState().subAgentStreams['sa-8']?.statusNote).toBe('timeout — retrying');
    // messages[].subagentData.statusNote must NOT be updated during streaming.
    const msg = chatState().messages.find(m => m.subagentData?.agentId === 'sa-8');
    expect(msg?.subagentData?.statusNote).toBeUndefined();
  });

  // ── completeSubAgent: flushes side-channel into messages[] and clears it ──

  it('completeSubAgent commits accumulated streaming text as TEXT message in subagentData.messages', () => {
    chatState().spawnSubAgent(JSON.stringify({ agentId: 'sa-9', label: 'Summarizer' }));
    chatState().appendSubAgentStreamDelta(JSON.stringify({ agentId: 'sa-9', delta: 'Found ' }));
    chatState().appendSubAgentStreamDelta(JSON.stringify({ agentId: 'sa-9', delta: '3 issues.' }));

    chatState().completeSubAgent(JSON.stringify({ agentId: 'sa-9', textContent: 'Done.' }));

    const msg = chatState().messages.find(m => m.subagentData?.agentId === 'sa-9');
    expect(msg?.say).toBe('SUBAGENT_COMPLETED');
    expect(msg?.subagentData?.status).toBe('COMPLETED');
    // Accumulated stream text committed as a child TEXT message.
    const textMsgs = msg?.subagentData?.messages.filter(m => m.say === 'TEXT') ?? [];
    expect(textMsgs).toHaveLength(1);
    expect(textMsgs[0]!.text).toBe('Found 3 issues.');
    expect(textMsgs[0]!.partial).toBe(false);
  });

  it('completeSubAgent removes the side-channel entry', () => {
    chatState().spawnSubAgent(JSON.stringify({ agentId: 'sa-10', label: 'Coder' }));
    chatState().appendSubAgentStreamDelta(JSON.stringify({ agentId: 'sa-10', delta: 'code' }));
    expect(chatState().subAgentStreams['sa-10']).toBeDefined();

    chatState().completeSubAgent(JSON.stringify({ agentId: 'sa-10', textContent: 'done' }));

    expect(chatState().subAgentStreams['sa-10']).toBeUndefined();
  });

  it('completeSubAgent with no streamed text does not add a TEXT child message', () => {
    chatState().spawnSubAgent(JSON.stringify({ agentId: 'sa-11', label: 'Empty' }));
    // No appendSubAgentStreamDelta — nothing in side-channel.

    chatState().completeSubAgent(JSON.stringify({ agentId: 'sa-11', textContent: 'Clean finish.' }));

    const msg = chatState().messages.find(m => m.subagentData?.agentId === 'sa-11');
    const textMsgs = msg?.subagentData?.messages.filter(m => m.say === 'TEXT') ?? [];
    expect(textMsgs).toHaveLength(0);
  });

  it('completeSubAgent flushes accumulated thinking as REASONING before TEXT', () => {
    chatState().spawnSubAgent(JSON.stringify({ agentId: 'sa-12', label: 'Thinker' }));
    chatState().appendSubAgentThinking('sa-12', 'deep thought');
    chatState().appendSubAgentStreamDelta(JSON.stringify({ agentId: 'sa-12', delta: 'result text' }));

    chatState().completeSubAgent(JSON.stringify({ agentId: 'sa-12', textContent: 'ok' }));

    const msg = chatState().messages.find(m => m.subagentData?.agentId === 'sa-12');
    const childMsgs = msg?.subagentData?.messages ?? [];
    const reasoningIdx = childMsgs.findIndex(m => m.say === 'REASONING');
    const textIdx = childMsgs.findIndex(m => m.say === 'TEXT');
    expect(reasoningIdx).toBeGreaterThanOrEqual(0);
    expect(textIdx).toBeGreaterThanOrEqual(0);
    expect(reasoningIdx).toBeLessThan(textIdx); // REASONING before TEXT
  });

  // ── killSubAgent: clears side-channel ──

  it('killSubAgent removes the side-channel entry', () => {
    chatState().spawnSubAgent(JSON.stringify({ agentId: 'sa-13', label: 'Worker' }));
    chatState().appendSubAgentStreamDelta(JSON.stringify({ agentId: 'sa-13', delta: 'partial' }));
    expect(chatState().subAgentStreams['sa-13']).toBeDefined();

    chatState().killSubAgent('sa-13');

    expect(chatState().subAgentStreams['sa-13']).toBeUndefined();
  });

  // ── clearChat clears the side-channel ──

  it('clearChat resets subAgentStreams to empty', () => {
    chatState().spawnSubAgent(JSON.stringify({ agentId: 'sa-14', label: 'Temp' }));
    chatState().appendSubAgentStreamDelta(JSON.stringify({ agentId: 'sa-14', delta: 'data' }));
    expect(Object.keys(chatState().subAgentStreams).length).toBeGreaterThan(0);

    chatState().clearChat();

    expect(chatState().subAgentStreams).toEqual({});
  });

  // ── endSubAgentThinking: flushes into messages[], clears thinking in side-channel ──

  it('endSubAgentThinking flushes side-channel thinking into subagentData.messages as REASONING', () => {
    chatState().spawnSubAgent(JSON.stringify({ agentId: 'sa-15', label: 'Reasoner' }));
    chatState().appendSubAgentThinking('sa-15', 'my reasoning');

    chatState().endSubAgentThinking('sa-15');

    // The thinking is now committed as a REASONING child message.
    const msg = chatState().messages.find(m => m.subagentData?.agentId === 'sa-15');
    const reasoningMsgs = msg?.subagentData?.messages.filter(m => m.say === 'REASONING') ?? [];
    expect(reasoningMsgs).toHaveLength(1);
    expect(reasoningMsgs[0]!.text).toBe('my reasoning');

    // Side-channel thinking is cleared; text entry may remain.
    expect(chatState().subAgentStreams['sa-15']?.thinking).toBeNull();
  });

  it('endSubAgentThinking on empty thinking is a no-op (no REASONING message added)', () => {
    chatState().spawnSubAgent(JSON.stringify({ agentId: 'sa-16', label: 'Silent' }));
    chatState().endSubAgentThinking('sa-16');

    const msg = chatState().messages.find(m => m.subagentData?.agentId === 'sa-16');
    const reasoningMsgs = msg?.subagentData?.messages.filter(m => m.say === 'REASONING') ?? [];
    expect(reasoningMsgs).toHaveLength(0);
  });
});
