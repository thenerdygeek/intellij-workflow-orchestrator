import { describe, it, expect, beforeEach } from 'vitest';
import { useChatStore } from '../chatStore';

describe('chatStore handoff projection', () => {
  beforeEach(() => { useChatStore.getState().clearChat(); });

  it('setHandoff stores the summary; clearHandoff removes it', () => {
    useChatStore.getState().setHandoff({ summary: '## Current Work\nX' });
    expect(useChatStore.getState().handoff?.summary).toContain('Current Work');
    useChatStore.getState().clearHandoff();
    expect(useChatStore.getState().handoff).toBeNull();
  });

  it('startSession clears any open handoff', () => {
    useChatStore.getState().setHandoff({ summary: 'pending' });
    useChatStore.getState().startSession('new task');
    expect(useChatStore.getState().handoff).toBeNull();
  });
});
