/**
 * Scenario tests for the unified streaming-message model in `chatStore`.
 *
 * Contracts:
 * - The first `appendToken` of a new stream creates a placeholder `UiMessage`
 *   in `messages` with `say:'TEXT'`, `partial:true`, and points
 *   `activeStream.messageTs` at its `ts`.
 * - Subsequent `appendToken` calls update that specific message's `text`
 *   in place via `.map(...)`. The message ts never changes during a stream.
 * - `endStream` just clears `activeStream` and sets `partial:false`. It MUST
 *   NOT add or remove any messages -- the placeholder stays where it is with
 *   identical ts and text, so React reconciles in place (no remount -> no flash).
 * - A second stream after `endStream` creates a brand-new placeholder with
 *   a different ts.
 * - A tool call arriving mid-stream clears the caret but must not duplicate
 *   the streaming text into a second message.
 * - Non-streaming messages keep their exact object reference across token
 *   updates, so `React.memo` can skip re-rendering them and streaming stays
 *   O(1) in the growing message per token.
 */
import { describe, it, expect, beforeEach, vi, afterEach } from 'vitest';
import { chatState, resetChatStore } from './chat-store-test-utils';

describe('chatStore -- unified streaming-message model', () => {
  // Use fake timers to ensure Date.now() returns distinct values across
  // successive store actions (real Date.now() can return the same ms in
  // fast synchronous test code, causing ts collisions).
  let fakeNow = 1000000;
  beforeEach(() => {
    vi.spyOn(Date, 'now').mockImplementation(() => ++fakeNow);
    resetChatStore();
  });
  afterEach(() => {
    vi.restoreAllMocks();
  });

  it('first appendToken creates a placeholder agent message and points activeStream at it', () => {
    chatState().appendToken('Hello');

    const state = chatState();
    expect(state.messages).toHaveLength(1);
    expect(state.messages[0]!.say).toBe('TEXT');
    expect(state.messages[0]!.text).toBe('Hello');
    expect(state.messages[0]!.partial).toBe(true);
    expect(state.activeStream).not.toBeNull();
    expect(state.activeStream!.messageTs).toBe(state.messages[0]!.ts);
  });

  it('empty-token appendToken is a no-op: no placeholder, no wasted state churn', () => {
    chatState().appendToken('');
    const state = chatState();
    expect(state.messages).toHaveLength(0);
    expect(state.activeStream).toBeNull();
  });

  it('subsequent appendToken updates the SAME message in place -- ts never changes', () => {
    const { appendToken } = chatState();
    appendToken('Hello');
    const firstTs = chatState().messages[0]!.ts;

    appendToken(', world');
    appendToken('. How are you?');

    const state = chatState();
    expect(state.messages).toHaveLength(1);
    expect(state.messages[0]!.ts).toBe(firstTs);
    expect(state.messages[0]!.text).toBe('Hello, world. How are you?');
    expect(state.activeStream?.messageTs).toBe(firstTs);
  });

  it('endStream does NOT add or remove messages -- it only clears activeStream and sets partial:false', () => {
    const { appendToken, endStream } = chatState();
    appendToken('Some text');
    appendToken(' streamed in.');

    const before = chatState();
    const beforeLength = before.messages.length;
    const beforeTs = before.messages[0]!.ts;
    const beforeText = before.messages[0]!.text;

    endStream();

    const after = chatState();
    // No new message was pushed; the placeholder stays in place with the
    // same ts and text. React sees the same key and same prop reference
    // -> no reconciliation delta -> no remount -> no flash.
    expect(after.messages).toHaveLength(beforeLength);
    expect(after.messages[0]!.ts).toBe(beforeTs);
    expect(after.messages[0]!.text).toBe(beforeText);
    expect(after.messages[0]!.partial).toBe(false);
    expect(after.activeStream).toBeNull();
  });

  it('a second stream after endStream creates a NEW placeholder with a different ts', () => {
    const { appendToken, endStream } = chatState();
    appendToken('First response.');
    const firstTs = chatState().messages[0]!.ts;
    endStream();

    appendToken('Second response.');
    const state = chatState();

    expect(state.messages).toHaveLength(2);
    expect(state.messages[0]!.ts).toBe(firstTs);
    expect(state.messages[0]!.text).toBe('First response.');
    expect(state.messages[1]!.ts).not.toBe(firstTs);
    expect(state.messages[1]!.text).toBe('Second response.');
    expect(state.activeStream!.messageTs).toBe(state.messages[1]!.ts);
  });

  it('addToolCall mid-stream clears the caret but does NOT duplicate the streaming message', () => {
    const { appendToken, addToolCall } = chatState();
    appendToken('Reading ');
    appendToken('the file...');

    expect(chatState().messages.filter(m => m.say === 'TEXT')).toHaveLength(1);

    addToolCall('tc-1', 'read_file', '{"path":"a.kt"}', 'RUNNING');

    const state = chatState();
    const textMessages = state.messages.filter(m => m.say === 'TEXT');
    expect(textMessages).toHaveLength(1);
    expect(textMessages[0]!.text).toBe('Reading the file...');
    expect(state.activeStream).toBeNull();
    expect(state.activeToolCalls.size).toBe(1);
  });

  it('non-streaming messages keep the SAME object reference across token updates', () => {
    // Object identity (not deep equality) is the contract React.memo needs
    // to skip re-rendering unchanged messages per token. If the `.map` in
    // `appendToken` ever stops returning `m` by reference for non-matching
    // items, every message re-renders on every token and streaming perf
    // collapses.
    const { addUserMessage, appendToken } = chatState();
    addUserMessage('First user question');
    const userMsgBefore = chatState().messages[0]!;

    appendToken('Starting agent response');
    appendToken(' with more tokens');
    appendToken(' and more still.');

    const userMsgAfter = chatState().messages[0]!;
    expect(userMsgAfter).toBe(userMsgBefore);
  });
});
