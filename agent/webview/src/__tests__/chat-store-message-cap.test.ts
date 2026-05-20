import { describe, it, expect, beforeEach } from 'vitest';
import { useChatStore, MESSAGES_HARD_CAP } from '../stores/chatStore';
import type { UiMessage } from '../bridge/types';

describe('chatStore — messages[] hard cap', () => {
  beforeEach(() => {
    useChatStore.getState().clearChat();
  });

  it('keeps the most recent messages (within cap) and replaces evicted prefix with a single SPILL marker', () => {
    const store = useChatStore.getState();
    for (let i = 0; i < 1050; i++) {
      store.addUserMessage(`msg-${i}`);
    }
    const msgs = useChatStore.getState().messages;
    // Cap is "at most MESSAGES_HARD_CAP entries INCLUDING the SPILL marker" —
    // first cap on 1050 with no prior marker produces exactly MESSAGES_HARD_CAP.
    expect(msgs.length).toBe(MESSAGES_HARD_CAP);
    expect(msgs[0].say).toBe('SYSTEM');
    expect(msgs[0].text).toMatch(/older messages.*archived/i);
    expect(msgs[msgs.length - 1].text).toBe('msg-1049');
  });

  it('does not insert a spill marker until the cap is exceeded', () => {
    const store = useChatStore.getState();
    for (let i = 0; i < MESSAGES_HARD_CAP; i++) {
      store.addUserMessage(`msg-${i}`);
    }
    const msgs = useChatStore.getState().messages;
    expect(msgs.length).toBe(MESSAGES_HARD_CAP);
    expect(msgs[0].text).toBe('msg-0');
  });

  it('caps messages[] on hydration when the loaded session exceeds the limit', () => {
    // Session-resume path: hydrateFromUiMessages is the top-level entry point
    // that replaces messages[] wholesale with the persisted ui_messages.json
    // payload. Without capping here, a multi-hour session with >cap stored
    // messages reintroduces the long-conversation OOM trajectory on every
    // load. The cap is UI-only — the agent's ContextManager still sees the
    // full persisted api_conversation_history.json.
    const store = useChatStore.getState();
    const huge: UiMessage[] = [];
    for (let i = 0; i < 1500; i++) {
      huge.push({
        ts: i + 1,
        type: 'SAY',
        say: 'USER_MESSAGE',
        text: `prior-${i}`,
      });
    }
    store.hydrateFromUiMessages(huge);
    const msgs = useChatStore.getState().messages;
    expect(msgs.length).toBeLessThanOrEqual(MESSAGES_HARD_CAP);
    expect(msgs[0].say).toBe('SYSTEM');
    expect(msgs[msgs.length - 1].text).toBe('prior-1499');
  });

  it('produces a stable size on successive caps (no drift between first cap and re-cap)', () => {
    // The original helper had two paths: first-cap (no marker present, returns
    // marker + MESSAGES_HARD_CAP entries) and re-cap (marker present, returns
    // marker + MESSAGES_HARD_CAP-1 entries). The off-by-one would have manifested
    // as a visible size drift on the second overflow. The fixed helper keeps
    // the total at exactly MESSAGES_HARD_CAP including the marker.
    const store = useChatStore.getState();
    for (let i = 0; i < 1500; i++) {
      store.addUserMessage(`msg-${i}`);
    }
    const sizeAfterFirstCap = useChatStore.getState().messages.length;
    for (let i = 1500; i < 1800; i++) {
      store.addUserMessage(`msg-${i}`);
    }
    const sizeAfterSecondCap = useChatStore.getState().messages.length;
    expect(sizeAfterFirstCap).toBe(MESSAGES_HARD_CAP);
    expect(sizeAfterSecondCap).toBe(MESSAGES_HARD_CAP);
  });
});
