/**
 * Session lifecycle must not strand or leak transient interactive UI:
 *  #6     completeSession must flush an in-flight thinking block (not drop it).
 *  #2     completeSession must clear pendingApproval / questions.
 *  #12    hydrateFromUiMessages (resume) must clear pendingApproval / questions /
 *         queuedSteeringMessages so a previous live session doesn't bleed in.
 */
import { describe, it, expect, beforeEach } from 'vitest';
import { useChatStore } from '../chatStore';

beforeEach(() => {
  useChatStore.setState({
    messages: [],
    streamingText: null,
    streamingMsgTs: null,
    streamingThinkingText: null,
    streamingThinkingTs: null,
    activeToolCalls: new Map(),
    pendingApproval: null,
    pendingProcessInput: null,
    questions: null,
    activeQuestionIndex: 0,
    queuedSteeringMessages: [],
  } as never);
});

describe('completeSession', () => {
  it('#6 flushes an in-flight thinking block into a REASONING message', () => {
    useChatStore.setState({ streamingThinkingText: 'partial reasoning', streamingThinkingTs: 999 } as never);
    useChatStore.getState().completeSession({} as never);
    const msgs = useChatStore.getState().messages;
    expect(msgs.some(m => m.say === 'REASONING' && m.text === 'partial reasoning')).toBe(true);
    expect(useChatStore.getState().streamingThinkingText).toBeNull();
  });

  it('#2 clears a stale pending approval and question wizard', () => {
    useChatStore.setState({
      pendingApproval: { toolName: 'run_command', riskLevel: 'high' },
      questions: [{ id: 'q1', question: 'pick', options: [], multiSelect: false }],
      activeQuestionIndex: 0,
    } as never);
    useChatStore.getState().completeSession({} as never);
    expect(useChatStore.getState().pendingApproval).toBeNull();
    expect(useChatStore.getState().questions).toBeNull();
  });
});

describe('hydrateFromUiMessages (resume)', () => {
  it('#12 clears transient UI from the previous live session', () => {
    useChatStore.setState({
      pendingApproval: { toolName: 'edit_file', riskLevel: 'medium' },
      questions: [{ id: 'q1', question: 'pick', options: [], multiSelect: false }],
      queuedSteeringMessages: [{ id: 's1', text: 'later' }],
    } as never);
    useChatStore.getState().hydrateFromUiMessages([]);
    expect(useChatStore.getState().pendingApproval).toBeNull();
    expect(useChatStore.getState().questions).toBeNull();
    expect(useChatStore.getState().queuedSteeringMessages).toEqual([]);
  });
});
