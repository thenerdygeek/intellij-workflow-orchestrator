import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';

// Virtuoso uses urx reactive streams that require a real browser scroll model;
// replace it with a minimal shim that exercises the same props contract.
// MessageList renders `footer` as a plain sibling below Virtuoso (not via
// `components.Footer`), so the mock just needs to render the virtualized rows.
vi.mock('react-virtuoso', () => ({
  Virtuoso: ({
    totalCount,
    itemContent,
  }: {
    totalCount: number;
    itemContent: (i: number) => React.ReactNode;
  }) => (
    <div role="log">
      {Array.from({ length: totalCount }, (_, i) => (
        <div key={i}>{itemContent(i)}</div>
      ))}
    </div>
  ),
}));

import { MessageList } from '@/components/chat/MessageList';

describe('MessageList', () => {
  it('renders the supplied items via renderItem', () => {
    render(
      <div style={{ height: 400 }}>
        <MessageList
          count={3}
          renderItem={(i) => <div data-testid={`row-${i}`}>row {i}</div>}
        />
      </div>
    );
    expect(screen.getByTestId('row-0')).toBeInTheDocument();
  });

  it('exposes a footer slot for trailing UI', () => {
    render(
      <div style={{ height: 400 }}>
        <MessageList
          count={1}
          renderItem={() => <div>row</div>}
          footer={<div data-testid="footer">FOOTER</div>}
        />
      </div>
    );
    expect(screen.getByTestId('footer')).toBeInTheDocument();
  });

  // Regression guard: pre-fix, the footer was passed through Virtuoso's
  // `components.Footer` + `context` plumbing, and rapid re-renders during
  // run_command streaming failed to flush a fresh `context.footer` to the
  // mounted Footer — the live tail appeared frozen. Now that `footer`
  // renders as a plain sibling of Virtuoso, plain React reconciliation
  // must reflect updated content on every parent re-render.
  it('reflects updated footer content on re-render (live-tail regression guard)', () => {
    const { rerender } = render(
      <div style={{ height: 400 }}>
        <MessageList
          count={1}
          renderItem={() => <div>row</div>}
          footer={<div data-testid="tail">first</div>}
        />
      </div>
    );
    expect(screen.getByTestId('tail').textContent).toBe('first');

    rerender(
      <div style={{ height: 400 }}>
        <MessageList
          count={1}
          renderItem={() => <div>row</div>}
          footer={<div data-testid="tail">first second</div>}
        />
      </div>
    );
    expect(screen.getByTestId('tail').textContent).toBe('first second');

    rerender(
      <div style={{ height: 400 }}>
        <MessageList
          count={1}
          renderItem={() => <div>row</div>}
          footer={<div data-testid="tail">first second third</div>}
        />
      </div>
    );
    expect(screen.getByTestId('tail').textContent).toBe('first second third');
  });
});
