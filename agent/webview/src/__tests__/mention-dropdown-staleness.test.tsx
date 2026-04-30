/**
 * Regression tests for the @ and # popup behaviour the user reported on 2026-05-01:
 *   - "@" was showing items that didn't match the typed query (stale results
 *     bleeding through the 200ms bridge debounce)
 *   - "#" was showing only one item on a blank query
 *
 * These tests render the dropdown components directly and pin the contract
 * that came out of the live Playwright verification.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, cleanup } from '@testing-library/react';
import { useRef } from 'react';
import { MentionDropdown, relevanceScore } from '../components/input/MentionDropdown';
import { TicketDropdown } from '../components/input/TicketDropdown';
import { useChatStore } from '../stores/chatStore';
import type { MentionSearchResult } from '../bridge/types';

// ── Test helpers ──

function MentionHarness({ query }: { query: string }) {
  const listRef = useRef<HTMLDivElement>(null!);
  return (
    <MentionDropdown
      query={query}
      onSelect={() => {}}
      onDismiss={() => {}}
      selectedIndex={0}
      setSelectedIndex={() => {}}
      listRef={listRef}
    />
  );
}

function TicketHarness({ query, onResultsChange }: { query: string; onResultsChange?: (r: MentionSearchResult[]) => void }) {
  const listRef = useRef<HTMLDivElement>(null!);
  return (
    <TicketDropdown
      query={query}
      onSelect={() => {}}
      onDismiss={() => {}}
      selectedIndex={0}
      setSelectedIndex={() => {}}
      listRef={listRef}
      onResultsChange={onResultsChange}
    />
  );
}

const STALE_PRIOR_QUERY_RESULTS: MentionSearchResult[] = [
  { type: 'file',   label: 'foo.kt',   path: 'src/foo.kt',   description: 'src' },
  { type: 'file',   label: 'bar.kt',   path: 'src/bar.kt',   description: 'src' },
  { type: 'folder', label: 'helpers/', path: 'src/helpers',  description: 'src/helpers' },
  { type: 'symbol', label: 'FooClass', path: 'com.example.FooClass', description: 'src/foo.kt' },
];

beforeEach(() => {
  // Suppress real bridge calls and reset store between tests
  (window as any)._searchMentions = vi.fn();
  (window as any)._searchTickets = vi.fn();
  useChatStore.setState({ mentionResults: [] });
});

afterEach(() => {
  cleanup();
});

// ── relevanceScore: contract on which the staleness filter depends ──

describe('relevanceScore', () => {
  it('returns 100 when label name (without extension) starts with query', () => {
    expect(relevanceScore('foo.kt', 'src/foo.kt', 'foo')).toBe(100);
  });

  it('returns 0 when query does not appear anywhere in label or path', () => {
    expect(relevanceScore('bar.kt', 'src/bar.kt', 'xyz')).toBe(0);
  });

  it('returns 0 for empty label', () => {
    expect(relevanceScore(undefined, 'src/foo.kt', 'foo')).toBe(0);
  });

  it('FooClass scores against `foo` (case-insensitive starts-with)', () => {
    expect(relevanceScore('FooClass', 'com.example.FooClass', 'foo')).toBeGreaterThan(0);
  });
});

// ── Bug 2: stale @ results bleeding through during debounce ──

describe('MentionDropdown staleness filter (Bug 2)', () => {
  it('drops score=0 items when the query is non-empty', () => {
    // Simulates the 200ms bridge-debounce window: store still holds results from a prior
    // query (here `foo`-shaped) while the user has typed something else (`xyz`). Without
    // the filter every stale item rendered as a "suggestion" against the new query.
    useChatStore.setState({ mentionResults: STALE_PRIOR_QUERY_RESULTS });
    render(<MentionHarness query="xyz" />);

    expect(screen.getByText('No results found.')).toBeTruthy();
    expect(screen.queryByText('foo.kt')).toBeNull();
    expect(screen.queryByText('bar.kt')).toBeNull();
    expect(screen.queryByText('helpers/')).toBeNull();
    expect(screen.queryByText('FooClass')).toBeNull();
  });

  it('keeps score>0 items and drops score=0 items when both are present', () => {
    // foo.kt and FooClass score>0 against `foo`; bar.kt and helpers/ score 0 and must be hidden.
    useChatStore.setState({ mentionResults: STALE_PRIOR_QUERY_RESULTS });
    render(<MentionHarness query="foo" />);

    expect(screen.getByText('foo.kt')).toBeTruthy();
    expect(screen.getByText('FooClass')).toBeTruthy();
    expect(screen.queryByText('bar.kt')).toBeNull();
    expect(screen.queryByText('helpers/')).toBeNull();
  });

  it('shows everything when the query is empty (open-tabs behavior)', () => {
    // Empty query is the "open editor tabs" path — score=0 items must NOT be filtered,
    // otherwise the empty-state landing dropdown would always render blank.
    useChatStore.setState({ mentionResults: STALE_PRIOR_QUERY_RESULTS });
    render(<MentionHarness query="" />);

    expect(screen.getByText('foo.kt')).toBeTruthy();
    expect(screen.getByText('bar.kt')).toBeTruthy();
    expect(screen.getByText('helpers/')).toBeTruthy();
    expect(screen.getByText('FooClass')).toBeTruthy();
  });
});

// ── Bug 1: # dropdown must surface the full bridge payload ──

describe('TicketDropdown renders the entire bridge payload (Bug 1)', () => {
  it('renders all five items when the bridge returns active+sprint list', async () => {
    // After the Kotlin fix, an empty `#` query no longer short-circuits to a single
    // active ticket — it returns the full sprint list with the active one prepended.
    // Verify the React side faithfully renders every result instead of dropping any.
    const fivePayload: MentionSearchResult[] = [
      { type: 'ticket', label: 'PROJ-100', path: 'PROJ-100', description: 'Active feature' },
      { type: 'ticket', label: 'PROJ-201', path: 'PROJ-201', description: 'Sprint A', icon: 'In Progress' },
      { type: 'ticket', label: 'PROJ-202', path: 'PROJ-202', description: 'Sprint B', icon: 'To Do' },
      { type: 'ticket', label: 'PROJ-203', path: 'PROJ-203', description: 'Sprint C', icon: 'In Review' },
      { type: 'ticket', label: 'PROJ-204', path: 'PROJ-204', description: 'Sprint D', icon: 'Done' },
    ];
    let captured: MentionSearchResult[] = [];

    (window as any)._searchTickets = (_q: string) => {
      // TicketDropdown registers a fresh global callback per mount; deliver to it.
      const cb = (window as any).__ticketSearchCallback;
      if (cb) cb(JSON.stringify(fivePayload));
    };

    render(<TicketHarness query="" onResultsChange={(r) => { captured = r; }} />);

    // The component debounces the bridge call by 200ms; flush.
    await new Promise(r => setTimeout(r, 250));

    expect(captured.length).toBe(5);
    for (const t of fivePayload) {
      expect(screen.getByText(t.label)).toBeTruthy();
    }
  });

  it('shows "No tickets found." when the bridge returns an empty array', async () => {
    (window as any)._searchTickets = (_q: string) => {
      const cb = (window as any).__ticketSearchCallback;
      if (cb) cb('[]');
    };

    render(<TicketHarness query="ZZZ" />);
    await new Promise(r => setTimeout(r, 250));

    expect(screen.getByText('No tickets found.')).toBeTruthy();
  });
});
