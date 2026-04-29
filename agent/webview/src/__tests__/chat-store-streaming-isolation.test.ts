import { describe, it, expect, beforeEach } from 'vitest';
import { useChatStore } from '@/stores/chatStore';
import type { SessionStatus } from '@/bridge/types';

describe('chatStore streaming isolation', () => {
  beforeEach(() => {
    useChatStore.getState().clearChat();
  });

  it('appendToken does NOT bump the messages array reference', () => {
    const store = useChatStore;
    store.getState().addUserMessage('hello');
    const messagesBefore = store.getState().messages;

    store.getState().appendToken('Hi ');
    store.getState().appendToken('there ');
    store.getState().appendToken('friend.');

    const messagesAfter = store.getState().messages;
    expect(messagesAfter).toBe(messagesBefore);
    expect(store.getState().streamingText).toBe('Hi there friend.');
    expect(store.getState().streamingMsgTs).not.toBeNull();
  });

  it('endStream flushes streaming text into messages with the streaming ts as key', () => {
    const store = useChatStore;
    store.getState().addUserMessage('hello');
    store.getState().appendToken('Reply');
    const streamTs = store.getState().streamingMsgTs;

    store.getState().endStream();

    const last = store.getState().messages.at(-1)!;
    expect(last.ts).toBe(streamTs);
    expect(last.text).toBe('Reply');
    expect(last.partial).toBe(false);
    expect(store.getState().streamingText).toBeNull();
    expect(store.getState().streamingMsgTs).toBeNull();
  });

  it('first token drains active tool calls into finalized messages', () => {
    const store = useChatStore;
    store.getState().addToolCall('tc1', 'read_file', '{"path":"a"}', 'COMPLETED');
    store.getState().updateToolCall('read_file', 'COMPLETED', 'ok', 12, 'output', undefined, 'tc1');
    expect(store.getState().activeToolCalls.size).toBe(1);

    store.getState().appendToken('Now ');

    expect(store.getState().activeToolCalls.size).toBe(0);
    const lastMsg = store.getState().messages.at(-1)!;
    expect(lastMsg.say).toBe('TOOL');
    expect(store.getState().streamingText).toBe('Now ');
  });

  it('addCompletionCard mid-stream flushes the buffer before the completion card', () => {
    const store = useChatStore;
    store.getState().addUserMessage('hi');
    store.getState().appendToken('Final reply text.');
    const streamTs = store.getState().streamingMsgTs;
    expect(streamTs).not.toBeNull();

    store.getState().addCompletionCard({ kind: 'done', result: 'task complete' });

    const msgs = store.getState().messages;
    // Flushed text must precede the completion card and reuse the streaming ts.
    const lastTwo = msgs.slice(-2);
    expect(lastTwo[0]?.say).toBe('TEXT');
    expect(lastTwo[0]?.text).toBe('Final reply text.');
    expect(lastTwo[0]?.ts).toBe(streamTs);
    expect(lastTwo[0]?.partial).toBe(false);
    expect(lastTwo[1]?.ask).toBe('COMPLETION_RESULT');
    expect(store.getState().streamingText).toBeNull();
    expect(store.getState().streamingMsgTs).toBeNull();
  });

  it('completeSession mid-stream flushes the buffer into messages', () => {
    const store = useChatStore;
    store.getState().addUserMessage('hi');
    store.getState().appendToken('Partial reply.');
    const streamTs = store.getState().streamingMsgTs;
    expect(streamTs).not.toBeNull();

    store.getState().completeSession({
      status: 'COMPLETED' as SessionStatus,
      durationMs: 0,
      tokensUsed: 0,
      iterations: 0,
      filesModified: [],
    });

    const msgs = store.getState().messages;
    const flushed = msgs.find(m => m.ts === streamTs);
    expect(flushed?.text).toBe('Partial reply.');
    expect(flushed?.partial).toBe(false);
    expect(store.getState().streamingText).toBeNull();
    expect(store.getState().streamingMsgTs).toBeNull();
  });

  it('hydrateFromUiMessages clears any in-flight stream slice', () => {
    const store = useChatStore;
    store.getState().appendToken('orphaned ');
    store.getState().appendToken('mid-stream text');
    expect(store.getState().streamingText).not.toBeNull();

    store.getState().hydrateFromUiMessages([]);

    expect(store.getState().streamingText).toBeNull();
    expect(store.getState().streamingMsgTs).toBeNull();
  });
});
