import { memo, useCallback } from 'react';
import { useChatStore } from '@/stores/chatStore';
import {
  Command,
  CommandInput,
  CommandList,
  CommandEmpty,
  CommandGroup,
  CommandItem,
} from '@/components/ui/command';
import { Sparkles } from 'lucide-react';

interface SkillDropdownProps {
  query: string;
  onSelect: (skillName: string) => void;
  onDismiss: () => void;
}

export const SkillDropdown = memo(function SkillDropdown({
  query,
  onSelect,
  onDismiss: _onDismiss,
}: SkillDropdownProps) {
  const skillsList = useChatStore(s => s.skillsList);

  // Filter skills by query (client-side since the list is small)
  const filtered = query
    ? skillsList.filter(s =>
        s.name.toLowerCase().includes(query.toLowerCase()) ||
        s.description?.toLowerCase().includes(query.toLowerCase())
      )
    : skillsList;

  const handleSelect = useCallback((value: string) => {
    onSelect(value);
  }, [onSelect]);

  return (
    <div className="absolute bottom-full left-0 mb-1 w-80 z-50">
      <Command
        shouldFilter={false}
        className="rounded-lg"
        style={{
          backgroundColor: 'var(--surface-elevated, var(--toolbar-bg, var(--popover)))',
          border: '1px solid var(--border)',
          boxShadow: '0 8px 30px rgba(0, 0, 0, 0.5), 0 2px 8px rgba(0, 0, 0, 0.3)',
        }}
      >
        <CommandInput
          placeholder="Search skills..."
          value={query}
          className="text-xs"
        />
        <CommandList className="max-h-60">
          <CommandEmpty className="text-xs py-4 text-center" style={{ color: 'var(--fg-muted)' }}>
            No skills found.
          </CommandEmpty>
          <CommandGroup heading="Skills">
            {filtered.map((skill) => (
              <CommandItem
                key={skill.name}
                value={skill.name}
                onSelect={handleSelect}
                className="text-xs gap-2"
              >
                <Sparkles className="size-3.5 shrink-0" style={{ color: 'var(--accent-edit, #f59e0b)' }} />
                <span className="font-mono">/{skill.name}</span>
                {skill.description && (
                  <span className="ml-auto text-[10px] truncate max-w-[160px]" style={{ color: 'var(--fg-muted)' }}>
                    {skill.description}
                  </span>
                )}
              </CommandItem>
            ))}
          </CommandGroup>
        </CommandList>
      </Command>
    </div>
  );
});
