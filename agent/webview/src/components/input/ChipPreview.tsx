import { memo } from 'react';
import { X, FileText } from 'lucide-react';
import type { PendingAttachment } from './AttachmentManager';

interface ChipPreviewProps {
  attachments: PendingAttachment[];
  onRemove: (sha256: string) => void;
}

/**
 * Phase 5: thumbnail row above the input area. Stacks horizontally; each chip
 * shows a 64×64 preview from an in-memory ObjectURL with a × button on hover
 * to remove. Hidden entirely when the pending list is empty so the input
 * area's vertical rhythm is unaffected for the common no-image case.
 */
export const ChipPreview = memo(function ChipPreview({ attachments, onRemove }: ChipPreviewProps) {
  if (attachments.length === 0) return null;
  return (
    <div
      className="flex flex-wrap items-center gap-1.5 px-2 pt-2 pb-1"
      role="list"
      aria-label="Pending attachments"
    >
      {attachments.map(att =>
        att.kind === 'file' ? (
          <div
            key={att.sha256}
            className="relative group flex items-center gap-1.5 rounded border px-2 py-1"
            role="listitem"
            title={`${att.originalFilename} • ${Math.round(att.size / 1024)} KB`}
            style={{ borderColor: 'var(--border, #2c2f33)', maxWidth: 180 }}
          >
            <FileText className="h-3.5 w-3.5 shrink-0" style={{ color: 'var(--fg-muted)' }} />
            <span className="truncate text-[11px]">{att.originalFilename}</span>
            <button
              type="button"
              onClick={() => onRemove(att.sha256)}
              aria-label={`Remove ${att.originalFilename}`}
              title="Remove"
              className="ml-1 inline-flex h-4 w-4 items-center justify-center rounded-full opacity-0 group-hover:opacity-100 transition-opacity"
              style={{ background: 'var(--fg, #111)', color: 'var(--bg, #fff)', border: '1px solid var(--border, #2c2f33)' }}
            >
              <X className="h-2.5 w-2.5" strokeWidth={3} />
            </button>
          </div>
        ) : (
          <div
            key={att.sha256}
            className="relative group"
            role="listitem"
            title={`${att.originalFilename} • ${Math.round(att.size / 1024)} KB`}
            style={{ width: 64, height: 64 }}
          >
            <img
              src={att.thumbnailUrl}
              alt={att.originalFilename}
              className="rounded border"
              style={{
                width: '100%',
                height: '100%',
                objectFit: 'cover',
                borderColor: 'var(--border, #2c2f33)',
              }}
            />
            <button
              type="button"
              onClick={() => onRemove(att.sha256)}
              aria-label={`Remove ${att.originalFilename}`}
              title="Remove"
              className="absolute -top-1.5 -right-1.5 inline-flex h-4 w-4 items-center justify-center rounded-full opacity-0 group-hover:opacity-100 transition-opacity"
              style={{
                background: 'var(--fg, #111)',
                color: 'var(--bg, #fff)',
                border: '1px solid var(--border, #2c2f33)',
              }}
            >
              <X className="h-2.5 w-2.5" strokeWidth={3} />
            </button>
          </div>
        )
      )}
    </div>
  );
});
