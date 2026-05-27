/**
 * Bug #15 — interrupted streaming content dropped on resume.
 *
 * `hydrateFromUiMessages` filtered out every `m.partial` message, so a turn that
 * was interrupted mid-stream (persisted partial:true, e.g. an open ```fence) was
 * dropped entirely on reload instead of rendered best-effort. A partial message
 * that carries content should be FINALIZED (rendered, partial:false), not lost.
 * Empty partial placeholders and the internal API_REQ_* tracking rows are still
 * filtered.
 */
import { describe, it, expect, beforeEach } from 'vitest';
import { useChatStore } from '../stores/chatStore';
import type { UiMessage } from '../bridge/types';

beforeEach(() => {
  useChatStore.getState().clearChat?.();
});

const openFence = 'Here is the fix:\n```ts\nconst x = 1;';

describe('Bug #15 — hydrateFromUiMessages preserves interrupted content', () => {
  it('finalizes a partial message that has content instead of dropping it', () => {
    const msgs: UiMessage[] = [
      { ts: 1, type: 'SAY', say: 'TEXT', text: openFence, partial: true } as UiMessage,
      { ts: 2, type: 'SAY', say: 'TEXT', text: 'next', partial: false } as UiMessage,
    ];
    useChatStore.getState().hydrateFromUiMessages(msgs);

    const messages = useChatStore.getState().messages;
    const recovered = messages.find(m => m.text === openFence);
    expect(recovered, 'interrupted content should survive reload').toBeDefined();
    // It must be finalized so it renders as a completed message, not a stuck stream.
    expect(recovered!.partial).toBe(false);
  });

  it('still drops an empty partial placeholder (nothing to render)', () => {
    const msgs: UiMessage[] = [
      { ts: 1, type: 'SAY', say: 'TEXT', text: '', partial: true } as UiMessage,
      { ts: 2, type: 'SAY', say: 'TEXT', text: 'real', partial: false } as UiMessage,
    ];
    useChatStore.getState().hydrateFromUiMessages(msgs);

    const messages = useChatStore.getState().messages;
    expect(messages).toHaveLength(1);
    expect(messages[0]!.text).toBe('real');
  });

  it('still filters internal API_REQ_* tracking rows', () => {
    const msgs: UiMessage[] = [
      { ts: 1, type: 'SAY', say: 'API_REQ_STARTED', text: '', partial: false } as UiMessage,
      { ts: 2, type: 'SAY', say: 'API_REQ_FINISHED', text: '', partial: false } as UiMessage,
      { ts: 3, type: 'SAY', say: 'TEXT', text: 'visible', partial: false } as UiMessage,
    ];
    useChatStore.getState().hydrateFromUiMessages(msgs);

    const messages = useChatStore.getState().messages;
    expect(messages.map(m => m.text)).toEqual(['visible']);
  });
});
