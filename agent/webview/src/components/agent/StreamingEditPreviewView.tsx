// Copyright 2025-2026 Workflow Orchestrator Contributors
// SPDX-License-Identifier: Apache-2.0
import { memo } from 'react';
import { useChatStore } from '@/stores/chatStore';
import { DiffHtml } from '@/components/rich/DiffHtml';

/**
 * Live streaming-diff preview for in-flight `edit_file` tool calls.
 *
 * Subscribes to `chatStore.streamingEdits` and renders one card per active
 * preview. Each entry is keyed by the AgentLoop-side callId; the diff text is
 * pushed by Kotlin's StreamingEditTracker via the `_streamingEdit{Open,Update,
 * Finalize,Cancel}` bridges. Renders the unified diff through the same
 * `<DiffHtml>` component the approval card uses, so once the tool call closes
 * the user is looking at byte-identical output (just statically rendered).
 *
 * Empty map → component returns null and contributes nothing to the layout.
 *
 * Mounted inside `ChatFooter` so the preview flows below the streaming text
 * bubble and above the (eventual) approval card. On `endStream` /
 * `clearChat` / `startSession` the chatStore drops `streamingEdits` to `{}`,
 * which makes this component go quiet automatically — no manual cleanup
 * needed on the React side.
 */
export const StreamingEditPreviewView = memo(function StreamingEditPreviewView() {
  const streamingEdits = useChatStore(s => s.streamingEdits);
  const entries = Object.entries(streamingEdits);
  if (entries.length === 0) return null;

  return (
    <div className="flex flex-col gap-2" data-testid="streaming-edit-preview-root">
      {entries.map(([callId, { path, diff, status }]) => (
        <div
          key={callId}
          data-testid={`streaming-edit-${callId}`}
          className="rounded-lg border border-[var(--border)] bg-[var(--code-bg)]/40 overflow-hidden"
        >
          <div className="px-3 py-1.5 text-[11px] font-mono text-[var(--fg-muted)] flex items-center gap-2">
            <span className="opacity-60">streaming edit</span>
            <span
              className="font-semibold truncate"
              title={path}
              data-testid={`streaming-edit-path-${callId}`}
            >
              {path}
            </span>
            {status === 'streaming' && (
              <span
                className="ml-auto inline-flex items-center gap-1 text-[10px]"
                style={{ color: 'var(--accent, #6366f1)' }}
                data-testid={`streaming-edit-live-${callId}`}
              >
                <span
                  className="inline-block h-1.5 w-1.5 rounded-full animate-pulse"
                  style={{ background: 'currentColor' }}
                />
                live
              </span>
            )}
          </div>
          <DiffHtml diffSource={diff} />
        </div>
      ))}
    </div>
  );
});
