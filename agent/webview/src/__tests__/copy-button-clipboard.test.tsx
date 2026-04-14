/**
 * Tests for clipboard copy functionality across chat UI components.
 *
 * Verifies that:
 * 1. CopyButton routes through the JCEF bridge in JCEF environments
 * 2. CopyButton falls back to navigator.clipboard in browser environments
 * 3. Components that need copy buttons have them (CompletionCard, SubAgentView, etc.)
 * 4. Visual feedback (icon swap to checkmark) works correctly
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, cleanup, act } from '@testing-library/react';
import { CopyButton } from '@/components/ui/copy-button';
import { CompletionCard } from '@/components/agent/CompletionCard';

// ── Helpers ──

/** Simulate JCEF environment by mocking window.location.origin */
function mockJcefEnvironment() {
  Object.defineProperty(window, 'location', {
    value: { ...window.location, origin: 'http://workflow-agent' },
    writable: true,
    configurable: true,
  });
}

/** Restore default jsdom environment */
function mockBrowserEnvironment() {
  Object.defineProperty(window, 'location', {
    value: { ...window.location, origin: 'http://localhost' },
    writable: true,
    configurable: true,
  });
}

describe('CopyButton — JCEF bridge integration', () => {
  beforeEach(() => {
    cleanup();
    vi.useFakeTimers();
    // Reset the mock between tests
    (window as any)._copyToClipboard = vi.fn();
  });

  afterEach(() => {
    vi.useRealTimers();
    mockBrowserEnvironment();
  });

  it('calls _copyToClipboard bridge in JCEF environment', () => {
    mockJcefEnvironment();
    render(<CopyButton text="hello world" label="Copy" />);

    const btn = screen.getByTitle('Copy');
    fireEvent.click(btn);

    expect((window as any)._copyToClipboard).toHaveBeenCalledWith('hello world');
  });

  it('does NOT call _copyToClipboard in browser environment', () => {
    mockBrowserEnvironment();
    // Mock navigator.clipboard for browser path
    const writeText = vi.fn().mockResolvedValue(undefined);
    Object.defineProperty(navigator, 'clipboard', {
      value: { writeText },
      writable: true,
      configurable: true,
    });

    render(<CopyButton text="browser text" label="Copy" />);
    fireEvent.click(screen.getByTitle('Copy'));

    expect((window as any)._copyToClipboard).not.toHaveBeenCalled();
    expect(writeText).toHaveBeenCalledWith('browser text');
  });

  it('shows checkmark feedback after copy in JCEF mode', () => {
    mockJcefEnvironment();
    render(<CopyButton text="test" label="Copy" />);

    const btn = screen.getByTitle('Copy');
    fireEvent.click(btn);

    // After click, title should change to "Copied!"
    expect(screen.getByTitle('Copied!')).toBeDefined();

    // After 2 seconds, should revert
    act(() => { vi.advanceTimersByTime(2000); });
    expect(screen.getByTitle('Copy')).toBeDefined();
  });

  it('renders as hidden when hoverOnly is true', () => {
    mockJcefEnvironment();
    const { container } = render(<CopyButton text="test" hoverOnly />);

    const btn = container.querySelector('button');
    expect(btn?.className).toContain('opacity-0');
    expect(btn?.className).toContain('group-hover:opacity-100');
  });

  it('supports sm and md sizes', () => {
    mockJcefEnvironment();

    const { container: smContainer } = render(<CopyButton text="t" size="sm" />);
    const smSvg = smContainer.querySelector('svg');
    expect(smSvg?.getAttribute('width')).toBe('12');

    cleanup();

    const { container: mdContainer } = render(<CopyButton text="t" size="md" />);
    const mdSvg = mdContainer.querySelector('svg');
    expect(mdSvg?.getAttribute('width')).toBe('14');
  });
});

describe('CompletionCard — copy buttons', () => {
  beforeEach(() => {
    cleanup();
    mockJcefEnvironment();
    (window as any)._copyToClipboard = vi.fn();
  });

  afterEach(() => {
    mockBrowserEnvironment();
  });

  it('has a copy button for the result text', () => {
    render(<CompletionCard result="Task done successfully" />);

    const copyBtn = screen.getByTitle('Copy result');
    expect(copyBtn).toBeDefined();

    fireEvent.click(copyBtn);
    expect((window as any)._copyToClipboard).toHaveBeenCalledWith('Task done successfully');
  });

  it('has a copy button for the verify command', () => {
    render(<CompletionCard result="Done" verifyCommand="npm test" />);

    const copyCmd = screen.getByTitle('Copy command');
    expect(copyCmd).toBeDefined();

    fireEvent.click(copyCmd);
    expect((window as any)._copyToClipboard).toHaveBeenCalledWith('npm test');
  });

  it('renders without verify command copy when no command provided', () => {
    render(<CompletionCard result="Done" />);

    expect(screen.queryByTitle('Copy command')).toBeNull();
    expect(screen.getByTitle('Copy result')).toBeDefined();
  });
});
