/**
 * Resume parity — a resumed (hydrated-from-disk) conversation must render the
 * same user-visible content the live chat showed: user messages, agent prose,
 * reasoning, tool calls, an interrupted (partial) message recovered best-effort,
 * and the completion card — in order — while internal tracking rows (API_REQ_*)
 * are filtered and transient UI (approval/questions/steering) starts clean.
 *
 * This renders the real <ChatView/> off the store after hydrateFromUiMessages,
 * the same path the _loadSessionState bridge drives on resume.
 */
import { describe, it, expect, beforeEach } from 'vitest';
import { render, screen, act } from '@testing-library/react';
import type { UiMessage } from '@/bridge/types';
import { useChatStore } from '@/stores/chatStore';

// Virtuoso needs a real scroll model; render rows + Footer inline (same shim as
// message-list.test.tsx).
import { vi } from 'vitest';
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

// A representative persisted ui_messages.json from a live session.
const persisted: UiMessage[] = [
  { ts: 1, type: 'SAY', say: 'USER_MESSAGE', text: 'Fix the login bug' } as UiMessage,
  { ts: 2, type: 'SAY', say: 'API_REQ_STARTED', text: '' } as UiMessage, // internal — must be filtered
  { ts: 3, type: 'SAY', say: 'REASONING', text: 'Let me think about the auth flow first' } as UiMessage,
  { ts: 4, type: 'SAY', say: 'TEXT', text: 'I will read the auth file.' } as UiMessage,
  {
    ts: 5, type: 'SAY', say: 'TOOL',
    toolCallData: { toolCallId: 't1', toolName: 'read_file', args: '{}', status: 'COMPLETED', result: 'ok' },
  } as UiMessage,
  // Interrupted mid-stream (persisted partial:true with content) — must be recovered.
  { ts: 6, type: 'SAY', say: 'TEXT', text: 'Here is the patch I applied', partial: true } as UiMessage,
  {
    ts: 7, type: 'ASK', ask: 'COMPLETION_RESULT', text: 'Done — login bug fixed',
    completionData: { kind: 'done', result: 'Done — login bug fixed' },
  } as UiMessage,
];

beforeEach(() => {
  act(() => useChatStore.getState().clearChat?.());
});

describe('resume parity: hydrated chat renders like the live chat', () => {
  it('renders every user-visible message, in order, and filters internal rows', () => {
    act(() => useChatStore.getState().hydrateFromUiMessages(persisted));
    render(<ChatView />);

    // Each live-visible piece is present.
    expect(screen.getByText(/Fix the login bug/)).toBeInTheDocument();          // user
    expect(screen.getByText(/think about the auth flow/)).toBeInTheDocument();   // reasoning
    expect(screen.getByText(/I will read the auth file/)).toBeInTheDocument();   // agent prose
    expect(screen.getByText(/read_file/)).toBeInTheDocument();                   // tool chain
    expect(screen.getByText(/Here is the patch I applied/)).toBeInTheDocument(); // recovered partial
    expect(screen.getByText(/Done — login bug fixed/)).toBeInTheDocument();      // completion

    // Internal tracking rows are not rendered.
    expect(screen.queryByText(/API_REQ/)).toBeNull();

    // Visible order matches the live order (user → reasoning → prose → tool → patch → done).
    const log = screen.getByRole('log').textContent ?? '';
    const order = ['Fix the login bug', 'auth flow', 'read the auth file', 'read_file', 'patch I applied', 'login bug fixed'];
    const positions = order.map(s => log.indexOf(s));
    expect(positions.every(p => p >= 0)).toBe(true);
    expect([...positions].sort((a, b) => a - b)).toEqual(positions);
  });

  it('leaves the resumed session in a clean state (store mirrors the rendered chat)', () => {
    act(() => useChatStore.getState().hydrateFromUiMessages(persisted));
    const s = useChatStore.getState();

    // The interrupted message survived and was finalized.
    const recovered = s.messages.find(m => m.text === 'Here is the patch I applied');
    expect(recovered?.partial).toBe(false);
    // Internal marker dropped.
    expect(s.messages.some(m => m.say === 'API_REQ_STARTED')).toBe(false);
    // Transient UI from the previous live session does not bleed in.
    expect(s.pendingApproval).toBeNull();
    expect(s.questions).toBeNull();
    expect(s.queuedSteeringMessages).toEqual([]);
    expect(s.streamingText).toBeNull();
  });
});
