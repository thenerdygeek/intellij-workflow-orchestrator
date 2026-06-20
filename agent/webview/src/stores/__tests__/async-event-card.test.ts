import { describe, it, expect, beforeEach } from 'vitest';
import { useChatStore } from '../chatStore';

describe('chatStore.addAsyncEventCard', () => {
  beforeEach(() => {
    useChatStore.setState({ messages: [] } as never);
  });

  it('appends an ASYNC_EVENT UiMessage carrying the payload', () => {
    useChatStore.getState().addAsyncEventCard({
      id: 'bg-1-1', kind: 'BACKGROUND', sourceId: 'bg1', label: 'x',
      status: 'SUCCESS', summary: 's', details: 'd', timestamp: 1,
    });
    const msgs = useChatStore.getState().messages;
    const card = msgs.find(m => m.say === 'ASYNC_EVENT');
    expect(card?.asyncEventData?.sourceId).toBe('bg1');
  });
});
