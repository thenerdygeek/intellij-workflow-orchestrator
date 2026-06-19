import { describe, it, expect, beforeEach } from 'vitest';
import { initBridge } from '@/bridge/jcef-bridge';
import { useChatStore } from '@/stores/chatStore';

beforeEach(() => {
  initBridge({
    getChatStore: () => useChatStore.getState(),
    getThemeStore: () => ({}),
    getSettingsStore: () => ({}),
  });
  useChatStore.setState({ messages: [] } as never);
});

describe('_pushAsyncEventCard bridge', () => {
  it('JSON.parses a string payload into an ASYNC_EVENT card', () => {
    const payload = JSON.stringify({
      id: 'bg-9-1', kind: 'BACKGROUND', sourceId: 'bg9', label: 'build',
      status: 'FAILURE', summary: 'exit 1 · 3s', details: 'boom', timestamp: 1,
    });
    (window as any)._pushAsyncEventCard(payload);
    const card = useChatStore.getState().messages.find(m => m.say === 'ASYNC_EVENT');
    expect(card?.asyncEventData?.status).toBe('FAILURE');
    expect(card?.asyncEventData?.sourceId).toBe('bg9');
  });

  it('bad JSON does not throw', () => {
    expect(() => (window as any)._pushAsyncEventCard('{not json')).not.toThrow();
  });
});
