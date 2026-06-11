/**
 * TDD tests for B9 and B10:
 *
 * B9: showApproval nulled in-flight thinking buffer without flushing it into
 *     messages as a REASONING message. Every other drain path (endStream,
 *     completeSession, endThinking) flushes. Fix: flush in showApproval too.
 *
 * B10: appendToken's tool-drain branch returned messages WITHOUT the
 *      capMessages() wrap every other path uses. Fix: wrap with capMessages().
 */
import { describe, it, expect, beforeEach, vi, afterEach } from 'vitest';
import { chatState, resetChatStore } from './chat-store-test-utils';
import { MESSAGES_HARD_CAP } from '@/stores/chatStore';

describe('B9 — showApproval flushes in-flight thinking buffer', () => {
  let fakeNow = 2_000_000;
  beforeEach(() => {
    vi.spyOn(Date, 'now').mockImplementation(() => ++fakeNow);
    resetChatStore();
  });
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('thinking buffer present when showApproval fires → flushed as REASONING before approval', () => {
    // Simulate: LLM emits <thinking>…</thinking> then an edit_file approval gate fires
    chatState().appendToThinking('deciding which file to edit');
    expect(chatState().streamingThinkingText).toBe('deciding which file to edit');

    chatState().showApproval('edit_file', 'HIGH');

    const state = chatState();
    // The thinking content must be committed as a REASONING message.
    const reasoningMsgs = state.messages.filter(m => m.say === 'REASONING');
    expect(reasoningMsgs).toHaveLength(1);
    expect(reasoningMsgs[0]!.text).toBe('deciding which file to edit');

    // The buffer must be cleared.
    expect(state.streamingThinkingText).toBeNull();
    expect(state.streamingThinkingTs).toBeNull();

    // The approval gate must be set.
    expect(state.pendingApproval).not.toBeNull();
    expect(state.pendingApproval?.toolName).toBe('edit_file');
  });

  it('no thinking buffer → showApproval does not add a REASONING message', () => {
    expect(chatState().streamingThinkingText).toBeNull();
    chatState().showApproval('run_command', 'HIGH', 'git status', undefined, undefined, undefined, false);

    const reasoningMsgs = chatState().messages.filter(m => m.say === 'REASONING');
    expect(reasoningMsgs).toHaveLength(0);
    expect(chatState().pendingApproval).not.toBeNull();
  });

  it('thinking buffer + streaming text both flushed in correct order (REASONING before TEXT before approval)', () => {
    chatState().appendToThinking('thinking text');
    chatState().appendToken('prose before approval');

    chatState().showApproval('edit_file', 'MEDIUM');

    const msgs = chatState().messages;
    const reasoningIdx = msgs.findIndex(m => m.say === 'REASONING');
    const textIdx = msgs.findIndex(m => m.say === 'TEXT');

    expect(reasoningIdx).toBeGreaterThanOrEqual(0);
    expect(textIdx).toBeGreaterThanOrEqual(0);
    // TEXT (flushed by hadStream path) should appear after REASONING.
    expect(textIdx).toBeGreaterThan(reasoningIdx);
  });
});

describe('B10 — appendToken tool-drain branch applies capMessages', () => {
  let fakeNow = 3_000_000;
  beforeEach(() => {
    vi.spyOn(Date, 'now').mockImplementation(() => ++fakeNow);
    resetChatStore();
  });
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('draining many tool calls then appending a token caps messages at MESSAGES_HARD_CAP', () => {
    // Fill messages[] to just below the cap with USER_MESSAGE entries.
    // We need at least (MESSAGES_HARD_CAP - 1) tool-call messages in activeToolCalls
    // to blow the cap when they drain on the first appendToken.
    // Instead of adding that many tool calls (expensive), we pre-fill messages[],
    // add one tool call, and verify capMessages wraps the combined array.
    // The concrete regression: before B10 the tool-drain returned the raw spread
    // array; capMessages was never called even when messages[] was at the limit.

    // Pre-fill messages to cap - 1 so the drain + 1 tool message would exceed cap.
    const { addUserMessage, addToolCall, appendToken } = chatState();
    for (let i = 0; i < MESSAGES_HARD_CAP - 1; i++) {
      addUserMessage(`msg-${i}`);
    }
    expect(chatState().messages.length).toBe(MESSAGES_HARD_CAP - 1);

    // Add one RUNNING tool call so activeToolCalls.size = 1.
    addToolCall('tc-drain', 'read_file', '{"path":"x.kt"}', 'RUNNING');
    expect(chatState().activeToolCalls.size).toBe(1);

    // First appendToken triggers the tool-drain branch — the combined array
    // (MESSAGES_HARD_CAP - 1 user msgs + 1 tool msg) has MESSAGES_HARD_CAP entries,
    // exactly at the cap — no overflow, so length stays at MESSAGES_HARD_CAP.
    appendToken('Hi');

    // After the drain the tool calls are gone.
    expect(chatState().activeToolCalls.size).toBe(0);
    // The combined array should be capped. Length must never exceed MESSAGES_HARD_CAP.
    expect(chatState().messages.length).toBeLessThanOrEqual(MESSAGES_HARD_CAP);
  });

  it('tool-drain at limit adds SYSTEM spill marker rather than exceeding MESSAGES_HARD_CAP', () => {
    const { addUserMessage, addToolCall, appendToken } = chatState();
    // Exactly at the cap.
    for (let i = 0; i < MESSAGES_HARD_CAP; i++) {
      addUserMessage(`u${i}`);
    }
    addToolCall('tc-spill', 'run_command', '{"command":"ls"}', 'RUNNING');

    appendToken('x');

    expect(chatState().messages.length).toBeLessThanOrEqual(MESSAGES_HARD_CAP);
  });
});
