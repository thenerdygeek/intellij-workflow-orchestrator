import { memo } from 'react';
import { MarkdownRenderer } from '@/components/markdown/MarkdownRenderer';

interface CompletionCardProps {
  result: string;
  verifyCommand?: string;
}

export const CompletionCard = memo(function CompletionCard({
  result,
  verifyCommand,
}: CompletionCardProps) {
  const handleCopyCommand = () => {
    if (verifyCommand) {
      navigator.clipboard.writeText(verifyCommand);
    }
  };

  return (
    <div
      className="relative rounded-lg overflow-hidden animate-[message-enter_300ms_ease-out_both]"
      style={{
        border: '1px solid color-mix(in srgb, var(--success, #22C55E) 30%, var(--border, #333))',
        background: `linear-gradient(
          135deg,
          color-mix(in srgb, var(--success, #22C55E) 4%, var(--bg, #1e1e1e)) 0%,
          var(--bg, #1e1e1e) 100%
        )`,
      }}
    >
      {/* Header bar */}
      <div
        className="flex items-center gap-2 px-4 py-2.5"
        style={{
          borderBottom: '1px solid color-mix(in srgb, var(--success, #22C55E) 15%, var(--border, #333))',
          background: 'color-mix(in srgb, var(--success, #22C55E) 6%, transparent)',
        }}
      >
        <svg width="16" height="16" viewBox="0 0 16 16" fill="none" xmlns="http://www.w3.org/2000/svg">
          <circle cx="8" cy="8" r="7" stroke="var(--success, #22C55E)" strokeWidth="1.5" fill="color-mix(in srgb, var(--success, #22C55E) 15%, transparent)" />
          <path d="M5 8l2 2 4-4" stroke="var(--success, #22C55E)" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" />
        </svg>
        <span
          className="text-[12px] font-semibold tracking-wide uppercase"
          style={{ color: 'var(--success, #22C55E)' }}
        >
          Task Completed
        </span>
      </div>

      {/* Summary content */}
      <div className="px-4 py-3 text-[13px]" style={{ color: 'var(--fg)' }}>
        <MarkdownRenderer content={result} isStreaming={false} />
      </div>

      {/* Verify command */}
      {verifyCommand && (
        <div
          className="mx-4 mb-3 flex items-center gap-2 rounded-md px-3 py-2"
          style={{
            background: 'var(--code-bg, #1a1a2e)',
            border: '1px solid var(--border, #333)',
          }}
        >
          <svg width="12" height="12" viewBox="0 0 16 16" fill="none" xmlns="http://www.w3.org/2000/svg" className="flex-shrink-0">
            <path d="M2 4l4 4-4 4M8 12h6" stroke="var(--fg-muted, #888)" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" />
          </svg>
          <code className="flex-1 text-[12px] font-mono" style={{ color: 'var(--fg-secondary)' }}>
            {verifyCommand}
          </code>
          <button
            onClick={handleCopyCommand}
            className="flex-shrink-0 rounded p-1 transition-colors hover:bg-[var(--hover-overlay-strong)]"
            title="Copy command"
          >
            <svg width="12" height="12" viewBox="0 0 16 16" fill="none" xmlns="http://www.w3.org/2000/svg">
              <rect x="5" y="5" width="9" height="9" rx="1.5" stroke="var(--fg-muted, #888)" strokeWidth="1.2" />
              <path d="M11 5V3.5A1.5 1.5 0 009.5 2h-6A1.5 1.5 0 002 3.5v6A1.5 1.5 0 003.5 11H5" stroke="var(--fg-muted, #888)" strokeWidth="1.2" />
            </svg>
          </button>
        </div>
      )}
    </div>
  );
});
