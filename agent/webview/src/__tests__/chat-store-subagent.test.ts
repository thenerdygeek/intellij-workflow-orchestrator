/**
 * Tests for sub-agent store actions: spawn, iterate, complete, kill.
 * Verifies that UiMessage-based sub-agent lifecycle works correctly.
 */
import { describe, it, expect, beforeEach } from 'vitest';
import { chatState, resetChatStore } from './chat-store-test-utils';

describe('chatStore — sub-agent lifecycle', () => {
  beforeEach(resetChatStore);

  it('spawnSubAgent creates SUBAGENT_STARTED message', () => {
    chatState().spawnSubAgent(JSON.stringify({ agentId: 'agent-1', label: 'Research agent' }));

    const msgs = chatState().messages;
    expect(msgs).toHaveLength(1);
    expect(msgs[0]!.say).toBe('SUBAGENT_STARTED');
    expect(msgs[0]!.subagentData).toBeDefined();
    expect(msgs[0]!.subagentData!.agentId).toBe('agent-1');
    expect(msgs[0]!.subagentData!.status).toBe('RUNNING');
    expect(msgs[0]!.subagentData!.description).toBe('Research agent');
  });

  it('duplicate spawnSubAgent is a no-op', () => {
    chatState().spawnSubAgent(JSON.stringify({ agentId: 'agent-1', label: 'Research agent' }));
    chatState().spawnSubAgent(JSON.stringify({ agentId: 'agent-1', label: 'Research agent' }));

    const msgs = chatState().messages;
    expect(msgs).toHaveLength(1);
  });

  it('updateSubAgentIteration increments iteration count', () => {
    chatState().spawnSubAgent(JSON.stringify({ agentId: 'agent-1', label: 'Coder' }));
    chatState().updateSubAgentIteration(JSON.stringify({ agentId: 'agent-1', iteration: 3 }));

    const msg = chatState().messages[0]!;
    expect(msg.subagentData!.iterations).toBe(3);
  });

  it('completeSubAgent sets status and summary', () => {
    chatState().spawnSubAgent(JSON.stringify({ agentId: 'agent-1', label: 'Reviewer' }));
    chatState().completeSubAgent(JSON.stringify({
      agentId: 'agent-1',
      textContent: 'Found 3 issues',
      tokensUsed: 5000,
    }));

    const msg = chatState().messages[0]!;
    expect(msg.say).toBe('SUBAGENT_COMPLETED');
    expect(msg.subagentData!.status).toBe('COMPLETED');
    expect(msg.subagentData!.summary).toBe('Found 3 issues');
  });

  it('killSubAgent sets status to KILLED', () => {
    chatState().spawnSubAgent(JSON.stringify({ agentId: 'agent-1', label: 'Worker' }));
    chatState().killSubAgent('agent-1');

    const msg = chatState().messages[0]!;
    expect(msg.subagentData!.status).toBe('KILLED');
  });

  it('operations on non-existent agent are no-ops', () => {
    chatState().spawnSubAgent(JSON.stringify({ agentId: 'agent-1', label: 'Worker' }));
    chatState().completeSubAgent(JSON.stringify({ agentId: 'agent-999', summary: 'Done' }));

    // Original agent unchanged
    const msg = chatState().messages[0]!;
    expect(msg.subagentData!.status).toBe('RUNNING');
    // No extra messages added
    expect(chatState().messages).toHaveLength(1);
  });
});
