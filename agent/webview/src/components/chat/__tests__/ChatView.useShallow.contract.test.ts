import { describe, it, expect } from 'vitest';
import * as fs from 'fs';
import * as path from 'path';

/**
 * P0-3 / P2-13 (perf Wave 3):
 * selectActiveSubAgents is NO LONGER called in ChatView. The pattern was:
 *   `useChatStore(useShallow(selectActiveSubAgents))`
 * which rebuilt a Map<agentId,SubAgentState> on EVERY store change, causing
 * ChatView (and every visible row) to re-render per streaming delta.
 *
 * After P0-3 the sub-agent stream side-channel (`subAgentStreams`) holds live
 * data; `SubAgentView` subscribes directly to its own slice
 * (`s => s.subAgentStreams?.[agentId]`). ChatView passes committed
 * `msg.subagentData` to SubAgentView; the shallow-Map subscription is gone.
 *
 * This test is updated to pin the NEW architecture:
 * 1. ChatView must NOT call selectActiveSubAgents (the hot-path Map rebuild
 *    is intentionally eliminated).
 * 2. SubAgentView must subscribe to `subAgentStreams` for the side-channel.
 */
describe('ChatView sub-agent subscription architecture (P0-3 side-channel)', () => {
  it('ChatView does NOT subscribe to selectActiveSubAgents (hot-path Map rebuild eliminated)', () => {
    const src = fs.readFileSync(
      path.resolve(__dirname, '../ChatView.tsx'),
      'utf8',
    );
    // The old hot-path Map subscription must be gone.
    const hasOldSubscription = /useChatStore\s*\(\s*useShallow\s*\(\s*selectActiveSubAgents\s*\)/.test(src);
    expect(hasOldSubscription).toBe(false);
  });

  it('SubAgentView subscribes to its own subAgentStreams slice for live streaming data', () => {
    const subSrc = fs.readFileSync(
      path.resolve(__dirname, '../../agent/SubAgentView.tsx'),
      'utf8',
    );
    // Must subscribe to the side-channel slice keyed by agentId.
    const hasSliceSubscription = /useChatStore\s*\(\s*\(s\)\s*=>\s*s\.subAgentStreams/.test(subSrc);
    expect(hasSliceSubscription).toBe(true);
  });
});
