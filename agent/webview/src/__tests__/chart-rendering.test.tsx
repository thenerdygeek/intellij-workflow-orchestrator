import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { AgentMessage } from '../components/chat/AgentMessage';
import type { UiMessage } from '@/bridge/types';

// Mock ChartView since Chart.js requires canvas — we just verify it gets rendered
vi.mock('@/components/rich/ChartView', () => ({
  ChartView: ({ source }: { source: string }) => (
    <div data-testid="chart-view" data-source={source} />
  ),
}));

describe('AgentMessage: UiMessage rendering', () => {
  it('renders agent TEXT messages as markdown', () => {
    const message: UiMessage = {
      ts: Date.now(),
      type: 'SAY',
      say: 'TEXT',
      text: 'Hello from the agent.',
    };

    const { container } = render(<AgentMessage message={message} />);

    // Agent label should be present
    const agentLabel = Array.from(container.querySelectorAll('span'))
      .find(el => el.textContent === 'Agent');
    expect(agentLabel).toBeDefined();
    // The text is rendered through markdown
    expect(container.textContent).toContain('Hello from the agent.');
  });

  it('renders user messages with USER_MESSAGE say type', () => {
    const message: UiMessage = {
      ts: Date.now(),
      type: 'SAY',
      say: 'USER_MESSAGE',
      text: 'What is the bug?',
    };

    const { container } = render(<AgentMessage message={message} />);

    // User messages should not show "Agent" label
    expect(container.textContent).toContain('What is the bug?');
  });

  it('renders empty content gracefully when text is undefined', () => {
    const message: UiMessage = {
      ts: Date.now(),
      type: 'SAY',
      say: 'TEXT',
    };

    const { container } = render(<AgentMessage message={message} />);
    // Should render without crashing — the Agent label is still present
    const agentLabel = Array.from(container.querySelectorAll('span'))
      .find(el => el.textContent === 'Agent');
    expect(agentLabel).toBeDefined();
  });
});
