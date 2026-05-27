import { describe, it, expect, beforeEach } from 'vitest';
import { useChatStore } from '../chatStore';

/**
 * Regression: thinking blocks, tool calls, and streaming text must render in
 * true emission order. Bug: `endThinking` committed a REASONING block into
 * `messages[]` immediately, while tool calls / streaming text sat in side
 * channels (`activeToolCalls` map / `streamingText`) that only drained later —
 * so anything emitted BETWEEN two thinking blocks rendered AFTER both.
 *
 * See: project_chat_file_document_attachments memory + the brainstorm finding
 * (chatStore.ts endThinking vs appendToken asymmetry).
 */
describe('chatStore — thinking / tool / text emission ordering', () => {
  beforeEach(() => {
    useChatStore.setState({
      messages: [],
      activeToolCalls: new Map(),
      toolOutputStreams: {},
      streamingText: null,
      streamingMsgTs: null,
      streamingThinkingText: null,
      streamingThinkingTs: null,
    } as never);
  });

  const order = () =>
    useChatStore
      .getState()
      .messages.filter(m => m.say === 'REASONING' || m.say === 'TOOL' || m.say === 'TEXT')
      .map(m =>
        m.say === 'TOOL'
          ? `TOOL:${m.toolCallData!.toolCallId}`
          : m.say === 'REASONING'
            ? `R:${m.text}`
            : `T:${m.text}`,
      );

  it('think → tool → think renders the tool BETWEEN the two thinking blocks', () => {
    const s = () => useChatStore.getState();
    s().appendToThinking('reasoning one');
    s().endThinking();
    s().addToolCall('A', 'read_file', '{}', 'COMPLETED');
    s().appendToThinking('reasoning two');
    s().endThinking();

    expect(order()).toEqual(['R:reasoning one', 'TOOL:A', 'R:reasoning two']);
  });

  it('think → text → think keeps the text BETWEEN the two thinking blocks', () => {
    const s = () => useChatStore.getState();
    s().appendToThinking('r1');
    s().endThinking();
    s().appendToken('hello text');
    s().appendToThinking('r2');
    s().endThinking();

    expect(order()).toEqual(['R:r1', 'T:hello text', 'R:r2']);
  });
});
