/**
 * Resume parity for THINKING. Live, AgentController's ThinkingTagSplitter pulls
 * <thinking>…</thinking> out of the assistant text into a collapsible reasoning
 * block. But the persisted assistant TEXT (ui_messages.json) keeps the raw tags
 * (the parser only strips tool-call XML), and the splitter never runs on resume.
 * So a resumed assistant message must still surface its reasoning as a reasoning
 * region — not leak the raw "<thinking>" tag or dump the reasoning inline as prose.
 */
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, screen, act } from '@testing-library/react';
import type { UiMessage } from '@/bridge/types';
import { useChatStore } from '@/stores/chatStore';

vi.mock('react-virtuoso', () => ({
  Virtuoso: ({ totalCount, itemContent, components }: any) => {
    const Footer = components?.Footer;
    return (
      <div role="log">
        {Array.from({ length: totalCount }, (_, i) => (
          <div key={i} data-item-index={i}>{itemContent(i)}</div>
        ))}
        {Footer && <Footer />}
      </div>
    );
  },
}));
import { ChatView } from '@/components/chat/ChatView';

beforeEach(() => {
  act(() => useChatStore.getState().clearChat?.());
});

describe('resume parity: thinking survives as a reasoning block', () => {
  it('extracts <thinking> from a persisted assistant message into a reasoning region', () => {
    const persisted: UiMessage[] = [
      { ts: 1, type: 'SAY', say: 'USER_MESSAGE', text: 'What is 6x7?' } as UiMessage,
      {
        ts: 2, type: 'SAY', say: 'TEXT',
        text: '<thinking>Multiply six by seven.</thinking>The answer is 42.',
      } as UiMessage,
    ];
    act(() => useChatStore.getState().hydrateFromUiMessages(persisted));
    render(<ChatView />);

    // The prose is shown.
    expect(screen.getByText(/The answer is 42/)).toBeInTheDocument();
    // The reasoning content is recovered (shown in the reasoning region).
    const reasoning = screen.getByRole('region', { name: /reasoning/i });
    expect(reasoning).toHaveTextContent(/Multiply six by seven/);
    // The raw tag must never leak into the visible chat.
    expect(screen.getByRole('log').textContent ?? '').not.toContain('<thinking>');
  });

  it('leaves a thinking-free assistant message unchanged', () => {
    const persisted: UiMessage[] = [
      { ts: 1, type: 'SAY', say: 'TEXT', text: 'Just a plain answer.' } as UiMessage,
    ];
    act(() => useChatStore.getState().hydrateFromUiMessages(persisted));
    const msgs = useChatStore.getState().messages;
    expect(msgs).toHaveLength(1);
    expect(msgs[0]!.text).toBe('Just a plain answer.');
    expect(msgs[0]!.say).toBe('TEXT');
  });
});
