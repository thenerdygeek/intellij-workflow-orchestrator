import { memo } from 'react';
import { Loader2 } from 'lucide-react';
import { useChatStore } from '@/stores/chatStore';

/**
 * Pinned banner shown at the top of the chat while a manual compaction is in
 * progress. Pairs with disabling chat input + the compact button so the user
 * can't mutate state during the LLM summarization round-trip.
 *
 * Render this near the top of ChatView so it floats above the scroll viewport.
 */
export const CompactionOverlay = memo(function CompactionOverlay() {
  const compactionState = useChatStore((s) => s.compactionState);
  if (!compactionState.active) return null;

  return (
    <div
      className="sticky top-0 z-30 flex items-center gap-3 px-4 py-2 animate-in fade-in slide-in-from-top-2"
      style={{
        background: 'color-mix(in srgb, var(--accent, #6366f1) 12%, var(--bg, #ffffff))',
        borderBottom: '1px solid color-mix(in srgb, var(--accent, #6366f1) 30%, transparent)',
      }}
      role="status"
      aria-live="polite"
    >
      <Loader2
        className="size-4 animate-spin shrink-0"
        style={{ color: 'var(--accent, #6366f1)' }}
      />
      <span
        className="text-[12px] font-medium"
        style={{ color: 'var(--fg, #1f2328)' }}
      >
        {compactionState.phase || 'Compacting context...'}
      </span>
      <span
        className="text-[10px] ml-auto"
        style={{ color: 'var(--fg-muted, #57606a)' }}
      >
        Input disabled until done
      </span>
    </div>
  );
});
