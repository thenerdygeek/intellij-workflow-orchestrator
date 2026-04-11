/**
 * Scenario tests for the unified streaming-message model in `chatStore`.
 *
 * Contracts:
 * - The first `appendToken` of a new stream creates a placeholder `Message`
 *   in `messages` with a fresh id and points `activeStream.messageId` at it.
 * - Subsequent `appendToken` calls update that specific message's `content`
 *   in place via `.map(...)`. The message id never changes during a stream.
 * - `endStream` just clears `activeStream`. It MUST NOT add or remove any
 *   messages — the placeholder stays where it is with identical id and
 *   content, so React reconciles in place (no remount → no flash).
 * - A second stream after `endStream` creates a brand-new placeholder with
 *   a different id.
 * - A tool call arriving mid-stream clears the caret but must not duplicate
 *   the streaming text into a second message.
 * - Non-streaming messages keep their exact object reference across token
 *   updates, so `React.memo` can skip re-rendering them and streaming stays
 *   O(1) in the growing message per token.
 */
import { describe, it, expect, beforeEach } from 'vitest';
import { chatState, resetChatStore } from './chat-store-test-utils';

describe('chatStore — unified streaming-message model', () => {
  beforeEach(resetChatStore);

  it('first appendToken creates a placeholder agent message and points activeStream at it', () => {
    chatState().appendToken('Hello');

    const state = chatState();
    expect(state.messages).toHaveLength(1);
    expect(state.messages[0]!.role).toBe('agent');
    expect(state.messages[0]!.content).toBe('Hello');
    expect(state.activeStream).not.toBeNull();
    expect(state.activeStream!.messageId).toBe(state.messages[0]!.id);
  });

  it('empty-token appendToken is a no-op: no placeholder, no wasted state churn', () => {
    chatState().appendToken('');
    const state = chatState();
    expect(state.messages).toHaveLength(0);
    expect(state.activeStream).toBeNull();
  });

  it('subsequent appendToken updates the SAME message in place — id never changes', () => {
    const { appendToken } = chatState();
    appendToken('Hello');
    const firstId = chatState().messages[0]!.id;

    appendToken(', world');
    appendToken('. How are you?');

    const state = chatState();
    expect(state.messages).toHaveLength(1);
    expect(state.messages[0]!.id).toBe(firstId);
    expect(state.messages[0]!.content).toBe('Hello, world. How are you?');
    expect(state.activeStream?.messageId).toBe(firstId);
  });

  it('endStream does NOT add or remove messages — it only clears activeStream', () => {
    const { appendToken, endStream } = chatState();
    appendToken('Some text');
    appendToken(' streamed in.');

    const before = chatState();
    const beforeLength = before.messages.length;
    const beforeId = before.messages[0]!.id;
    const beforeContent = before.messages[0]!.content;

    endStream();

    const after = chatState();
    // No new message was pushed; the placeholder stays in place with the
    // same id and content. React sees the same key and same prop reference
    // → no reconciliation delta → no remount → no flash.
    expect(after.messages).toHaveLength(beforeLength);
    expect(after.messages[0]!.id).toBe(beforeId);
    expect(after.messages[0]!.content).toBe(beforeContent);
    expect(after.activeStream).toBeNull();
  });

  it('a second stream after endStream creates a NEW placeholder with a different id', () => {
    const { appendToken, endStream } = chatState();
    appendToken('First response.');
    const firstId = chatState().messages[0]!.id;
    endStream();

    appendToken('Second response.');
    const state = chatState();

    expect(state.messages).toHaveLength(2);
    expect(state.messages[0]!.id).toBe(firstId);
    expect(state.messages[0]!.content).toBe('First response.');
    expect(state.messages[1]!.id).not.toBe(firstId);
    expect(state.messages[1]!.content).toBe('Second response.');
    expect(state.activeStream!.messageId).toBe(state.messages[1]!.id);
  });

  it('addToolCall mid-stream clears the caret but does NOT duplicate the streaming message', () => {
    const { appendToken, addToolCall } = chatState();
    appendToken('Reading ');
    appendToken('the file...');

    expect(chatState().messages.filter(m => m.role === 'agent')).toHaveLength(1);

    addToolCall('tc-1', 'read_file', '{"path":"a.kt"}', 'RUNNING');

    const state = chatState();
    const agentMessages = state.messages.filter(m => m.role === 'agent');
    expect(agentMessages).toHaveLength(1);
    expect(agentMessages[0]!.content).toBe('Reading the file...');
    expect(state.activeStream).toBeNull();
    expect(state.activeToolCalls.size).toBe(1);
  });

  it('non-streaming messages keep the SAME object reference across token updates', () => {
    // Object identity (not deep equality) is the contract React.memo needs
    // to skip re-rendering unchanged messages per token. If the `.map` in
    // `appendToken` ever stops returning `m` by reference for non-matching
    // items, every message re-renders on every token and streaming perf
    // collapses.
    const { addMessage, appendToken } = chatState();
    addMessage('user', 'First user question');
    const userMsgBefore = chatState().messages[0]!;

    appendToken('Starting agent response');
    appendToken(' with more tokens');
    appendToken(' and more still.');

    const userMsgAfter = chatState().messages[0]!;
    expect(userMsgAfter).toBe(userMsgBefore);
  });
});
