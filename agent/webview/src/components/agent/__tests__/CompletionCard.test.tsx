import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, cleanup } from '@testing-library/react';
import { CompletionCard } from '../CompletionCard';

vi.mock('@/components/markdown/MarkdownRenderer', () => ({
  MarkdownRenderer: ({ content }: { content: string }) => <span>{content}</span>,
}));

function mockJcefEnvironment() {
  Object.defineProperty(window, 'location', {
    value: { ...window.location, origin: 'http://workflow-agent' },
    writable: true,
    configurable: true,
  });
}

function mockBrowserEnvironment() {
  Object.defineProperty(window, 'location', {
    value: { ...window.location, origin: 'http://localhost' },
    writable: true,
    configurable: true,
  });
}

describe('CompletionCard', () => {
  beforeEach(() => {
    cleanup();
    mockJcefEnvironment();
    (window as any)._copyToClipboard = vi.fn();
  });

  afterEach(() => {
    mockBrowserEnvironment();
  });

  it('done kind renders green label and result', () => {
    render(<CompletionCard data={{ kind: 'done', result: 'All tests pass.' }} />);
    expect(screen.getByText('Task Completed')).toBeDefined();
    expect(screen.getByText('All tests pass.')).toBeDefined();
  });

  it('review kind renders amber label and verify pill above body', () => {
    render(<CompletionCard data={{ kind: 'review', result: 'Check the admin panel.', verifyHow: 'open /admin' }} />);
    expect(screen.getByText('Please Review')).toBeDefined();
    // verify pill text
    expect(screen.getByText('open /admin')).toBeDefined();
  });

  it('heads_up kind renders blue label and discovery callout', () => {
    render(<CompletionCard data={{ kind: 'heads_up', result: 'Migration done.', discovery: '3 orphaned tables found.' }} />);
    expect(screen.getByText('Heads Up')).toBeDefined();
    expect(screen.getByText('Discovery')).toBeDefined();
    expect(screen.getByText('3 orphaned tables found.')).toBeDefined();
  });
});
