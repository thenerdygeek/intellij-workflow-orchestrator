import { render, screen, cleanup } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { SubAgentView } from '../SubAgentView';
import type { SubAgentState } from '@/bridge/types';

// ThinkingView calls scanAndLinkify on the DOM after streaming ends — stub it out.
vi.mock('@/util/file-link-scanner', () => ({
  scanAndLinkify: () => Promise.resolve(),
  PATH_REGEX: /(?:never-match-please)/g,
}));

// chatStore: SubAgentView calls useChatStore to get killSubAgent — provide a no-op.
vi.mock('@/stores/chatStore', () => ({
  useChatStore: (selector: (s: any) => any) => selector({ killSubAgent: vi.fn() }),
}));

const baseState: SubAgentState = {
  agentId: 'a-1',
  label: 'Review files (code-reviewer)',
  status: 'RUNNING',
  startedAt: Date.now(),
  iteration: 1,
  tokensUsed: 0,
  messages: [],
  activeToolChain: [],
  streamingText: null,
  streamingThinkingText: null,
};

describe('SubAgentView thinking parity', () => {
  beforeEach(() => {
    cleanup();
  });

  afterEach(() => {
    cleanup();
  });

  it('renders streaming thinking through <ThinkingView> (collapsible), not as plain italic text', () => {
    const state: SubAgentState = {
      ...baseState,
      streamingThinkingText: 'planning the next move',
    };
    render(<SubAgentView subAgent={state} />);
    // ThinkingView's streaming label is "Thinking..." (per ThinkingView.tsx:32).
    expect(screen.getByText(/Thinking/i)).toBeTruthy();
  });

  it('renders finalised REASONING messages through <ThinkingView> (auto-collapsed)', () => {
    // Keep status RUNNING so the sub-agent card body is open — the assertion is about
    // ThinkingView rendering the content, not about the card collapse behaviour.
    const state: SubAgentState = {
      ...baseState,
      status: 'RUNNING',
      streamingThinkingText: null,
      messages: [
        { ts: 1, say: 'REASONING', text: 'I decided to call read_file' } as any,
      ],
    };
    render(<SubAgentView subAgent={state} />);
    // The thinking text content must be present somewhere in the DOM
    // (collapsed view still has the text in the markup).
    expect(screen.queryAllByText(/I decided to call read_file/).length).toBeGreaterThan(0);
  });
});
