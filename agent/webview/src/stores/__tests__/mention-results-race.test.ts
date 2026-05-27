/**
 * #13: out-of-order mention search responses. A late response for an OLDER
 * query must not clobber the results of the latest requested query (which would
 * make the dropdown flash "no results" for a query that actually had matches).
 */
import { describe, it, expect, beforeEach } from 'vitest';
import { useChatStore } from '../chatStore';
import type { MentionSearchResult } from '../../types';

const r = (label: string): MentionSearchResult => ({ type: 'file', label, path: `src/${label}` });

describe('receiveMentionResults staleness guard', () => {
  beforeEach(() => {
    useChatStore.setState({ mentionResults: [], mentionResultsQuery: '', pendingMentionQuery: '' } as never);
  });

  it('accepts results for the latest requested query', () => {
    useChatStore.getState().setPendingMentionQuery('ab');
    useChatStore.getState().receiveMentionResults('ab', [r('alpha.ts')]);
    expect(useChatStore.getState().mentionResults.map(x => x.label)).toEqual(['alpha.ts']);
    expect(useChatStore.getState().mentionResultsQuery).toBe('ab');
  });

  it('ignores a late response for an older query (no clobber)', () => {
    // Current request is "ab" and its results are in the slot.
    useChatStore.getState().setPendingMentionQuery('ab');
    useChatStore.getState().receiveMentionResults('ab', [r('alpha.ts'), r('beta.ts')]);

    // A late, out-of-order response for the older "a" query arrives.
    useChatStore.getState().receiveMentionResults('a', [r('stale.ts')]);

    // The current "ab" results survive untouched.
    expect(useChatStore.getState().mentionResults.map(x => x.label)).toEqual(['alpha.ts', 'beta.ts']);
    expect(useChatStore.getState().mentionResultsQuery).toBe('ab');
  });
});
