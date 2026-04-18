import { memo } from 'react';
import { MarkdownRenderer } from '@/components/markdown/MarkdownRenderer';
import { CopyButton } from '@/components/ui/copy-button';
import type { CompletionData } from '@/bridge/types';

interface CompletionCardProps {
  data: CompletionData;
}

function DoneIcon() {
  return (
    <svg width="16" height="16" viewBox="0 0 16 16" fill="none" xmlns="http://www.w3.org/2000/svg">
      <circle cx="8" cy="8" r="7" stroke="var(--success, #22C55E)" strokeWidth="1.5" fill="color-mix(in srgb, var(--success, #22C55E) 15%, transparent)" />
      <path d="M5 8l2 2 4-4" stroke="var(--success, #22C55E)" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" />
    </svg>
  );
}

function ReviewIcon() {
  return (
    <svg width="16" height="16" viewBox="0 0 16 16" fill="none" xmlns="http://www.w3.org/2000/svg">
      <path d="M1 8s3-5 7-5 7 5 7 5-3 5-7 5-7-5-7-5z" stroke="var(--warning, #F59E0B)" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" />
      <circle cx="8" cy="8" r="2.5" stroke="var(--warning, #F59E0B)" strokeWidth="1.5" />
    </svg>
  );
}

function HeadsUpIcon() {
  return (
    <svg width="16" height="16" viewBox="0 0 16 16" fill="none" xmlns="http://www.w3.org/2000/svg">
      <path d="M13 2L4.5 9H10L3 14l8.5-7H6L13 2z" stroke="var(--info, #3B82F6)" strokeWidth="1.5" strokeLinecap="round" strokeLinejoin="round" fill="color-mix(in srgb, var(--info, #3B82F6) 15%, transparent)" />
    </svg>
  );
}

function VerifyPill({ command }: { command: string }) {
  return (
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
        {command}
      </code>
      <CopyButton text={command} size="sm" label="Copy command" />
    </div>
  );
}

export const CompletionCard = memo(function CompletionCard({ data }: CompletionCardProps) {
  const accent =
    data.kind === 'done'
      ? 'var(--success, #22C55E)'
      : data.kind === 'review'
        ? 'var(--warning, #F59E0B)'
        : 'var(--info, #3B82F6)';

  const label =
    data.kind === 'done'
      ? 'Task Completed'
      : data.kind === 'review'
        ? 'Please Review'
        : 'Heads Up';

  const Icon =
    data.kind === 'done'
      ? DoneIcon
      : data.kind === 'review'
        ? ReviewIcon
        : HeadsUpIcon;

  return (
    <div
      className="group relative rounded-lg overflow-hidden animate-[message-enter_300ms_ease-out_both]"
      style={{
        border: `1px solid color-mix(in srgb, ${accent} 30%, var(--border, #333))`,
        background: `linear-gradient(
          135deg,
          color-mix(in srgb, ${accent} 4%, var(--bg, #1e1e1e)) 0%,
          var(--bg, #1e1e1e) 100%
        )`,
      }}
    >
      {/* Header bar */}
      <div
        className="flex items-center gap-2 px-4 py-2.5"
        style={{
          borderBottom: `1px solid color-mix(in srgb, ${accent} 15%, var(--border, #333))`,
          background: `color-mix(in srgb, ${accent} 6%, transparent)`,
        }}
      >
        <Icon />
        <span
          className="text-[12px] font-semibold tracking-wide uppercase"
          style={{ color: accent }}
        >
          {label}
        </span>
        <CopyButton text={data.result} size="sm" hoverOnly className="ml-auto" label="Copy result" />
      </div>

      {/* review kind: verify pill ABOVE body (prominent CTA) */}
      {data.kind === 'review' && data.verifyHow && (
        <div className="pt-3">
          <VerifyPill command={data.verifyHow} />
        </div>
      )}

      {/* Body */}
      <div className="px-4 py-3 text-[13px]" style={{ color: 'var(--fg)' }}>
        <MarkdownRenderer content={data.result} isStreaming={false} />
      </div>

      {/* heads_up kind: discovery callout below body */}
      {data.kind === 'heads_up' && (
        <div
          style={{
            background: 'color-mix(in srgb, var(--info, #3B82F6) 8%, var(--bg))',
            border: '1px solid color-mix(in srgb, var(--info, #3B82F6) 25%, var(--border))',
            borderRadius: 6,
            padding: '8px 12px',
            margin: '0 16px 12px',
          }}
        >
          <span style={{ fontSize: 11, fontWeight: 600, color: 'var(--info, #3B82F6)', textTransform: 'uppercase', letterSpacing: '0.04em' }}>Discovery</span>
          <p style={{ margin: '4px 0 0', fontSize: 13, color: 'var(--fg)' }}>{data.discovery}</p>
        </div>
      )}

      {/* done / heads_up kind: verify pill BELOW body */}
      {data.kind !== 'review' && data.verifyHow && (
        <VerifyPill command={data.verifyHow} />
      )}
    </div>
  );
});
