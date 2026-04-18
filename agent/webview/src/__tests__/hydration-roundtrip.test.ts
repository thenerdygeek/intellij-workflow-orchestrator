/**
 * Round-trip test: messages created via live store actions must produce
 * the same render output as messages loaded via hydrateFromUiMessages().
 * This is the core contract that eliminates the conversion-layer bugs.
 */
import { describe, it, expect, beforeEach } from 'vitest';
import { chatState, resetChatStore } from './chat-store-test-utils';
import type { UiMessage } from '@/bridge/types';

describe('hydration round-trip', () => {
  beforeEach(resetChatStore);

  it('live agent text messages survive hydration unchanged', () => {
    chatState().addAgentText('Hello from the agent');
    const liveMessages = [...chatState().messages];

    resetChatStore();
    chatState().hydrateFromUiMessages(liveMessages);

    const hydrated = chatState().messages;
    expect(hydrated).toHaveLength(1);
    expect(hydrated[0]!.say).toBe('TEXT');
    expect(hydrated[0]!.text).toBe('Hello from the agent');
  });

  it('live user messages survive hydration unchanged', () => {
    chatState().addUserMessage('User question');
    const liveMessages = [...chatState().messages];

    resetChatStore();
    chatState().hydrateFromUiMessages(liveMessages);

    const hydrated = chatState().messages;
    expect(hydrated).toHaveLength(1);
    expect(hydrated[0]!.say).toBe('USER_MESSAGE');
    expect(hydrated[0]!.text).toBe('User question');
  });

  it('finalized tool chain messages survive hydration', () => {
    chatState().addToolCall('tc-1', 'read_file', '{"path":"/foo"}', 'RUNNING');
    chatState().updateToolCall('read_file', 'COMPLETED', 'file contents', 100, undefined, undefined, 'tc-1');
    chatState().finalizeToolChain();
    const liveMessages = [...chatState().messages];

    resetChatStore();
    chatState().hydrateFromUiMessages(liveMessages);

    const hydrated = chatState().messages;
    expect(hydrated.length).toBeGreaterThan(0);
    const toolMsg = hydrated.find(m => m.say === 'TOOL');
    expect(toolMsg).toBeDefined();
    expect(toolMsg!.toolCallData!.toolName).toBe('read_file');
    expect(toolMsg!.toolCallData!.result).toBe('file contents');
  });

  it('thinking messages survive hydration', () => {
    chatState().addThinking('Let me analyze this...');
    const liveMessages = [...chatState().messages];

    resetChatStore();
    chatState().hydrateFromUiMessages(liveMessages);

    const hydrated = chatState().messages;
    expect(hydrated[0]!.say).toBe('REASONING');
    expect(hydrated[0]!.text).toBe('Let me analyze this...');
  });

  it('partial (streaming) messages are filtered during hydration', () => {
    chatState().appendToken('In progress...');
    const liveMessages = [...chatState().messages];
    expect(liveMessages[0]!.partial).toBe(true);

    resetChatStore();
    chatState().hydrateFromUiMessages(liveMessages);

    expect(chatState().messages).toHaveLength(0);
  });

  it('plan state is restored from PLAN_UPDATE messages', () => {
    const planMsg: UiMessage = {
      ts: Date.now(),
      type: 'SAY',
      say: 'PLAN_UPDATE',
      planData: {
        steps: [{ title: 'Step 1', status: 'completed' }, { title: 'Step 2', status: 'pending' }],
        status: 'EXECUTING',
        comments: {},
      },
    };

    chatState().hydrateFromUiMessages([planMsg]);

    const plan = chatState().plan;
    expect(plan).not.toBeNull();
    // NOTE: Plan.steps removed in Phase 5 (task system port) — steps now come from TaskStore
    expect(plan!.approved).toBe(true);
  });

  it('status messages survive hydration', () => {
    chatState().addStatus('Context compressed', 'INFO');
    const liveMessages = [...chatState().messages];

    resetChatStore();
    chatState().hydrateFromUiMessages(liveMessages);

    const hydrated = chatState().messages;
    expect(hydrated).toHaveLength(1);
    expect(hydrated[0]!.text).toBe('Context compressed');
  });

  // ── Legacy TOOL message upgrade tests ──
  // Old sessions may have communication tools stored as generic TOOL messages.
  // hydrateFromUiMessages() must upgrade them to proper semantic types.

  it('upgrades legacy attempt_completion TOOL to COMPLETION_RESULT', () => {
    const legacyMsg: UiMessage = {
      ts: Date.now(),
      type: 'SAY',
      say: 'TOOL',
      toolCallData: {
        toolCallId: 'tc-1',
        toolName: 'attempt_completion',
        args: '{"result": "Fixed the bug", "command": "./gradlew test"}',
        status: 'COMPLETED',
        result: 'Task completed',
        durationMs: 50,
      },
    };

    chatState().hydrateFromUiMessages([legacyMsg]);
    const hydrated = chatState().messages;
    expect(hydrated).toHaveLength(1);
    expect(hydrated[0]!.ask).toBe('COMPLETION_RESULT');
    expect(hydrated[0]!.text).toBe('Fixed the bug');
    expect(hydrated[0]!.toolCallData).toBeUndefined();
  });

  it('upgrades legacy plan_mode_respond TOOL to TEXT', () => {
    const legacyMsg: UiMessage = {
      ts: Date.now(),
      type: 'SAY',
      say: 'TOOL',
      toolCallData: {
        toolCallId: 'tc-2',
        toolName: 'plan_mode_respond',
        args: '{"response": "Here is my plan:\\n1. Step one\\n2. Step two"}',
        status: 'COMPLETED',
        result: 'Plan presented',
        durationMs: 20,
      },
    };

    chatState().hydrateFromUiMessages([legacyMsg]);
    const hydrated = chatState().messages;
    expect(hydrated).toHaveLength(1);
    expect(hydrated[0]!.say).toBe('TEXT');
    expect(hydrated[0]!.text).toContain('Here is my plan');
    expect(hydrated[0]!.toolCallData).toBeUndefined();
  });

  it('upgrades legacy act_mode_respond TOOL to TEXT', () => {
    const legacyMsg: UiMessage = {
      ts: Date.now(),
      type: 'SAY',
      say: 'TOOL',
      toolCallData: {
        toolCallId: 'tc-3',
        toolName: 'act_mode_respond',
        args: '{"response": "Completed step 1. Moving on."}',
        status: 'COMPLETED',
        result: 'Progress delivered',
        durationMs: 15,
      },
    };

    chatState().hydrateFromUiMessages([legacyMsg]);
    const hydrated = chatState().messages;
    expect(hydrated).toHaveLength(1);
    expect(hydrated[0]!.say).toBe('TEXT');
    expect(hydrated[0]!.text).toBe('Completed step 1. Moving on.');
    expect(hydrated[0]!.toolCallData).toBeUndefined();
  });

  it('upgrades legacy ask_followup_question TOOL to FOLLOWUP', () => {
    const legacyMsg: UiMessage = {
      ts: Date.now(),
      type: 'SAY',
      say: 'TOOL',
      toolCallData: {
        toolCallId: 'tc-4',
        toolName: 'ask_followup_question',
        args: '{"question": "Should I update the tests?"}',
        status: 'COMPLETED',
        result: 'Question asked',
        durationMs: 10,
      },
    };

    chatState().hydrateFromUiMessages([legacyMsg]);
    const hydrated = chatState().messages;
    expect(hydrated).toHaveLength(1);
    expect(hydrated[0]!.ask).toBe('FOLLOWUP');
    expect(hydrated[0]!.text).toBe('Should I update the tests?');
    expect(hydrated[0]!.toolCallData).toBeUndefined();
  });

  it('upgrades legacy ask_questions TOOL to TEXT', () => {
    const legacyMsg: UiMessage = {
      ts: Date.now(),
      type: 'SAY',
      say: 'TOOL',
      toolCallData: {
        toolCallId: 'tc-5',
        toolName: 'ask_questions',
        args: '{"questions": [{"question": "Which framework?", "options": ["A","B"]}]}',
        status: 'COMPLETED',
        result: 'Questions answered',
        durationMs: 25,
      },
    };

    chatState().hydrateFromUiMessages([legacyMsg]);
    const hydrated = chatState().messages;
    expect(hydrated).toHaveLength(1);
    expect(hydrated[0]!.say).toBe('TEXT');
    expect(hydrated[0]!.text).toBe('Which framework?');
    expect(hydrated[0]!.toolCallData).toBeUndefined();
  });

  it('does NOT upgrade regular TOOL messages', () => {
    const regularTool: UiMessage = {
      ts: Date.now(),
      type: 'SAY',
      say: 'TOOL',
      toolCallData: {
        toolCallId: 'tc-6',
        toolName: 'read_file',
        args: '{"path": "/src/main.kt"}',
        status: 'COMPLETED',
        result: 'Read 100 lines',
        durationMs: 30,
      },
    };

    chatState().hydrateFromUiMessages([regularTool]);
    const hydrated = chatState().messages;
    expect(hydrated).toHaveLength(1);
    expect(hydrated[0]!.say).toBe('TOOL');
    expect(hydrated[0]!.toolCallData).toBeDefined();
    expect(hydrated[0]!.toolCallData!.toolName).toBe('read_file');
  });

  it('handles legacy TOOL with unparseable args gracefully', () => {
    const badArgs: UiMessage = {
      ts: Date.now(),
      type: 'SAY',
      say: 'TOOL',
      toolCallData: {
        toolCallId: 'tc-7',
        toolName: 'attempt_completion',
        args: 'not valid json',
        status: 'COMPLETED',
        result: 'Fallback result text',
        durationMs: 10,
      },
    };

    chatState().hydrateFromUiMessages([badArgs]);
    const hydrated = chatState().messages;
    expect(hydrated).toHaveLength(1);
    expect(hydrated[0]!.ask).toBe('COMPLETION_RESULT');
    // Falls back to toolCallData.result when args can't be parsed
    expect(hydrated[0]!.text).toBe('Fallback result text');
  });
});
