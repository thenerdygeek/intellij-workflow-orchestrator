import { memo } from 'react';
import type { Message } from '@/bridge/types';
import { MarkdownRenderer } from '@/components/markdown/MarkdownRenderer';
import { ThinkingView } from '@/components/agent/ThinkingView';
import {
  Message as PkMessage,
  MessageAvatar,
} from '@/components/ui/prompt-kit/message';
import { Loader } from '@/components/ui/prompt-kit/loader';
import { cn } from '@/lib/utils';

interface AgentMessageProps {
  message: Message;
  isStreaming?: boolean;
  streamText?: string;
}

export const AgentMessage = memo(function AgentMessage({
  message,
  isStreaming = false,
  streamText,
}: AgentMessageProps) {
  const isUser = message.role === 'user';
  const content = isStreaming ? (streamText ?? message.content) : message.content;

  // System messages: thinking blocks and status lines
  if (message.role === 'system') {
    try {
      const parsed = JSON.parse(message.content) as { type?: string; text?: string; message?: string };
      if (parsed.type === 'thinking') {
        return <ThinkingView content={parsed.text ?? ''} isStreaming={false} />;
      }
      if (parsed.type === 'status') {
        return (
          <div className="px-1 py-0.5 text-[11px]" style={{ color: 'var(--fg-muted, #888)' }}>
            {parsed.message}
          </div>
        );
      }
    } catch {
      // not JSON — fall through to null
    }
    return null;
  }

  return (
    <PkMessage
      className={cn(
        'group w-full animate-[message-enter_220ms_ease-out_both]',
        isUser ? 'flex-row-reverse' : '',
      )}
    >
      {/* Agent avatar (non-user messages only) */}
      {!isUser && (
        <MessageAvatar
          fallback="A"
          className="h-5 w-5 bg-[var(--accent,#6366f1)] text-[10px] font-bold text-[var(--bg)]"
        />
      )}

      {/* Content bubble */}
      <div
        className={cn(
          'max-w-[85%] rounded-lg px-4 py-3 break-words whitespace-normal',
          isUser
            ? 'bg-[var(--user-bg)] text-[var(--fg)]'
            : 'bg-transparent text-[var(--fg)]',
        )}
      >
        {!isUser && (
          <span className="mb-1 block text-[11px] font-medium text-[var(--fg-secondary)]">
            Agent
          </span>
        )}

        <MarkdownRenderer content={content} isStreaming={isStreaming} />

        {isStreaming && (
          <Loader variant="terminal" size="sm" className="inline-block ml-0.5 align-text-bottom" />
        )}

        {/* Timestamp on hover */}
        <div className="mt-1 opacity-0 transition-opacity duration-200 group-hover:opacity-100">
          <span className="text-[10px] text-[var(--fg-muted)]">
            {new Date(message.timestamp).toLocaleTimeString([], {
              hour: '2-digit',
              minute: '2-digit',
            })}
          </span>
        </div>
      </div>
    </PkMessage>
  );
});
