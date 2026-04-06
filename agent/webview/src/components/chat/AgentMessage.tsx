import { memo } from 'react';
import type { Message } from '@/bridge/types';
import { MarkdownRenderer } from '@/components/markdown/MarkdownRenderer';
import { ThinkingView } from '@/components/agent/ThinkingView';
import { CompletionCard } from '@/components/agent/CompletionCard';
import { EditDiffView } from '@/components/agent/EditDiffView';
import { DiffHtml } from '@/components/rich/DiffHtml';
import {
  Message as PkMessage,
  MessageAvatar,
} from '@/components/ui/prompt-kit/message';
import { CopyButton } from '@/components/ui/copy-button';
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
      // Diff explanation from generate_explanation tool
      if (parsed.type === 'diff-explanation' && parsed.diffSource) {
        return (
          <div style={{ marginBottom: 8 }}>
            {parsed.title && (
              <div style={{
                fontSize: 13,
                fontWeight: 600,
                marginBottom: 6,
                color: 'var(--fg-secondary, #94a3b8)',
              }}>
                {parsed.title}
              </div>
            )}
            <DiffHtml diffSource={parsed.diffSource} />
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

        {/* Copy button (visible on hover) */}
        {!isStreaming && content && (
          <CopyButton
            text={content}
            hoverOnly
            label="Copy message"
            className="absolute top-2 right-2"
          />
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
