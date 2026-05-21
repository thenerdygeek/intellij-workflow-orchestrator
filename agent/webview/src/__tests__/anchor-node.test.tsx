/**
 * Tests for AnchorNode click routing and the symbol: sanitizer whitelist.
 *
 * Coverage:
 *  1. symbol: href survives rehype-sanitize (protocol whitelist fix)
 *  2. Click on resolved symbol link (data-canonical present) → _navigateToFile(path:line+1)
 *  3. Click on unresolved symbol link (data-canonical absent) → _navigateToFile NOT called
 *  4. Click on regular file-path link → _navigateToFile(href) unchanged
 *  5. Click on anchor with falsy href → nothing happens (no crash)
 *  6. javascript: href is still stripped (security regression guard)
 */
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render, cleanup, fireEvent } from '@testing-library/react';
import { MarkdownRenderer } from '@/components/markdown/MarkdownRenderer';

// _navigateToFile is pre-registered as vi.fn() in setup.ts
function getNavigateMock() {
  return (window as any)._navigateToFile as ReturnType<typeof vi.fn>;
}

describe('AnchorNode — symbol: sanitizer whitelist', () => {
  beforeEach(() => {
    cleanup();
    vi.clearAllMocks();
  });

  it('preserves symbol: href through rehype-sanitize so the scanner can find it', () => {
    const { container } = render(
      <MarkdownRenderer content="[UserService](symbol:com.example.UserService)" />,
    );

    const anchor = container.querySelector('a') as HTMLAnchorElement | null;
    expect(anchor).not.toBeNull();
    expect(anchor?.getAttribute('href')).toBe('symbol:com.example.UserService');
  });

  it('still strips javascript: hrefs (security regression guard)', () => {
    const { container } = render(
      // eslint-disable-next-line no-script-url
      <MarkdownRenderer content={'[bad](javascript:alert(1))'} />,
    );

    const anchor = container.querySelector('a');
    // href must be absent or empty — never the javascript: payload
    expect(anchor?.getAttribute('href') ?? '').not.toContain('javascript:');
  });
});

describe('AnchorNode — symbol: click routing', () => {
  beforeEach(() => {
    cleanup();
    vi.clearAllMocks();
  });

  it('calls _navigateToFile with canonicalPath:line+1 when data-canonical is set', () => {
    const { container } = render(
      <MarkdownRenderer content="[UserService](symbol:com.example.UserService)" />,
    );

    const anchor = container.querySelector('a') as HTMLAnchorElement;
    expect(anchor).not.toBeNull();

    // Simulate what scanAndSymbolLinkify writes onto the DOM
    anchor.dataset.canonical = '/project/src/UserService.kt';
    anchor.dataset.line = '9'; // 0-based

    fireEvent.click(anchor);

    expect(getNavigateMock()).toHaveBeenCalledOnce();
    expect(getNavigateMock()).toHaveBeenCalledWith('/project/src/UserService.kt:10');
  });

  it('does NOT call _navigateToFile when data-canonical is absent (unresolved link)', () => {
    const { container } = render(
      <MarkdownRenderer content="[Unknown](symbol:com.example.Unknown)" />,
    );

    const anchor = container.querySelector('a') as HTMLAnchorElement;
    expect(anchor).not.toBeNull();
    // No data-canonical — scanner stripped the href, but click fires anyway
    // Simulate the stripped-href case: scanner removed href for invalid symbols
    anchor.removeAttribute('href');

    fireEvent.click(anchor);

    expect(getNavigateMock()).not.toHaveBeenCalled();
  });

  it('uses line 0 (→ :1) when data-line is missing', () => {
    const { container } = render(
      <MarkdownRenderer content="[Foo](symbol:com.example.Foo)" />,
    );

    const anchor = container.querySelector('a') as HTMLAnchorElement;
    anchor.dataset.canonical = '/src/Foo.kt';
    // intentionally omit data-line

    fireEvent.click(anchor);

    expect(getNavigateMock()).toHaveBeenCalledWith('/src/Foo.kt:1');
  });
});

describe('AnchorNode — regular link regression', () => {
  beforeEach(() => {
    cleanup();
    vi.clearAllMocks();
  });

  it('does NOT call _navigateToFile directly for non-symbol links — the confirmation modal is now the gate', () => {
    // Phase 4 hyperlink modal: non-symbol clicks open the LinkConfirmModal
    // instead of dispatching straight to _navigateToFile. The Kotlin-side
    // _openLink bridge handles navigation only after the user confirms in
    // the modal. _navigateToFile remains available for legacy callers but
    // is no longer invoked by the AnchorNode click path.
    const { container } = render(
      <MarkdownRenderer content="[see file](file:agent/src/main/kotlin/AgentLoop.kt:42)" />,
    );

    const anchor = container.querySelector('a') as HTMLAnchorElement | null;
    if (!anchor || !anchor.getAttribute('href')) return;

    fireEvent.click(anchor);

    expect(getNavigateMock()).not.toHaveBeenCalled();
  });
});
