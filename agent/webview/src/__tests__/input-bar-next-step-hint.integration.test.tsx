/**
 * Integration test for the next-step-hint promotion flow.
 *
 * The unit tests cover the isolated layers:
 *   - `rich-input-next-step-hint.test.tsx`  → ghost-text rendering + Right Arrow
 *     gating in `<RichInput>`
 *   - `chat-store-next-step-hint.test.ts`   → store lifecycle (set on completion,
 *     cleared on user message / clearChat / clearNextStepHint)
 *
 * What this file pins down is the *integration*: when the agent calls
 * `attempt_completion` with `next_steps`, then the user presses Right Arrow
 * to accept the hint, then presses Enter, the message MUST flow through the
 * same `kotlinBridge.sendMessage(text)` path a hand-typed message uses — so
 * the LLM iteration on the Kotlin side (AgentController.executeTask) sees an
 * ordinary user prompt with no special "this came from a hint" framing.
 *
 * Why this matters: the user explicitly asked "verify pressing Enter follows
 * how the normal LLM iteration happens, that is the message is sent as user
 * message". The unit tests can't see that — they don't render `<InputBar>`,
 * which is the component that wires `setText(hint)` + `getText()` + `sendMessage`
 * together.
 *
 * Mocking strategy:
 *   - `@/bridge/jcef-bridge` is hoisted-mocked so `chatStore.sendMessage`'s
 *     dynamic `import('../bridge/jcef-bridge')` resolves to our spies.
 *   - `kotlinBridge.sendMessage` is the assertion target.
 *   - `kotlinBridge.sendMessageWithMentions` should NOT be called for a plain
 *     hint accept (no chips, no attachments).
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, fireEvent, act } from '@testing-library/react';
import { useChatStore } from '@/stores/chatStore';
import type { CompletionData } from '@/bridge/types';

// ── Mock the JCEF bridge module so we can spy on the wire format ──
//
// vi.mock is hoisted before module evaluation, so chatStore.sendMessage's
// dynamic `import('../bridge/jcef-bridge')` resolves to this mock.
vi.mock('@/bridge/jcef-bridge', () => ({
  kotlinBridge: {
    sendMessage: vi.fn(),
    sendMessageWithMentions: vi.fn(),
    cancelTask: vi.fn(),
    newChat: vi.fn(),
    requestUndo: vi.fn(),
    requestViewTrace: vi.fn(),
    openSettings: vi.fn(),
  },
  isJcefEnvironment: () => false,
  preloadDiff2Html: () => Promise.resolve(),
  initBridge: () => {},
}));

// chatStore.sendMessage uses `import('../bridge/jcef-bridge')` — that relative
// path from `src/stores/chatStore.ts` resolves to the same module-id as
// `@/bridge/jcef-bridge` once vite/vitest resolves the alias, so the single
// vi.mock above covers both import sites. Pin that with an alias-mock too in
// case future refactors change the import style.
vi.mock('../bridge/jcef-bridge', () => ({
  kotlinBridge: {
    sendMessage: vi.fn(),
    sendMessageWithMentions: vi.fn(),
    cancelTask: vi.fn(),
    newChat: vi.fn(),
    requestUndo: vi.fn(),
    requestViewTrace: vi.fn(),
    openSettings: vi.fn(),
  },
  isJcefEnvironment: () => false,
  preloadDiff2Html: () => Promise.resolve(),
  initBridge: () => {},
}));

// Avoid a real Virtuoso scroll model — InputBar is the SUT, not MessageList,
// but the global setup might import it transitively. Cheap shim.
vi.mock('react-virtuoso', () => ({
  Virtuoso: ({ totalCount, itemContent }: { totalCount: number; itemContent: (i: number) => React.ReactNode }) => (
    <div role="log">
      {Array.from({ length: totalCount }, (_, i) => (
        <div key={i}>{itemContent(i)}</div>
      ))}
    </div>
  ),
}));

import { InputBar } from '@/components/input/InputBar';
import { kotlinBridge } from '@/bridge/jcef-bridge';

function getEditor(container: HTMLElement): HTMLDivElement {
  const el = container.querySelector('.rich-input') as HTMLDivElement | null;
  if (!el) throw new Error('rich-input editor not found in <InputBar>');
  return el;
}

/** Drain pending microtasks so chatStore.sendMessage's dynamic import resolves. */
async function flushAsync() {
  // Two awaits: one for the dynamic import resolution, one for the .then()
  // callback that invokes the bridge function.
  await act(async () => {
    await Promise.resolve();
    await Promise.resolve();
  });
}

describe('InputBar — next-step-hint promotion (integration)', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    // Fully reset the store so prior tests don't leak hint state.
    useChatStore.getState().clearChat();
    useChatStore.setState({ nextStepHint: null });
  });

  afterEach(() => {
    useChatStore.getState().clearChat();
    useChatStore.setState({ nextStepHint: null });
  });

  it('Right Arrow accept → Enter sends the hint as a user message via kotlinBridge.sendMessage', async () => {
    const completion: CompletionData = {
      kind: 'done',
      result: 'Refactor complete.',
      nextStep: 'run the failing tests',
    };
    // Seed the hint exactly as a real `attempt_completion` would.
    act(() => {
      useChatStore.getState().addCompletionCard(completion);
    });
    expect(useChatStore.getState().nextStepHint).toBe('run the failing tests');

    const { container } = render(<InputBar />);
    const editor = getEditor(container);

    // The editor must be empty so RichInput's `isEmpty` guard fires.
    expect(editor.textContent ?? '').toBe('');

    // The displayed hint includes a ▶ glyph for affordance — verify it on the
    // editor's data-placeholder attribute (CSS pseudo-element renders this).
    expect(editor.getAttribute('data-hint-active')).toBe('true');
    expect(editor.getAttribute('data-placeholder')).toBe('run the failing tests ▶');

    // Step 1 — Right Arrow promotes the hint into the editor.
    fireEvent.keyDown(editor, { key: 'ArrowRight' });

    // The promoted text must be the *raw* hint (no ▶ glyph). InputBar.tsx:494
    // calls `setText(nextStepHint)`, not `setText(hint)`.
    expect(editor.textContent).toBe('run the failing tests');

    // The store-side hint is cleared so it can't replay or echo back.
    expect(useChatStore.getState().nextStepHint).toBeNull();

    // Bridge has not been touched yet — accept ≠ send.
    expect(kotlinBridge.sendMessage).not.toHaveBeenCalled();
    expect(kotlinBridge.sendMessageWithMentions).not.toHaveBeenCalled();

    // Step 2 — Enter triggers the same `handleSend` path as a hand-typed
    // message. RichInput.onSubmit → InputBar.handleSend → ri.getText() →
    // useChatStore.sendMessage → dynamic import → kotlinBridge.sendMessage.
    fireEvent.keyDown(editor, { key: 'Enter' });

    await flushAsync();

    // The wire payload MUST equal the raw hint, exactly. No ▶, no extra
    // metadata, no mentions JSON envelope — this is what makes Kotlin's
    // AgentController.executeTask treat it as an ordinary user prompt.
    expect(kotlinBridge.sendMessage).toHaveBeenCalledTimes(1);
    expect(kotlinBridge.sendMessage).toHaveBeenCalledWith('run the failing tests');

    // The mentions path must NOT fire — that path carries different framing
    // and would route through `_sendMessageWithMentions` on the Kotlin side.
    expect(kotlinBridge.sendMessageWithMentions).not.toHaveBeenCalled();
  });

  it('Right Arrow with no hint is a no-op — does not call setText or sendMessage', async () => {
    // Sanity: store is clean.
    expect(useChatStore.getState().nextStepHint).toBeNull();

    const { container } = render(<InputBar />);
    const editor = getEditor(container);
    expect(editor.getAttribute('data-hint-active')).toBe('false');

    fireEvent.keyDown(editor, { key: 'ArrowRight' });

    expect(editor.textContent ?? '').toBe('');
    expect(kotlinBridge.sendMessage).not.toHaveBeenCalled();

    fireEvent.keyDown(editor, { key: 'Enter' });
    await flushAsync();

    // Empty input + Enter is a guarded no-op in handleSend.
    expect(kotlinBridge.sendMessage).not.toHaveBeenCalled();
  });

  it('Right Arrow with text already in input does NOT promote the hint', async () => {
    act(() => {
      useChatStore.getState().addCompletionCard({
        kind: 'done',
        result: 'A',
        nextStep: 'commit and push',
      });
    });

    const { container } = render(<InputBar />);
    const editor = getEditor(container);

    // Simulate the user having typed something (RichInput.isEmpty is computed
    // off `el.textContent.length` and chip count, both of which are inspected
    // synchronously inside handleKeyDown — we don't need to fire input events
    // for this guard to engage).
    editor.textContent = 'I have my own question';

    fireEvent.keyDown(editor, { key: 'ArrowRight' });

    // Hint did not replace the user's text.
    expect(editor.textContent).toBe('I have my own question');
    // Hint stays in the store — it wasn't accepted.
    expect(useChatStore.getState().nextStepHint).toBe('commit and push');
    expect(kotlinBridge.sendMessage).not.toHaveBeenCalled();
  });

  it('Promoted hint with surrounding whitespace is trimmed before send (matches normal-typing behavior)', async () => {
    // The store normalizes blank-only hints to null (chat-store unit test
    // covers that), but a hint with internal whitespace + trailing space is
    // legal. handleSend calls text.trim() exactly like a typed message would.
    act(() => {
      useChatStore.getState().addCompletionCard({
        kind: 'done',
        result: 'A',
        // The store's addCompletionCard already does `.trim()` on entry, so
        // we get 'review the diff' as-is. Re-confirm trimming on send anyway.
        nextStep: '   review the diff   ',
      });
    });
    expect(useChatStore.getState().nextStepHint).toBe('review the diff');

    const { container } = render(<InputBar />);
    const editor = getEditor(container);

    fireEvent.keyDown(editor, { key: 'ArrowRight' });
    expect(editor.textContent).toBe('review the diff');

    fireEvent.keyDown(editor, { key: 'Enter' });
    await flushAsync();

    expect(kotlinBridge.sendMessage).toHaveBeenCalledTimes(1);
    expect(kotlinBridge.sendMessage).toHaveBeenCalledWith('review the diff');
  });
});
