import { describe, expect, it } from 'vitest';
import { useChatStore } from '@/stores/chatStore';

describe('chatStore — aggregateDiff', () => {
  it('starts null', () => {
    // Reset to fresh state for this test
    useChatStore.setState({ aggregateDiff: null });
    expect(useChatStore.getState().aggregateDiff).toBeNull();
  });

  it('updateAggregateDiff sets the diff and totals', () => {
    useChatStore.getState().updateAggregateDiff({
      totalAdded: 12, totalRemoved: 3,
      files: [{ path: 'src/Foo.kt', added: 12, removed: 3, status: 'MODIFIED' }],
    });
    const s = useChatStore.getState();
    expect(s.aggregateDiff?.totalAdded).toBe(12);
    expect(s.aggregateDiff?.totalRemoved).toBe(3);
    expect(s.aggregateDiff?.files.length).toBe(1);
    expect(s.aggregateDiff?.files[0].path).toBe('src/Foo.kt');
  });
});
