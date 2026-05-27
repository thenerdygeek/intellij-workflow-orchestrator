/**
 * ToastStack — renders the chatStore `toasts` queue.
 *
 * Bug fix: `showToast`/`dismissToast` existed and were called from many places
 * (attachment validation, vision-disabled-at-send, etc.) but NO component
 * subscribed to `s.toasts`, so every toast was silently swallowed. This mounts
 * the missing renderer at the app root.
 */
import { memo } from 'react';
import { useChatStore } from '@/stores/chatStore';

function borderFor(type: string): string {
  if (type === 'error') return 'var(--error, #ef4444)';
  if (type === 'warning') return 'var(--warning, #d97706)';
  if (type === 'success') return 'var(--success, #22c55e)';
  return 'var(--border, #2c2f33)';
}

export const ToastStack = memo(function ToastStack() {
  const toasts = useChatStore(s => s.toasts);
  const dismissToast = useChatStore(s => s.dismissToast);
  if (toasts.length === 0) return null;

  return (
    <div
      className="pointer-events-none fixed bottom-3 right-3 z-50 flex flex-col gap-2"
      role="region"
      aria-label="Notifications"
    >
      {toasts.map(t => (
        <div
          key={t.id}
          role="alert"
          className="pointer-events-auto flex items-start gap-2 rounded-md px-3 py-2 text-xs shadow-lg"
          style={{
            maxWidth: 360,
            background: 'var(--card-bg, #2a2d31)',
            color: 'var(--fg, #cccccc)',
            border: `1px solid ${borderFor(t.type)}`,
          }}
        >
          <span className="flex-1 whitespace-pre-wrap break-words">{t.message}</span>
          <button
            type="button"
            aria-label="Dismiss notification"
            onClick={() => dismissToast(t.id)}
            className="shrink-0 opacity-60 transition-opacity hover:opacity-100"
            style={{ lineHeight: 1, fontSize: 13 }}
          >
            ×
          </button>
        </div>
      ))}
    </div>
  );
});
