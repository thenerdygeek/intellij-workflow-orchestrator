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

  it('completeSubAgent appends leftover thinking AFTER existing child messages (not prepended)', () => {
    // Final-iteration thinking belongs chronologically AFTER iteration-1 tool
    // calls. Prepending ([thinkingMsg, ...messages]) would place it before them.
    chatState().spawnSubAgent(JSON.stringify({ agentId: 'sa-12b', label: 'Thinker' }));
    // Commit an iteration-1 TOOL child message into sub.messages.
    chatState().addSubAgentToolCall(JSON.stringify({ agentId: 'sa-12b', toolCallId: 'tc-1', toolName: 'read_file', toolArgs: '{}' }));
    chatState().updateSubAgentToolCall(JSON.stringify({ agentId: 'sa-12b', toolCallId: 'tc-1', toolName: 'read_file', toolResult: 'ok' }));
    // Final-iteration leftover thinking + streamed text.
    chatState().appendSubAgentThinking('sa-12b', 'final thought');
    chatState().appendSubAgentStreamDelta(JSON.stringify({ agentId: 'sa-12b', delta: 'final text' }));

    chatState().completeSubAgent(JSON.stringify({ agentId: 'sa-12b', textContent: 'ok' }));

    const msg = chatState().messages.find(m => m.subagentData?.agentId === 'sa-12b');
    const childMsgs = msg?.subagentData?.messages ?? [];
    const toolIdx = childMsgs.findIndex(m => m.say === 'TOOL');
    const reasoningIdx = childMsgs.findIndex(m => m.say === 'REASONING');
    const textIdx = childMsgs.findIndex(m => m.say === 'TEXT');
    expect(toolIdx).toBeGreaterThanOrEqual(0);
    expect(reasoningIdx).toBeGreaterThanOrEqual(0);
    expect(textIdx).toBeGreaterThanOrEqual(0);
    // Chronological order: TOOL (iter 1) → REASONING (final thinking) → TEXT (final stream).
    expect(toolIdx).toBeLessThan(reasoningIdx);
    expect(reasoningIdx).toBeLessThan(textIdx);
  });

  // ── killSubAgent: commits side-channel content, then clears it ──

  it('killSubAgent removes the side-channel entry', () => {
    chatState().spawnSubAgent(JSON.stringify({ agentId: 'sa-13', label: 'Worker' }));
    chatState().appendSubAgentStreamDelta(JSON.stringify({ agentId: 'sa-13', delta: 'partial' }));
    expect(chatState().subAgentStreams['sa-13']).toBeDefined();

    chatState().killSubAgent('sa-13');

    expect(chatState().subAgentStreams['sa-13']).toBeUndefined();
  });

  it('killSubAgent after deltas preserves partial text in subagentData.messages', () => {
    // Regression: kill must NOT lose work-in-progress output — mirror
    // completeSubAgent's flush, only the status differs (KILLED).
    chatState().spawnSubAgent(JSON.stringify({ agentId: 'sa-17', label: 'Worker' }));
    chatState().appendSubAgentStreamDelta(JSON.stringify({ agentId: 'sa-17', delta: 'partial ' }));
    chatState().appendSubAgentStreamDelta(JSON.stringify({ agentId: 'sa-17', delta: 'output' }));

    chatState().killSubAgent('sa-17');

    const msg = chatState().messages.find(m => m.subagentData?.agentId === 'sa-17');
    expect(msg?.subagentData?.status).toBe('KILLED');
    const textMsgs = msg?.subagentData?.messages.filter(m => m.say === 'TEXT') ?? [];
    expect(textMsgs).toHaveLength(1);
    expect(textMsgs[0]!.text).toBe('partial output');
    expect(textMsgs[0]!.partial).toBe(false);
  });

  it('killSubAgent flushes accumulated thinking as REASONING after existing children', () => {
    chatState().spawnSubAgent(JSON.stringify({ agentId: 'sa-18', label: 'Worker' }));
    chatState().addSubAgentToolCall(JSON.stringify({ agentId: 'sa-18', toolCallId: 'tc-k1', toolName: 'search_code', toolArgs: '{}' }));
    chatState().updateSubAgentToolCall(JSON.stringify({ agentId: 'sa-18', toolCallId: 'tc-k1', toolName: 'search_code', toolResult: 'ok' }));
    chatState().appendSubAgentThinking('sa-18', 'mid-kill reasoning');
    chatState().appendSubAgentStreamDelta(JSON.stringify({ agentId: 'sa-18', delta: 'some text' }));

    chatState().killSubAgent('sa-18');

    const msg = chatState().messages.find(m => m.subagentData?.agentId === 'sa-18');
    const childMsgs = msg?.subagentData?.messages ?? [];
    const toolIdx = childMsgs.findIndex(m => m.say === 'TOOL');
    const reasoningIdx = childMsgs.findIndex(m => m.say === 'REASONING');
    const textIdx = childMsgs.findIndex(m => m.say === 'TEXT');
    expect(reasoningIdx).toBeGreaterThanOrEqual(0);
    expect(childMsgs.find(m => m.say === 'REASONING')?.text).toBe('mid-kill reasoning');
    expect(toolIdx).toBeLessThan(reasoningIdx);
    expect(reasoningIdx).toBeLessThan(textIdx);
  });

  it('killSubAgent with empty side-channel adds no child messages', () => {
    chatState().spawnSubAgent(JSON.stringify({ agentId: 'sa-19', label: 'Worker' }));

    chatState().killSubAgent('sa-19');

    const msg = chatState().messages.find(m => m.subagentData?.agentId === 'sa-19');
    expect(msg?.subagentData?.status).toBe('KILLED');
    expect(msg?.subagentData?.messages ?? []).toHaveLength(0);
  });

  // ── Session transitions clear the side-channel (stale-slice bleed guard) ──

  it('clearChat resets subAgentStreams to empty', () => {
    chatState().spawnSubAgent(JSON.stringify({ agentId: 'sa-14', label: 'Temp' }));
    chatState().appendSubAgentStreamDelta(JSON.stringify({ agentId: 'sa-14', delta: 'data' }));
    expect(Object.keys(chatState().subAgentStreams).length).toBeGreaterThan(0);

    chatState().clearChat();

    expect(chatState().subAgentStreams).toEqual({});
  });

  it('startSession resets subAgentStreams to empty', () => {
    chatState().spawnSubAgent(JSON.stringify({ agentId: 'sa-20', label: 'Stale' }));
    chatState().appendSubAgentStreamDelta(JSON.stringify({ agentId: 'sa-20', delta: 'stale' }));
    expect(chatState().subAgentStreams['sa-20']).toBeDefined();

    chatState().startSession('new task');

    expect(chatState().subAgentStreams).toEqual({});
  });

  it('completeSession resets subAgentStreams to empty', () => {
    chatState().spawnSubAgent(JSON.stringify({ agentId: 'sa-21', label: 'Stale' }));
    chatState().appendSubAgentStreamDelta(JSON.stringify({ agentId: 'sa-21', delta: 'stale' }));
    expect(chatState().subAgentStreams['sa-21']).toBeDefined();

    chatState().completeSession({ status: 'COMPLETED', tokensUsed: 0, durationMs: 0, iterations: 0, filesModified: [] });

    expect(chatState().subAgentStreams).toEqual({});
  });

  it('hydrateFromUiMessages clears a pre-existing subAgentStreams entry', () => {
    // Resume-path bleed: a slice left over from the previously live session must
    // not attach to a same-agentId card in the loaded session.
    chatState().spawnSubAgent(JSON.stringify({ agentId: 'sa-22', label: 'Stale' }));
    chatState().appendSubAgentStreamDelta(JSON.stringify({ agentId: 'sa-22', delta: 'stale text' }));
    expect(chatState().subAgentStreams['sa-22']).toBeDefined();

    chatState().hydrateFromUiMessages([]);

    expect(chatState().subAgentStreams).toEqual({});
  });

  // ── tokensUsed flows through the side-channel while streaming ──

  it('updateSubAgentIteration with active stream carries tokensUsed in side-channel without touching messages[]', () => {
    chatState().spawnSubAgent(JSON.stringify({ agentId: 'sa-23', label: 'Worker' }));
    chatState().appendSubAgentStreamDelta(JSON.stringify({ agentId: 'sa-23', delta: 't' }));
    const msgsBefore = chatState().messages;

    chatState().updateSubAgentIteration(JSON.stringify({ agentId: 'sa-23', iteration: 3, tokensUsed: 4200 }));

    expect(chatState().messages).toBe(msgsBefore);
    expect(chatState().subAgentStreams['sa-23']?.tokensUsed).toBe(4200);
  });

  it('completeSubAgent writes back side-channel tokensUsed when payload omits it', () => {
    chatState().spawnSubAgent(JSON.stringify({ agentId: 'sa-24', label: 'Worker' }));
    chatState().appendSubAgentStreamDelta(JSON.stringify({ agentId: 'sa-24', delta: 't' }));
    chatState().updateSubAgentIteration(JSON.stringify({ agentId: 'sa-24', iteration: 2, tokensUsed: 9000 }));

    chatState().completeSubAgent(JSON.stringify({ agentId: 'sa-24', textContent: 'done' }));

    const msg = chatState().messages.find(m => m.subagentData?.agentId === 'sa-24');
    expect(msg?.subagentData?.tokensUsed).toBe(9000);
  });

  it('killSubAgent writes back side-channel tokensUsed and iteration', () => {
    chatState().spawnSubAgent(JSON.stringify({ agentId: 'sa-25', label: 'Worker' }));
    chatState().appendSubAgentStreamDelta(JSON.stringify({ agentId: 'sa-25', delta: 't' }));
    chatState().updateSubAgentIteration(JSON.stringify({ agentId: 'sa-25', iteration: 4, tokensUsed: 1234 }));

    chatState().killSubAgent('sa-25');

    const msg = chatState().messages.find(m => m.subagentData?.agentId === 'sa-25');
    expect(msg?.subagentData?.tokensUsed).toBe(1234);
    expect(msg?.subagentData?.iteration).toBe(4);
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
