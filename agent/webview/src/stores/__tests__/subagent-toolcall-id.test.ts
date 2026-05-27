/**
 * #8: when a sub-agent runs two calls to the SAME tool in parallel,
 * updateSubAgentToolCall must finalize the call matching the toolCallId — not
 * just the first RUNNING tool with that name (which would attach the result to
 * the wrong row).
 */
import { describe, it, expect, beforeEach } from 'vitest';
import { useChatStore, selectActiveSubAgents } from '../chatStore';

const J = (o: unknown) => JSON.stringify(o);

beforeEach(() => {
  useChatStore.setState({ messages: [], toolOutputStreams: {} } as never);
});

describe('updateSubAgentToolCall id matching', () => {
  it('finalizes the call with the matching toolCallId, leaving the other RUNNING', () => {
    const s = () => useChatStore.getState();
    s().spawnSubAgent(J({ agentId: 'a1', label: 'explorer', model: 'm' }));
    s().addSubAgentToolCall(J({ agentId: 'a1', toolCallId: 'A', toolName: 'read_file', toolArgs: '{}' }));
    s().addSubAgentToolCall(J({ agentId: 'a1', toolCallId: 'B', toolName: 'read_file', toolArgs: '{}' }));

    // Finalize B specifically.
    s().updateSubAgentToolCall(J({ agentId: 'a1', toolCallId: 'B', toolName: 'read_file', isError: false, toolResult: 'B done', toolOutput: 'B out' }));

    const chain = selectActiveSubAgents(s()).get('a1')!.activeToolChain;
    const ids = chain.map(tc => tc.id);
    // A is still RUNNING in the active chain; B was finalized out of it.
    expect(ids).toContain('A');
    expect(ids).not.toContain('B');
  });
});
