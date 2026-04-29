import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';

// Virtuoso uses urx reactive streams that require a real browser scroll model;
// replace it with a minimal shim that exercices the same props contract.
vi.mock('react-virtuoso', () => ({
  Virtuoso: ({
    totalCount,
    itemContent,
    components,
  }: {
    totalCount: number;
    itemContent: (i: number) => React.ReactNode;
    components?: { Footer?: () => React.ReactNode };
  }) => (
    <div role="log">
      {Array.from({ length: totalCount }, (_, i) => (
        <div key={i}>{itemContent(i)}</div>
      ))}
      {components?.Footer?.()}
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
});
