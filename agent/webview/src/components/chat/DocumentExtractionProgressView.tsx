// Copyright 2025-2026 Workflow Orchestrator Contributors
// SPDX-License-Identifier: Apache-2.0
import { memo } from 'react';
import { useChatStore } from '@/stores/chatStore';

/**
 * Compact status row shown in `ChatFooter` while a `read_document` call is
 * blocking on background extraction.
 *
 * Formats elapsed time as mm:ss and shows page progress when the page count is
 * known. Subscribes to `chatStore.documentExtraction`; returns null when null,
 * so it contributes nothing to the layout when extraction is not in progress.
 *
 * Cleared automatically when `read_document` completes (success or error) via
 * the `_documentExtractionClear` bridge, and on session lifecycle resets
 * (endStream / clearChat / startSession / completeSession).
 */
export const DocumentExtractionProgressView = memo(function DocumentExtractionProgressView() {
  const extraction = useChatStore(s => s.documentExtraction);
  if (extraction === null) return null;

  const { pagesDone, pagesTotal, elapsedMs } = extraction;

  // Format elapsedMs as mm:ss (e.g. 65 000 ms → "1:05")
  const totalSec = Math.floor(elapsedMs / 1000);
  const mm = Math.floor(totalSec / 60);
  const ss = String(totalSec % 60).padStart(2, '0');
  const elapsed = `${mm}:${ss}`;

  const label =
    pagesTotal != null
      ? `Extracting document… page ${pagesDone} of ${pagesTotal} (${elapsed})`
      : `Extracting document… (${elapsed})`;

  return (
    <div
      className="flex items-center gap-2 text-[12px]"
      style={{ color: 'var(--fg-muted)' }}
      data-testid="document-extraction-progress"
    >
      {/* Spinner dot matching WorkingIndicator / StreamingEditPreviewView aesthetics */}
      <span
        className="inline-block h-2 w-2 rounded-full animate-pulse flex-shrink-0"
        style={{ background: 'var(--accent, #6366f1)' }}
        aria-hidden="true"
      />
      <span data-testid="document-extraction-label">{label}</span>
    </div>
  );
});
