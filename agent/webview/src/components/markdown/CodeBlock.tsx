import { memo, useState, useCallback } from 'react';
import { useShiki } from '@/hooks/useShiki';

interface CodeBlockProps {
  code: string;
  language: string;
  isStreaming?: boolean;
}

export const CodeBlock = memo(function CodeBlock({
  code,
  language,
  isStreaming = false,
}: CodeBlockProps) {
  const { html, isLoading } = useShiki(code, language);
  const [copied, setCopied] = useState(false);
  const [showLineNumbers, setShowLineNumbers] = useState(false);

  const handleCopy = useCallback(() => {
    navigator.clipboard.writeText(code).then(() => {
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    });
  }, [code]);

  const handleApply = useCallback(() => {
    // Send apply action to IDE via bridge
    /* eslint-disable @typescript-eslint/no-explicit-any */
    (window as any)._applyCode?.(code, language);
    /* eslint-enable @typescript-eslint/no-explicit-any */
  }, [code, language]);

  const lines = code.split('\n');

  // Streaming skeleton for open code fences
  if (isStreaming && !code.trim()) {
    return (
      <div className="relative my-2 rounded-md border border-[var(--border)] bg-[var(--code-bg)] overflow-hidden">
        <div className="p-3 space-y-2">
          <span className="block h-3 w-3/4 animate-pulse rounded bg-[var(--fg-muted)]/20" />
          <span className="block h-3 w-1/2 animate-pulse rounded bg-[var(--fg-muted)]/20" />
          <span className="block h-3 w-2/3 animate-pulse rounded bg-[var(--fg-muted)]/20" />
        </div>
      </div>
    );
  }

  return (
    <div className="relative my-2 rounded-md border border-[var(--border)] bg-[var(--code-bg)] overflow-hidden">
      {/* Header */}
      <div className="flex items-center justify-between border-b border-[var(--border)] px-3 py-1">
        <span className="text-[10px] font-medium uppercase text-[var(--fg-muted)]">
          {language || 'code'}
        </span>
        <div className="flex items-center gap-1">
          {/* Line numbers toggle */}
          <button
            onClick={() => setShowLineNumbers(!showLineNumbers)}
            className="rounded p-1 text-[var(--fg-muted)] hover:bg-[var(--hover-bg)] hover:text-[var(--fg)]"
            title="Toggle line numbers"
            aria-label="Toggle line numbers"
          >
            <span className="text-[10px] font-mono font-bold">#</span>
          </button>

          {/* Copy button */}
          <button
            onClick={handleCopy}
            className="rounded p-1 text-[var(--fg-muted)] hover:bg-[var(--hover-bg)] hover:text-[var(--fg)]"
            title={copied ? 'Copied!' : 'Copy code'}
            aria-label={copied ? 'Copied' : 'Copy code'}
          >
            {copied ? (
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                <polyline points="20 6 9 17 4 12" />
              </svg>
            ) : (
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                <rect x="9" y="9" width="13" height="13" rx="2" ry="2" />
                <path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1" />
              </svg>
            )}
          </button>

          {/* Apply button */}
          <button
            onClick={handleApply}
            className="rounded p-1 text-[var(--fg-muted)] hover:bg-[var(--hover-bg)] hover:text-[var(--fg)]"
            title="Apply code"
            aria-label="Apply code"
          >
            <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
              <path d="M12 5v14" />
              <path d="m19 12-7 7-7-7" />
            </svg>
          </button>
        </div>
      </div>

      {/* Code content */}
      {isLoading ? (
        <div className="p-3 space-y-2">
          <span className="block h-3 w-3/4 animate-pulse rounded bg-[var(--fg-muted)]/20" />
          <span className="block h-3 w-1/2 animate-pulse rounded bg-[var(--fg-muted)]/20" />
        </div>
      ) : (
        <div className="flex overflow-x-auto">
          {showLineNumbers && (
            <div className="flex-shrink-0 select-none border-r border-[var(--border)] bg-[var(--code-bg)] px-2 py-3 text-right">
              {lines.map((_, i) => (
                <div
                  key={i}
                  className="font-[var(--font-mono,'JetBrains_Mono',monospace)] text-[11px] leading-[1.6] text-[var(--fg-muted)]/40"
                >
                  {i + 1}
                </div>
              ))}
            </div>
          )}
          <div
            className="min-w-0 flex-1 overflow-x-auto p-3 [&_pre]:!m-0 [&_pre]:!bg-transparent [&_pre]:!p-0 [&_code]:font-[var(--font-mono,'JetBrains_Mono',monospace)] [&_code]:text-[12px] [&_code]:leading-[1.6]"
            dangerouslySetInnerHTML={{ __html: html }}
          />
        </div>
      )}
    </div>
  );
});
