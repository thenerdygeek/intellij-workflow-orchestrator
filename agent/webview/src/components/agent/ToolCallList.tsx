import { useChatStore } from '@/stores/chatStore';
import { ToolCallCard } from './ToolCallCard';

export function ToolCallList() {
  const activeToolCalls = useChatStore(s => s.activeToolCalls);
  const entries = Array.from(activeToolCalls.entries());
  if (entries.length === 0) return null;

  return (
    <div className="px-4">
      {entries.map(([key, toolCall], index) => (
        <ToolCallCard
          key={key}
          toolCall={toolCall}
          isLatest={index === entries.length - 1}
        />
      ))}
    </div>
  );
}
