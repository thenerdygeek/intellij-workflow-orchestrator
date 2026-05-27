/**
 * CopyButton — reusable clipboard copy with visual feedback.
 *
 * Shows a copy icon that transitions to a checkmark for 2 seconds after
 * a successful copy. Supports two sizes and an optional hover-only mode.
 */

import { useState, useCallback, useEffect, useRef, type CSSProperties } from 'react';
import { cn } from '@/lib/utils';
import { kotlinBridge, isJcefEnvironment } from '@/bridge/jcef-bridge';

type CopyButtonSize = 'sm' | 'md';

interface CopyButtonProps {
  /** The text to copy to the clipboard. */
  text: string;
  /** Icon size: sm = 12px, md = 14px. Defaults to 'md'. */
  size?: CopyButtonSize;
  /** If true, the button is invisible until a parent with `group` class is hovered. */
  hoverOnly?: boolean;
  /** Extra CSS classes on the outer button element. */
  className?: string;
  /** Extra inline styles. */
  style?: CSSProperties;
  /** Tooltip text when idle. Defaults to 'Copy'. */
  label?: string;
}

const ICON_SIZE: Record<CopyButtonSize, number> = { sm: 12, md: 14 };

export function CopyButton({
  text,
  size = 'md',
  hoverOnly = false,
  className,
  style,
  label = 'Copy',
}: CopyButtonProps) {
  const [copied, setCopied] = useState(false);
  const timeoutRef = useRef<ReturnType<typeof setTimeout> | null>(null);

  useEffect(() => () => {
    if (timeoutRef.current != null) clearTimeout(timeoutRef.current);
  }, []);

  const handleCopy = useCallback(() => {
    if (timeoutRef.current != null) clearTimeout(timeoutRef.current);
    const markCopied = () => {
      setCopied(true);
      timeoutRef.current = setTimeout(() => setCopied(false), 2000);
    };
    if (isJcefEnvironment()) {
      // Fire-and-forget bridge call — guard so a disposed panel / bridge error
      // doesn't throw out of the click handler and wedge the checkmark.
      try {
        kotlinBridge.copyToClipboard(text);
        markCopied();
      } catch (e) {
        console.warn('[CopyButton] clipboard bridge copy failed', e);
      }
    } else {
      // navigator.clipboard.writeText rejects on lost focus / denied permission;
      // a missing .catch leaks an unhandled rejection. Swallow + log instead.
      navigator.clipboard.writeText(text).then(markCopied).catch(e => {
        console.warn('[CopyButton] clipboard write failed', e);
      });
    }
  }, [text]);

  const s = ICON_SIZE[size];

  return (
    <button
      onClick={handleCopy}
      className={cn(
        'rounded p-1 transition-all text-[var(--fg-muted)] hover:bg-[var(--hover-overlay)] hover:text-[var(--fg)]',
        hoverOnly && 'opacity-0 group-hover:opacity-100',
        copied && 'text-[var(--success)]',
        className,
      )}
      style={style}
      title={copied ? 'Copied!' : label}
      aria-label={copied ? 'Copied' : label}
    >
      {copied ? (
        <svg width={s} height={s} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
          <polyline points="20 6 9 17 4 12" />
        </svg>
      ) : (
        <svg width={s} height={s} viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
          <rect x="9" y="9" width="13" height="13" rx="2" ry="2" />
          <path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1" />
        </svg>
      )}
    </button>
  );
}
