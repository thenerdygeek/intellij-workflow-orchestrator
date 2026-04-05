import { memo, useState, useCallback } from 'react';
import type { Message } from '@/bridge/types';
import { MarkdownRenderer } from '@/components/markdown/MarkdownRenderer';
import { ThinkingView } from '@/components/agent/ThinkingView';
import { CompletionCard } from '@/components/agent/CompletionCard';
import { EditDiffView } from '@/components/agent/EditDiffView';
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
  const [copied, setCopied] = useState(false);

  const handleCopy = useCallback(() => {
    navigator.clipboard.writeText(content).then(() => {
      setCopied(true);
      setTimeout(() => setCopied(false), 2000);
    });
  }, [content]);

  // System messages: thinking blocks and status lines
  if (message.role === 'system') {
    try {
      const parsed = JSON.parse(message.content) as Record<string, any>;
      if (parsed.type === 'thinking') {
        return <ThinkingView content={parsed.text ?? ''} isStreaming={false} />;
      }
      if (parsed.type === 'completion') {
        return <CompletionCard result={parsed.result ?? ''} verifyCommand={parsed.verifyCommand} />;
      }
      if (parsed.type === 'status') {
        return (
          <div className="px-1 py-0.5 text-[11px]" style={{ color: 'var(--fg-muted, #888)' }}>
            {parsed.message}
          </div>
        );
      }
      // Edit diffs from the Kotlin bridge (has filePath + oldLines + newLines)
      if (parsed.filePath && Array.isArray(parsed.oldLines) && Array.isArray(parsed.newLines)) {
        return (
          <EditDiffView
            filePath={parsed.filePath}
            oldLines={parsed.oldLines}
            newLines={parsed.newLines}
            accepted={parsed.accepted ?? null}
          />
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
          'relative max-w-[85%] rounded-lg px-4 py-3 whitespace-normal [overflow-wrap:anywhere]',
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

        {isUser ? (
          <p className="text-[13px] leading-relaxed whitespace-pre-wrap">{content}</p>
        ) : (
          <MarkdownRenderer content={content} isStreaming={isStreaming} />
        )}


        {/* Copy button for agent messages (visible on hover) */}
        {!isUser && !isStreaming && content && (
          <button
            onClick={handleCopy}
            className="absolute top-2 right-2 opacity-0 group-hover:opacity-100 transition-opacity rounded p-1 text-[var(--fg-muted)] hover:bg-[var(--hover-bg)] hover:text-[var(--fg)]"
            title={copied ? 'Copied!' : 'Copy message'}
            aria-label={copied ? 'Copied' : 'Copy message'}
          >
            {copied ? (
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                <polyline points="20 6 9 17 4 12" />
              </svg>
            ) : (
              <svg width="14" height="14" viewBox="0 0 24 24" fill="none" stroke="currentColor" strokeWidth="2" strokeLinecap="round" strokeLinejoin="round">
                <rect x="9" y="9" width="13" height="13" rx="2" ry="2" />
                <path d="M5 15H4a2 2 0 0 1-2-2V4a2 2 0 0 1 2-2h9a2 2 0 0 1 2 2v1" />
              </svg>
            )}
          </button>
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
