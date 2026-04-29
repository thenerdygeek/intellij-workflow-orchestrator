/**
 * Scenario tests for the streaming-isolation model in `chatStore`.
 *
 * Contracts (new streaming-isolation model):
 * - The first `appendToken` of a new stream sets `streamingText` and
 *   `streamingMsgTs`. NO placeholder is added to `messages` — so the
 *   `messages` array reference never changes during streaming.
 * - Subsequent `appendToken` calls concatenate onto `streamingText`.
 *   The `messages` array reference stays the same for the full stream.
 * - `endStream` flushes `streamingText` into `messages` as a finalized
 *   UiMessage using `streamingMsgTs` as the ts. It then clears both fields.
 * - A second stream after `endStream` creates a brand-new `streamingMsgTs`
 *   with a different ts.
 * - A tool call arriving mid-stream flushes the streaming buffer into
 *   `messages` and clears `streamingText`.
 * - Non-streaming messages keep their exact object reference across token
 *   updates, so `React.memo` can skip re-rendering them.
 */
import { describe, it, expect, beforeEach, vi, afterEach } from 'vitest';
import { chatState, resetChatStore } from './chat-store-test-utils';

describe('chatStore -- streaming-isolation model', () => {
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

  it('first appendToken sets streamingText and streamingMsgTs, does NOT add to messages', () => {
    chatState().appendToken('Hello');

    const state = chatState();
    expect(state.messages).toHaveLength(0);
    expect(state.streamingText).toBe('Hello');
    expect(state.streamingMsgTs).not.toBeNull();
  });

  it('empty-token appendToken is a no-op: no streamingText, no wasted state churn', () => {
    chatState().appendToken('');
    const state = chatState();
    expect(state.messages).toHaveLength(0);
    expect(state.streamingText).toBeNull();
    expect(state.streamingMsgTs).toBeNull();
  });

  it('subsequent appendToken concatenates onto streamingText -- messages array stays the same reference', () => {
    const { addUserMessage, appendToken } = chatState();
    addUserMessage('question');
    const messagesBefore = chatState().messages;

    appendToken('Hello');
    appendToken(', world');
    appendToken('. How are you?');

    const state = chatState();
    expect(state.messages).toBe(messagesBefore);
    expect(state.streamingText).toBe('Hello, world. How are you?');
  });

  it('endStream flushes streamingText into messages with streamingMsgTs as ts and partial:false', () => {
    const { appendToken, endStream } = chatState();
    appendToken('Some text');
    appendToken(' streamed in.');

    const streamTs = chatState().streamingMsgTs;
    const beforeMsgCount = chatState().messages.length;

    endStream();

    const after = chatState();
    // One new message was added by endStream.
    expect(after.messages).toHaveLength(beforeMsgCount + 1);
    const last = after.messages.at(-1)!;
    expect(last.ts).toBe(streamTs);
    expect(last.text).toBe('Some text streamed in.');
    expect(last.partial).toBe(false);
    expect(after.streamingText).toBeNull();
    expect(after.streamingMsgTs).toBeNull();
  });

  it('a second stream after endStream creates a NEW streamingMsgTs with a different ts', () => {
    const { appendToken, endStream } = chatState();
    appendToken('First response.');
    const firstStreamTs = chatState().streamingMsgTs;
    endStream();

    appendToken('Second response.');
    const state = chatState();

    // endStream committed first message; second appendToken started new stream
    expect(state.messages).toHaveLength(1);
    expect(state.messages[0]!.text).toBe('First response.');
    expect(state.streamingText).toBe('Second response.');
    expect(state.streamingMsgTs).not.toBe(firstStreamTs);
    expect(state.streamingMsgTs).not.toBeNull();
  });

  it('addToolCall mid-stream flushes streamingText into messages and clears it', () => {
    const { appendToken, addToolCall } = chatState();
    appendToken('Reading ');
    appendToken('the file...');

    const streamTs = chatState().streamingMsgTs;
    expect(chatState().messages).toHaveLength(0);

    addToolCall('tc-1', 'read_file', '{"path":"a.kt"}', 'RUNNING');

    const state = chatState();
    // The streamed text was flushed into messages before the tool card
    const textMessages = state.messages.filter(m => m.say === 'TEXT');
    expect(textMessages).toHaveLength(1);
    expect(textMessages[0]!.ts).toBe(streamTs);
    expect(textMessages[0]!.text).toBe('Reading the file...');
    expect(textMessages[0]!.partial).toBe(false);
    expect(state.streamingText).toBeNull();
    expect(state.streamingMsgTs).toBeNull();
    expect(state.activeToolCalls.size).toBe(1);
  });

  it('non-streaming messages keep the SAME object reference across token updates', () => {
    // Object identity (not deep equality) is the contract React.memo needs
    // to skip re-rendering unchanged messages per token.
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
