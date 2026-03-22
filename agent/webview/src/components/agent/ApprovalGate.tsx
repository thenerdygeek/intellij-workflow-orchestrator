import { useCallback } from 'react';

interface ApprovalGateProps {
  title: string;
  description?: string;
  commandPreview?: string;
  onApprove: () => void;
  onDeny: () => void;
}

export function ApprovalGate({ title, description, commandPreview, onApprove, onDeny }: ApprovalGateProps) {
  const handleApprove = useCallback(() => { onApprove(); }, [onApprove]);
  const handleDeny = useCallback(() => { onDeny(); }, [onDeny]);

  return (
    <div
      className="my-3 rounded-xl border-2 animate-[fade-in_220ms_ease-out]"
      role="alertdialog"
      aria-label="Approval required"
      style={{
        borderColor: 'var(--warning, #f59e0b)',
        backgroundColor: 'color-mix(in srgb, var(--warning, #f59e0b) 6%, var(--bg, #1e1e1e))',
      }}
    >
      {/* Header with warning icon */}
      <div className="flex items-center gap-3 px-4 py-3">
        <div
          className="flex h-8 w-8 flex-shrink-0 items-center justify-center rounded-lg"
          style={{ backgroundColor: 'color-mix(in srgb, var(--warning, #f59e0b) 15%, transparent)' }}
        >
          <svg className="h-5 w-5" style={{ color: 'var(--warning, #f59e0b)' }} viewBox="0 0 16 16" fill="none">
            <path d="M8 1.5l6.5 12H1.5L8 1.5z" stroke="currentColor" strokeWidth="1.5" strokeLinejoin="round" />
            <path d="M8 6v3" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" />
            <circle cx="8" cy="11.5" r="0.75" fill="currentColor" />
          </svg>
        </div>
        <div className="flex-1">
          <h3 className="text-sm font-semibold" style={{ color: 'var(--warning, #f59e0b)' }}>
            Approval Required
          </h3>
          <p className="text-xs font-medium" style={{ color: 'var(--fg, #ccc)' }}>
            {title}
          </p>
        </div>
      </div>

      {/* Description */}
      {description && (
        <div className="px-4 pb-2">
          <p className="text-xs leading-relaxed" style={{ color: 'var(--fg-secondary, #999)' }}>
            {description}
          </p>
        </div>
      )}

      {/* Command preview */}
      {commandPreview && (
        <div className="px-4 pb-3">
          <pre
            className="rounded-lg p-3 text-xs leading-relaxed"
            style={{
              backgroundColor: 'var(--code-bg, #111)',
              color: 'var(--fg, #ccc)',
              fontFamily: 'var(--font-mono)',
              border: '1px solid var(--divider-subtle, #333)',
              whiteSpace: 'pre-wrap',
              wordBreak: 'break-all',
            }}
          >
            {commandPreview}
          </pre>
        </div>
      )}

      {/* Action buttons */}
      <div
        className="flex items-center justify-end gap-2 border-t px-4 py-3"
        style={{ borderColor: 'color-mix(in srgb, var(--warning, #f59e0b) 20%, var(--divider-subtle, #333))' }}
      >
        <button
          onClick={handleDeny}
          className="rounded-lg px-4 py-2 text-xs font-medium transition-all duration-150 active:scale-[0.97] hover:brightness-110"
          style={{
            border: '1px solid var(--error, #ef4444)',
            color: 'var(--error, #ef4444)',
            backgroundColor: 'transparent',
          }}
        >
          Deny
        </button>
        <button
          onClick={handleApprove}
          className="rounded-lg px-4 py-2 text-xs font-semibold text-white transition-all duration-150 active:scale-[0.97] hover:brightness-110"
          style={{ backgroundColor: 'var(--success, #22c55e)' }}
        >
          Approve
        </button>
      </div>
    </div>
  );
}
