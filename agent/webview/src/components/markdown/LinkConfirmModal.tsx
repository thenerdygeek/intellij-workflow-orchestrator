import { useEffect, useRef, useState } from 'react';
import {
  Dialog,
  DialogContent,
  DialogDescription,
  DialogFooter,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';
import { Button } from '@/components/ui/button';

type LinkKind = 'FILE' | 'CLASS' | 'JIRA' | 'WEB' | 'UNKNOWN';

interface Resolution {
  kind: LinkKind;
  raw: string;
  displayLabel: string;
  targetDescription: string;
  /** Resolved external URL (JIRA → …/browse/TICKET, WEB → the URL). Null/absent for non-browser links. */
  browserUrl?: string | null;
}

interface LinkConfirmModalProps {
  href: string;
  onClose: () => void;
}

const KIND_BADGE: Record<LinkKind, { label: string; tone: string }> = {
  FILE: { label: 'File', tone: 'bg-blue-500/15 text-blue-400' },
  CLASS: { label: 'Class', tone: 'bg-purple-500/15 text-purple-400' },
  JIRA: { label: 'Jira', tone: 'bg-amber-500/15 text-amber-400' },
  WEB: { label: 'Web', tone: 'bg-emerald-500/15 text-emerald-400' },
  UNKNOWN: { label: 'Unknown', tone: 'bg-zinc-500/15 text-zinc-400' },
};

export function LinkConfirmModal({ href, onClose }: LinkConfirmModalProps) {
  const [res, setRes] = useState<Resolution | null>(null);
  const [copied, setCopied] = useState(false);
  const cancelRef = useRef<HTMLButtonElement | null>(null);

  useEffect(() => {
    let alive = true;
    const fetchResolution = async () => {
      try {
        const bridge = (window as unknown as {
          _resolveLink?: (h: string) => Promise<string>;
        })._resolveLink;
        if (!bridge) {
          if (alive) {
            setRes({
              kind: 'UNKNOWN',
              raw: href,
              displayLabel: href,
              targetDescription: 'Resolver bridge unavailable',
            });
          }
          return;
        }
        const raw = await bridge(href);
        if (!alive) return;
        try {
          setRes(JSON.parse(raw) as Resolution);
        } catch {
          setRes({
            kind: 'UNKNOWN',
            raw: href,
            displayLabel: href,
            targetDescription: 'Could not parse resolver response',
          });
        }
      } catch {
        if (alive) {
          setRes({
            kind: 'UNKNOWN',
            raw: href,
            displayLabel: href,
            targetDescription: 'Could not resolve',
          });
        }
      }
    };
    fetchResolution();
    return () => {
      alive = false;
    };
  }, [href]);

  // Focus the Cancel button after the dialog mounts so Enter/Space defaults to
  // the safe action rather than opening the link.
  useEffect(() => {
    const t = window.setTimeout(() => cancelRef.current?.focus(), 0);
    return () => window.clearTimeout(t);
  }, []);

  const handleOpen = () => {
    const bridge = (window as unknown as { _openLink?: (h: string) => void })._openLink;
    if (bridge) {
      try {
        bridge(href);
      } catch {
        // fire-and-forget; ignore
      }
    }
    onClose();
  };

  // The user-facing URL: the resolved external link (e.g. https://jira…/browse/WORK-1
  // for a jira: href), falling back to the raw href only when the resolver gave none.
  const copyUrl = res?.browserUrl ?? res?.raw ?? href;

  const handleCopy = async () => {
    try {
      if (navigator.clipboard && navigator.clipboard.writeText) {
        await navigator.clipboard.writeText(copyUrl);
      } else {
        const copyBridge = (window as unknown as {
          _copyToClipboard?: (s: string) => void;
        })._copyToClipboard;
        copyBridge?.(copyUrl);
      }
      setCopied(true);
      window.setTimeout(onClose, 350);
    } catch {
      // Fallback: route through Kotlin clipboard bridge if JCEF restricts navigator.clipboard.
      const copyBridge = (window as unknown as {
        _copyToClipboard?: (s: string) => void;
      })._copyToClipboard;
      copyBridge?.(copyUrl);
      onClose();
    }
  };

  const kind: LinkKind = res?.kind ?? 'UNKNOWN';
  const badge = KIND_BADGE[kind];
  const description = res?.targetDescription ?? 'Resolving link…';
  const label = res?.displayLabel ?? href;

  return (
    <Dialog
      open
      onOpenChange={(open) => {
        if (!open) onClose();
      }}
    >
      <DialogContent
        className="sm:max-w-md gap-3"
        onEscapeKeyDown={() => onClose()}
        onPointerDownOutside={() => onClose()}
      >
        <DialogHeader>
          <div className="flex items-center gap-2">
            <span
              className={`inline-flex h-5 items-center rounded px-1.5 text-[10px] font-medium uppercase tracking-wide ${badge.tone}`}
            >
              {badge.label}
            </span>
            <DialogTitle className="text-base">Open in browser?</DialogTitle>
          </div>
          <DialogDescription className="text-[12px]">
            {description}
          </DialogDescription>
        </DialogHeader>

        <div className="space-y-2 text-[12px]">
          {label !== copyUrl && (
            <div>
              <div className="text-[10px] uppercase tracking-wide text-muted-foreground">
                Label
              </div>
              <div className="break-all text-[13px]">{label}</div>
            </div>
          )}
          <div>
            <div className="text-[10px] uppercase tracking-wide text-muted-foreground">
              URL
            </div>
            <div
              className="break-all rounded border bg-[var(--code-bg,#1e1e1e)] p-2 font-mono text-[11px]"
              style={{ wordBreak: 'break-all' }}
            >
              {copyUrl}
            </div>
          </div>
        </div>

        <DialogFooter className="gap-2">
          <Button
            ref={cancelRef}
            variant="ghost"
            size="sm"
            onClick={onClose}
          >
            Cancel
          </Button>
          <Button variant="outline" size="sm" onClick={handleCopy}>
            {copied ? 'Copied' : 'Copy URL'}
          </Button>
          <Button variant="default" size="sm" onClick={handleOpen}>
            Open Link
          </Button>
        </DialogFooter>
      </DialogContent>
    </Dialog>
  );
}
