import { describe, it, expect, beforeEach } from 'vitest';
import { useChatStore } from '../stores/chatStore';

describe('chatStore — steering queue ordering', () => {
  beforeEach(() => {
    useChatStore.getState().clearChat();
    // clearChat does not reset queuedSteeringMessages; reset explicitly so each
    // test starts from a clean slate.
    useChatStore.setState({ queuedSteeringMessages: [] });
  });

  it('promoteQueuedSteeringMessages drains activeToolCalls before appending the user message', () => {
    const store = useChatStore.getState();

    // Simulate: agent issued 2 tool calls that are still running in the footer.
    // Signature: addToolCall(toolCallId, name, args, status, toolTimeoutSeconds?)
    store.addToolCall('tc-1', 'read_file', '{"path":"a.kt"}', 'RUNNING');
    store.addToolCall('tc-2', 'read_file', '{"path":"b.kt"}', 'RUNNING');

    // Simulate: user types during execution.
    store.addQueuedSteeringMessage('steer-1', 'wait, also do C');

    expect(useChatStore.getState().messages).toHaveLength(0);
    expect(useChatStore.getState().activeToolCalls.size).toBe(2);
    expect(useChatStore.getState().queuedSteeringMessages).toHaveLength(1);

    // Agent loop drains the steering queue.
    store.promoteQueuedSteeringMessages(['steer-1']);

    const msgs = useChatStore.getState().messages;
    // Contract: tool calls drain first, THEN the user message lands at the bottom.
    expect(msgs).toHaveLength(3);
    expect(msgs[0].say).toBe('TOOL');
    expect(msgs[0].toolCallData?.toolCallId).toBe('tc-1');
    expect(msgs[1].say).toBe('TOOL');
    expect(msgs[1].toolCallData?.toolCallId).toBe('tc-2');
    expect(msgs[2].say).toBe('USER_MESSAGE');
    expect(msgs[2].text).toBe('wait, also do C');
    expect(useChatStore.getState().activeToolCalls.size).toBe(0);
    expect(useChatStore.getState().queuedSteeringMessages).toHaveLength(0);
  });

  it('promoteQueuedSteeringMessages flushes streamingText before appending the user message', () => {
    const store = useChatStore.getState();

    store.appendToken('partial agent thought before user steers...');
    store.addQueuedSteeringMessage('steer-2', 'actually do this instead');

    expect(useChatStore.getState().streamingText).not.toBeNull();

    store.promoteQueuedSteeringMessages(['steer-2']);

    const msgs = useChatStore.getState().messages;
    // Contract: streaming text finalizes into a message, then the user message lands below it.
    expect(msgs).toHaveLength(2);
    expect(msgs[0].say).toBe('TEXT');
    expect(msgs[0].text).toBe('partial agent thought before user steers...');
    expect((msgs[0] as any).partial).toBe(false);
    expect(msgs[1].say).toBe('USER_MESSAGE');
    expect(msgs[1].text).toBe('actually do this instead');
    expect(useChatStore.getState().streamingText).toBeNull();
  });
});
