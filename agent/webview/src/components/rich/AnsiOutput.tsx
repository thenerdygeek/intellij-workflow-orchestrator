import { useMemo } from 'react';
import { AnsiUp } from 'ansi_up';
import { CopyButton } from '@/components/ui/copy-button';

// ── Singleton AnsiUp instance ──

const ansiUp = new AnsiUp();
ansiUp.use_classes = false;

// ── Props ──

interface AnsiOutputProps {
  text: string;
}

// eslint-disable-next-line no-control-regex
const ANSI_RE = /\x1B\[[0-9;]*[a-zA-Z]/g;

export function AnsiOutput({ text }: AnsiOutputProps) {
  const html = useMemo(() => ansiUp.ansi_to_html(text), [text]);
  const strippedText = useMemo(() => text.replace(ANSI_RE, ''), [text]);

  return (
    <div className="my-2 overflow-hidden rounded-lg border border-[var(--border)]">
      {/* Header */}
      <div className="flex items-center justify-between border-b border-[var(--border)] bg-[var(--toolbar-bg)] px-3 py-1.5">
        <div className="flex items-center gap-2">
          <svg
            width="12"
            height="12"
            viewBox="0 0 12 12"
            fill="none"
            className="text-[var(--fg-muted)]"
          >
            <rect
              x="1"
              y="1"
              width="10"
              height="10"
              rx="1.5"
              stroke="currentColor"
              strokeWidth="1.2"
            />
            <path d="M3 5l1.5 1.5L3 8" stroke="currentColor" strokeWidth="1.2" strokeLinecap="round" strokeLinejoin="round" />
            <path d="M6 8h3" stroke="currentColor" strokeWidth="1.2" strokeLinecap="round" />
          </svg>
          <span className="text-[11px] font-medium text-[var(--fg-secondary)]">
            Terminal Output
          </span>
        </div>
        <CopyButton text={strippedText} size="sm" label="Copy output" />
      </div>

      {/* Output area */}
      <pre
        className="max-h-[400px] overflow-auto bg-[var(--code-bg,var(--bg))] p-3 font-[var(--font-mono,'JetBrains_Mono',monospace)] text-[12px] leading-[1.6] text-[var(--fg)]"
        dangerouslySetInnerHTML={{ __html: html }}
      />
    </div>
  );
}
