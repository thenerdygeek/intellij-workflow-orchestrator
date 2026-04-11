import React from 'react';
import { useChatStore } from '@/stores/chatStore';
import { MarkdownRenderer } from '../markdown/MarkdownRenderer';

/**
 * Single-zone streaming message renderer.
 *
 * Streamdown handles the heavy lifting: per-block memoization, speculative
 * close of incomplete markdown constructs (remend), and a block-level caret
 * indicator. There is no per-character animation, no presentation buffer,
 * no ad-hoc block splitter.
 *
 * The Kotlin StreamBatcher (agent/ui/StreamBatcher.kt) already coalesces
 * rapid LLM chunks at 16ms intervals on the EDT, which is the right layer
 * to smooth the bridge call rate without introducing visible latency.
 */
export const StreamingMessage: React.FC = () => {
  const text = useChatStore(s => s.activeStream?.text ?? '');
  const isStreaming = useChatStore(s => s.activeStream?.isStreaming ?? false);

  if (text.length === 0) return null;

  return (
    <div className="agent-message streaming-message">
      <MarkdownRenderer content={text} isStreaming={isStreaming} />
    </div>
  );
};
