import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';

// Virtuoso uses urx reactive streams that require a real browser scroll model;
// replace it with a minimal shim that exercices the same props contract.
// Real Virtuoso renders `components.Footer` as a React component receiving
// `{ context }`; the mock matches so the stable-Footer pattern in MessageList
// (Footer reads dynamic content from `context.footer`) is exercised.
vi.mock('react-virtuoso', () => ({
  Virtuoso: ({
    totalCount,
    itemContent,
    components,
    context,
  }: {
    totalCount: number;
    itemContent: (i: number) => React.ReactNode;
    components?: { Footer?: React.ComponentType<{ context?: unknown }> };
    context?: unknown;
  }) => {
    const Footer = components?.Footer;
    return (
      <div role="log">
        {Array.from({ length: totalCount }, (_, i) => (
          <div key={i}>{itemContent(i)}</div>
        ))}
        {Footer ? <Footer context={context} /> : null}
      </div>
    );
  },
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
