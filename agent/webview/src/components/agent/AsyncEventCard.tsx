import { useState } from 'react';
import type { UiMessageAsyncEventData, AsyncEventStatus } from '@/bridge/types';

const STATUS_COLOR: Record<AsyncEventStatus, string> = {
  SUCCESS: 'var(--accent-write, #22c55e)',
  FAILURE: 'var(--accent-edit, #f59e0b)',
  NOTABLE: 'var(--fg-secondary, #9ca3af)',
  ALERT: 'var(--error, #ef4444)',
};

function BgIcon() {
  return (
    <svg width="11" height="11" viewBox="0 0 16 16" fill="none" aria-hidden>
      <circle cx="8" cy="8" r="2.2" stroke="currentColor" strokeWidth="1.3" />
      <path d="M8 1v2M8 13v2M1 8h2M13 8h2M3 3l1.4 1.4M11.6 11.6L13 13M13 3l-1.4 1.4M4.4 11.6L3 13"
        stroke="currentColor" strokeWidth="1.3" strokeLinecap="round" />
    </svg>
  );
}

function MonIcon() {
  return (
    <svg width="11" height="11" viewBox="0 0 16 16" fill="none" aria-hidden>
      <path d="M1 8s2.5-4.5 7-4.5S15 8 15 8s-2.5 4.5-7 4.5S1 8 1 8Z" stroke="currentColor" strokeWidth="1.3" />
      <circle cx="8" cy="8" r="1.8" stroke="currentColor" strokeWidth="1.3" />
    </svg>
  );
}

export function AsyncEventCard({ data }: { data: UiMessageAsyncEventData }) {
  const [open, setOpen] = useState(false);
  const color = STATUS_COLOR[data.status];
  const badge = data.kind === 'BACKGROUND' ? 'Background' : 'Monitor';
  return (
    <div
      className="rounded-md px-2 py-1 my-0.5 animate-[fade-in_200ms_ease-out]"
      style={{
        background: 'color-mix(in srgb, var(--user-bg, #2a2a2a) 50%, transparent)',
        border: '1px solid var(--border, #333)',
      }}
    >
      <button
        type="button"
        onClick={() => setOpen(o => !o)}
        className="flex items-center gap-1.5 w-full text-left"
        style={{ color: 'var(--fg-secondary, #9ca3af)' }}
        aria-expanded={open}
      >
        <span style={{ color }} className="flex items-center">{data.kind === 'BACKGROUND' ? <BgIcon /> : <MonIcon />}</span>
        <span className="text-[11px] font-medium">{badge}</span>
        <span className="text-[11px] font-mono opacity-70">{data.sourceId}</span>
        <span className="inline-block size-1.5 rounded-full" style={{ background: color }} />
        <span className="text-[11px] truncate flex-1">{data.summary}</span>
        <span className="text-[10px] opacity-60">{open ? '▾' : '▸'}</span>
      </button>
      <div className="text-[11px] font-mono opacity-80 pl-[18px] truncate" style={{ color: 'var(--fg-muted, #888)' }}>
        {data.label}
      </div>
      {open && (
        <pre
          className="text-[11px] font-mono mt-1 p-1.5 rounded overflow-x-auto whitespace-pre-wrap"
          style={{ background: 'var(--code-bg, rgba(0,0,0,0.2))', color: 'var(--fg, #ddd)', maxHeight: 240 }}
        >
          {data.details || '(no output)'}
          {data.spillPath ? `\n\nFull output: ${data.spillPath}` : ''}
        </pre>
      )}
    </div>
  );
}
