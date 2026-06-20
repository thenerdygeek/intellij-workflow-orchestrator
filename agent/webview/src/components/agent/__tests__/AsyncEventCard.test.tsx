import { render, screen, fireEvent } from '@testing-library/react';
import { describe, it, expect } from 'vitest';
import { AsyncEventCard } from '../AsyncEventCard';
import type { UiMessageAsyncEventData } from '@/bridge/types';

const bg: UiMessageAsyncEventData = {
  id: 'bg-bg7-1', kind: 'BACKGROUND', sourceId: 'bg7', label: 'npm run build',
  status: 'SUCCESS', summary: 'exit 0 · 12s', details: 'webpack compiled\nDone in 12s', timestamp: 1,
};

describe('AsyncEventCard', () => {
  it('renders collapsed summary + label, details hidden', () => {
    render(<AsyncEventCard data={bg} />);
    expect(screen.getByText(/npm run build/)).toBeInTheDocument();
    expect(screen.getByText(/exit 0 · 12s/)).toBeInTheDocument();
    expect(screen.queryByText(/webpack compiled/)).not.toBeInTheDocument();
  });

  it('expands details on click', () => {
    render(<AsyncEventCard data={bg} />);
    fireEvent.click(screen.getByRole('button'));
    expect(screen.getByText(/webpack compiled/)).toBeInTheDocument();
  });

  it('monitor alert renders with monitor label', () => {
    render(<AsyncEventCard data={{ ...bg, kind: 'MONITOR', status: 'ALERT', label: 'shell-7', summary: '2 errors', sourceId: 'shell-7' }} />);
    // sourceId and label both equal 'shell-7' in this fixture, so multiple elements match
    expect(screen.getAllByText(/shell-7/).length).toBeGreaterThan(0);
  });

  it('shows the spillPath affordance when expanded and present', () => {
    render(<AsyncEventCard data={{ ...bg, spillPath: '/tmp/out.txt' }} />);
    fireEvent.click(screen.getByRole('button'));
    expect(screen.getByText(/Full output: \/tmp\/out\.txt/)).toBeInTheDocument();
  });
});
