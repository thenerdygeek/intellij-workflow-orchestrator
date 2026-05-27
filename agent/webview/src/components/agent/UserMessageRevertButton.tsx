import { useEffect, useState } from 'react';
import { Check, X } from 'lucide-react';

interface Props { ts: number; }

// Inline two-step confirm. Native window.confirm() does NOT work inside the
// JCEF (embedded Chromium) webview — no CefJSDialogHandler is registered, so
// confirm() returns false and the revert silently never fired. We mirror the
// in-webview confirm affordance used by SessionCard's delete button instead.
export function UserMessageRevertButton({ ts }: Props) {
  const [confirming, setConfirming] = useState(false);

  // Auto-dismiss the confirm prompt so a stray click doesn't leave it hanging
  // open over the chat.
  useEffect(() => {
    if (!confirming) return;
    const id = setTimeout(() => setConfirming(false), 4000);
    return () => clearTimeout(id);
  }, [confirming]);

  if (confirming) {
    return (
      <div
        role="group"
        aria-label="Confirm checkpoint revert"
        className="flex items-center gap-2 px-2 py-1 rounded-md text-[11px] font-medium border shadow-sm"
        style={{ background: 'var(--toolbar-bg)', borderColor: 'var(--border)' }}
        title="Revert files & chat to this point, restore message to input"
      >
        <span style={{ color: 'var(--fg-secondary)' }}>Revert to here?</span>
        <button
          onClick={(e) => { e.stopPropagation(); setConfirming(false); window._revertToUserMessage?.(ts); }}
          className="flex items-center gap-1 hover:underline"
          style={{ color: 'var(--link)' }}
          aria-label="Confirm checkpoint revert"
        >
          <Check size={11} /> Confirm
        </button>
        <button
          onClick={(e) => { e.stopPropagation(); setConfirming(false); }}
          className="flex items-center gap-1 hover:underline"
          style={{ color: 'var(--fg-muted)' }}
          aria-label="Cancel checkpoint revert"
        >
          <X size={11} /> Cancel
        </button>
      </div>
    );
  }

  return (
    <button
      onClick={(e) => { e.stopPropagation(); setConfirming(true); }}
      className="px-3 py-1 rounded-md text-[11px] font-medium border shadow-sm transition-colors hover:bg-[var(--hover-overlay)]"
      style={{
        background: 'var(--toolbar-bg)',
        color: 'var(--link)',
        borderColor: 'var(--border)',
      }}
      aria-label="Checkpoint to here"
      title="Revert files & chat to this point, restore message to input"
    >
      ⟲ Checkpoint to here
    </button>
  );
}
