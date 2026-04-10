import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import { AgentMessage } from '../components/chat/AgentMessage';

// Mock ChartView since Chart.js requires canvas — we just verify it gets rendered
vi.mock('@/components/rich/ChartView', () => ({
  ChartView: ({ source }: { source: string }) => (
    <div data-testid="chart-view" data-source={source} />
  ),
}));

describe('AgentMessage: chart system messages', () => {
  it('renders ChartView for system messages with type=chart', () => {
    const chartConfig = JSON.stringify({
      type: 'bar',
      data: { labels: ['A', 'B'], datasets: [{ data: [1, 2] }] },
    });
    const message = {
      id: 'chart-1',
      role: 'system' as const,
      content: JSON.stringify({ type: 'chart', config: chartConfig }),
      timestamp: Date.now(),
    };

    render(<AgentMessage message={message} />);

    const chartView = screen.getByTestId('chart-view');
    expect(chartView).toBeInTheDocument();
    expect(chartView.getAttribute('data-source')).toBe(chartConfig);
  });

  it('does not render ChartView for non-chart system messages', () => {
    const message = {
      id: 'status-1',
      role: 'system' as const,
      content: JSON.stringify({ type: 'status', message: 'Working...' }),
      timestamp: Date.now(),
    };

    const { container } = render(<AgentMessage message={message} />);

    expect(screen.queryByTestId('chart-view')).not.toBeInTheDocument();
    expect(container.textContent).toContain('Working...');
  });

  it('still returns null for unknown system message types', () => {
    const message = {
      id: 'unknown-1',
      role: 'system' as const,
      content: JSON.stringify({ type: 'unknown-future-type' }),
      timestamp: Date.now(),
    };

    const { container } = render(<AgentMessage message={message} />);
    expect(container.innerHTML).toBe('');
  });
});
