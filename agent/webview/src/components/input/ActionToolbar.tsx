import { memo } from 'react';
import { useChatStore } from '@/stores/chatStore';

interface ActionToolbarProps {
  isHovered: boolean;
}

interface ToolbarButtonProps {
  label: string;
  onClick: () => void;
  icon: React.ReactNode;
  variant?: 'default' | 'danger';
  visible?: boolean;
}

function ToolbarButton({ label, onClick, icon, variant = 'default', visible = true }: ToolbarButtonProps) {
  if (!visible) return null;
  return (
    <button
      onClick={onClick}
      title={label}
      className={`flex items-center gap-1 rounded-md px-2 py-1 text-[11px] transition-all duration-150 active:scale-[0.97] ${variant === 'danger' ? 'text-[var(--error)] hover:bg-[var(--error)]/10' : 'text-[var(--fg-muted)] hover:bg-[var(--hover-overlay-strong)] hover:text-[var(--fg)]'}`}
    >
      {icon}
      <span>{label}</span>
    </button>
  );
}

export const ActionToolbar = memo(function ActionToolbar({ isHovered }: ActionToolbarProps) {
  const busy = useChatStore(s => s.busy);

  return (
    <div className={`flex items-center gap-0.5 px-1 py-0.5 transition-opacity duration-200 ${isHovered || busy ? 'opacity-100' : 'opacity-0'}`}>
      <ToolbarButton label="Stop" visible={busy} variant="danger" onClick={() => (window as any)._cancelTask?.()} icon={<svg width="12" height="12" viewBox="0 0 16 16" fill="currentColor"><rect x="3" y="3" width="10" height="10" rx="1" /></svg>} />
      <ToolbarButton label="Undo" visible={isHovered} onClick={() => (window as any)._requestUndo?.()} icon={<svg width="12" height="12" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5"><path d="M3 7h7a3 3 0 1 1 0 6H9" strokeLinecap="round" strokeLinejoin="round" /><path d="M6 4L3 7l3 3" strokeLinecap="round" strokeLinejoin="round" /></svg>} />
      <ToolbarButton label="New" visible={isHovered} onClick={() => (window as any)._newChat?.()} icon={<svg width="12" height="12" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5"><path d="M8 3v10M3 8h10" strokeLinecap="round" /></svg>} />
      {isHovered && <div className="flex-1" />}
      <ToolbarButton label="Traces" visible={isHovered} onClick={() => (window as any)._requestViewTrace?.()} icon={<svg width="12" height="12" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5"><path d="M2 12l4-4 3 3 5-7" strokeLinecap="round" strokeLinejoin="round" /></svg>} />
      <ToolbarButton label="Settings" visible={isHovered} onClick={() => (window as any)._openSettings?.()} icon={<svg width="12" height="12" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5"><circle cx="8" cy="8" r="2.5" /><path d="M8 1.5v2M8 12.5v2M1.5 8h2M12.5 8h2M3.1 3.1l1.4 1.4M11.5 11.5l1.4 1.4M3.1 12.9l1.4-1.4M11.5 4.5l1.4-1.4" /></svg>} />
    </div>
  );
});
