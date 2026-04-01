import { memo } from 'react';
import { useChatStore } from '@/stores/chatStore';
import { Sparkles } from 'lucide-react';

interface SkillDropdownProps {
  query: string;
  onSelect: (skillName: string) => void;
  onDismiss: () => void;
  selectedIndex: number;
  setSelectedIndex: (i: number) => void;
  listRef: React.RefObject<HTMLDivElement>;
}

export const SkillDropdown = memo(function SkillDropdown({
  query,
  onSelect,
  onDismiss: _onDismiss,
  selectedIndex,
  setSelectedIndex,
  listRef,
}: SkillDropdownProps) {
  const skillsList = useChatStore(s => s.skillsList);

  // Filter skills by query — client-side since the list is small
  const filtered = query
    ? skillsList.filter(
        s =>
          s.name.toLowerCase().includes(query.toLowerCase()) ||
          s.description?.toLowerCase().includes(query.toLowerCase())
      )
    : skillsList;

  const isEmpty = filtered.length === 0;

  return (
    <div className="absolute bottom-full left-0 mb-1 min-w-[420px] max-w-[560px] w-max z-50">
      <div
        className="rounded-lg overflow-hidden"
        style={{
          backgroundColor: 'var(--surface-elevated, var(--toolbar-bg, var(--popover)))',
          border: '1px solid var(--border)',
          boxShadow: '0 8px 30px rgba(0, 0, 0, 0.5), 0 2px 8px rgba(0, 0, 0, 0.3)',
        }}
      >
        {/* Group heading */}
        <div
          className="px-3 py-1.5 text-[10px] font-medium uppercase tracking-wider border-b"
          style={{ color: 'var(--fg-muted)', borderColor: 'var(--border)' }}
        >
          Skills
        </div>

        {isEmpty ? (
          <div
            className="text-xs py-4 text-center"
            style={{ color: 'var(--fg-muted)' }}
          >
            No skills found.
          </div>
        ) : (
          <div
            ref={listRef}
            className="max-h-60 overflow-y-auto p-1"
            style={{ scrollbarWidth: 'thin' }}
          >
            {filtered.map((skill, i) => {
              const highlighted = i === selectedIndex;
              return (
                <div
                  key={skill.name}
                  data-highlighted={highlighted ? 'true' : undefined}
                  onClick={() => onSelect(skill.name)}
                  onMouseEnter={() => setSelectedIndex(i)}
                  className="flex items-center gap-2 px-2 py-1.5 rounded text-xs cursor-default"
                  style={{
                    backgroundColor: highlighted
                      ? 'var(--hover-overlay, rgba(255,255,255,0.08))'
                      : 'transparent',
                    borderLeft: highlighted
                      ? '2px solid var(--accent, #6366f1)'
                      : '2px solid transparent',
                    transition: 'background-color 0.1s ease, border-color 0.1s ease',
                    fontWeight: highlighted ? 500 : 400,
                  }}
                >
                  <Sparkles
                    className="size-3.5 shrink-0"
                    style={{ color: 'var(--accent-edit, #f59e0b)' }}
                  />
                  <span className="font-mono">/{skill.name}</span>
                  {skill.description && (
                    <span
                      className="ml-auto text-[10px] truncate max-w-[280px]"
                      style={{ color: 'var(--fg-muted)' }}
                    >
                      {skill.description}
                    </span>
                  )}
                </div>
              );
            })}
          </div>
        )}
      </div>
    </div>
  );
});

export type { SkillDropdownProps };
