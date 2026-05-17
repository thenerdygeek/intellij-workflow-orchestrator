import { describe, it, expect, beforeEach } from 'vitest';
import { useChatStore, selectActiveSubAgents } from '../chatStore';

describe('selectActiveSubAgents (derived)', () => {
  beforeEach(() => {
    // Reset store to a clean state for each test
    useChatStore.setState({ messages: [] } as any);
  });

  it('returns an empty Map when there are no SUBAGENT messages', () => {
    const map = selectActiveSubAgents(useChatStore.getState());
    expect(map.size).toBe(0);
  });

  it('returns a Map of agentId → SubAgentState built from messages[]', () => {
    useChatStore.getState().spawnSubAgent(JSON.stringify({ agentId: 'a-1', label: 'foo', model: 'm-1' }));
    const map = selectActiveSubAgents(useChatStore.getState());
    expect(map.get('a-1')?.label).toBe('foo');
  });

  it('reflects iteration updates from messages[]', () => {
    useChatStore.getState().spawnSubAgent(JSON.stringify({ agentId: 'a-2', label: 'bar', model: 'm-1' }));
    useChatStore.getState().updateSubAgentIteration(JSON.stringify({ agentId: 'a-2', iteration: 5, tokensUsed: 100 }));
    const map = selectActiveSubAgents(useChatStore.getState());
    expect(map.get('a-2')?.iteration).toBe(5);
  });
});
