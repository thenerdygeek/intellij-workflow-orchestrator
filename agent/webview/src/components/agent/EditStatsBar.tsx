import type { EditStats } from '@/bridge/types';

interface EditStatsBarProps {
  stats: EditStats | null;
}

export function EditStatsBar({ stats }: EditStatsBarProps) {
  if (!stats || (stats.totalLinesAdded === 0 && stats.totalLinesRemoved === 0)) return null;

  return (
    <div
      className="flex items-center gap-3 px-4 py-1.5 text-[11px] font-mono border-t"
      style={{ borderColor: 'var(--border)', background: 'var(--toolbar-bg)' }}
    >
      <span style={{ color: 'var(--diff-add-fg, #b5cea8)' }}>+{stats.totalLinesAdded}</span>
      <span style={{ color: 'var(--diff-rem-fg, #f4a5a5)' }}>-{stats.totalLinesRemoved}</span>
      <span className="w-px h-3" style={{ background: 'var(--border)' }} />
      <span style={{ color: 'var(--fg-muted)' }}>{stats.filesModified} file{stats.filesModified !== 1 ? 's' : ''}</span>
    </div>
  );
}
