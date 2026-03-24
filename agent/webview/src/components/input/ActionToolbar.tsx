import { memo } from 'react';
import { Square, Undo2, Plus, Activity, Settings } from 'lucide-react';
import { useChatStore } from '@/stores/chatStore';
import { Button } from '@/components/ui/button';

interface ActionToolbarProps {
  isHovered: boolean;
}

export const ActionToolbar = memo(function ActionToolbar({ isHovered }: ActionToolbarProps) {
  const busy = useChatStore(s => s.busy);

  return (
    <div className={`flex items-center gap-0.5 px-1 py-0.5 transition-opacity duration-200 ${isHovered || busy ? 'opacity-100' : 'opacity-0'}`}>
      {busy && (
        <Button
          variant="ghost"
          size="sm"
          className="h-6 gap-1 px-2 text-[11px]"
          style={{ color: 'var(--error)' }}
          onClick={() => window._cancelTask?.()}
          title="Stop"
        >
          <Square className="h-3 w-3" fill="currentColor" />
          Stop
        </Button>
      )}
      {isHovered && (
        <>
          <Button variant="ghost" size="sm" className="h-6 gap-1 px-2 text-[11px]" onClick={() => window._requestUndo?.()} title="Undo">
            <Undo2 className="h-3 w-3" />
            Undo
          </Button>
          <Button variant="ghost" size="sm" className="h-6 gap-1 px-2 text-[11px]" onClick={() => window._newChat?.()} title="New">
            <Plus className="h-3 w-3" />
            New
          </Button>
          <div className="flex-1" />
          <Button variant="ghost" size="sm" className="h-6 gap-1 px-2 text-[11px]" onClick={() => window._requestViewTrace?.()} title="Traces">
            <Activity className="h-3 w-3" />
            Traces
          </Button>
          <Button variant="ghost" size="sm" className="h-6 gap-1 px-2 text-[11px]" onClick={() => window._openSettings?.()} title="Settings">
            <Settings className="h-3 w-3" />
            Settings
          </Button>
        </>
      )}
    </div>
  );
});
