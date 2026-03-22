import { memo } from 'react';
import type { Message } from '@/bridge/types';

interface MessageCardProps {
  message: Message;
  isStreaming?: boolean;
  streamText?: string;
}

export const MessageCard = memo(function MessageCard({
  message,
  isStreaming = false,
  streamText,
}: MessageCardProps) {
  const isUser = message.role === 'user';
  const content = isStreaming ? (streamText ?? message.content) : message.content;

  if (message.role === 'system') {
    return null;
  }

  return (
    <div
      className={`
        group relative flex w-full
        ${isUser ? 'justify-end' : 'justify-start'}
        animate-[message-enter_220ms_ease-out_both]
      `}
    >
      <div
        className={`
          relative max-w-[85%] rounded-lg px-4 py-3
          ${isUser
            ? 'bg-[var(--user-bg)] text-[var(--fg)]'
            : 'bg-transparent text-[var(--fg)]'
          }
        `}
      >
        {!isUser && (
          <div className="mb-1.5 flex items-center gap-2">
            <div className="flex h-5 w-5 items-center justify-center rounded-full bg-[var(--accent,#6366f1)] text-[10px] font-bold text-white">
              A
            </div>
            <span className="text-[11px] font-medium text-[var(--fg-muted)]">
              Agent
            </span>
          </div>
        )}

        <div className="whitespace-pre-wrap break-words text-[13px] leading-relaxed">
          {content}
        </div>

        {isStreaming && (
          <span className="inline-block h-4 w-[2px] translate-y-[2px] bg-[var(--fg)] animate-[cursor-blink_530ms_step-end_infinite]" />
        )}

        <div className="mt-1 opacity-0 transition-opacity duration-200 group-hover:opacity-100">
          <span className="text-[10px] text-[var(--fg-muted)]">
            {new Date(message.timestamp).toLocaleTimeString([], {
              hour: '2-digit',
              minute: '2-digit',
            })}
          </span>
        </div>
      </div>
    </div>
  );
});
