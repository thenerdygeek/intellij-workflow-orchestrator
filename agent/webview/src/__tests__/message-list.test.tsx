import { describe, it, expect, vi } from 'vitest';
import { useState } from 'react';
import { act, render, screen } from '@testing-library/react';

// Virtuoso uses urx reactive streams that require a real browser scroll model;
// replace it with a minimal shim that exercises the same props contract.
// MessageList registers `Footer` in `components`, so the mock renders it after
// the virtualized rows.
vi.mock('react-virtuoso', () => ({
  Virtuoso: ({
    totalCount,
    itemContent,
    components,
  }: {
    totalCount: number;
    itemContent: (i: number) => React.ReactNode;
    components?: { Footer?: React.ComponentType };
  }) => {
    const Footer = components?.Footer;
    return (
      <div role="log">
        {Array.from({ length: totalCount }, (_, i) => (
          <div key={i}>{itemContent(i)}</div>
        ))}
        {Footer && <Footer />}
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
      </div>,
    );
    expect(screen.getByTestId('row-0')).toBeInTheDocument();
  });

  it('mounts the supplied Footer component inside the scrolling region', () => {
    function Footer() {
      return <div data-testid="footer">FOOTER</div>;
    }
    render(
      <div style={{ height: 400 }}>
        <MessageList
          count={1}
          renderItem={() => <div>row</div>}
          Footer={Footer}
        />
      </div>,
    );
    expect(screen.getByTestId('footer')).toBeInTheDocument();
    // Footer must be a descendant of role="log" so it scrolls with messages,
    // not pinned outside the scroller (the broken pre-fix arrangement).
    const log = screen.getByRole('log');
    expect(log.contains(screen.getByTestId('footer'))).toBe(true);
  });

  // Regression guard for the architectural contract introduced by the
  // ChatFooter refactor: the Footer must update its content from its OWN
  // state subscriptions, not via prop forwarding through Virtuoso. Pre-fix,
  // the parent rebuilt a `footer` ReactNode each render and hoped Virtuoso's
  // urx store would flush it; under streaming load it didn't, and the live
  // tail froze. With Footer as a stable component owning its own state,
  // re-renders of the parent must not be required for the Footer to update.
  it('Footer renders fresh content from its own state without parent re-render', () => {
    let setCounter: ((n: number) => void) | undefined;
    function StatefulFooter() {
      const [n, setN] = useState(0);
      setCounter = setN;
      return <div data-testid="counter">count {n}</div>;
    }

    render(
      <div style={{ height: 400 }}>
        <MessageList
          count={1}
          renderItem={() => <div>row</div>}
          Footer={StatefulFooter}
        />
      </div>,
    );
    expect(screen.getByTestId('counter').textContent).toBe('count 0');

    act(() => setCounter?.(7));
    expect(screen.getByTestId('counter').textContent).toBe('count 7');

    act(() => setCounter?.(42));
    expect(screen.getByTestId('counter').textContent).toBe('count 42');
  });
});
