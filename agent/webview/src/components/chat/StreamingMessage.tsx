import React from 'react';
import { useSettingsStore } from '@/stores/settingsStore';
import { useBlockSplitter } from '@/hooks/useBlockSplitter';
import { usePresentationBuffer } from '@/hooks/usePresentationBuffer';
import { BlurTextStream } from './BlurTextStream';
import { MarkdownRenderer } from '../markdown/MarkdownRenderer';

/**
 * Three-zone streaming message renderer:
 *
 * Zone 1 (Markdown):  Completed blocks rendered with full markdown formatting
 * Zone 2 (Settled):   Current in-progress block — plain text (graduated from blur)
 * Zone 3 (Animated):  Trailing ~15 chars with blur/fade animation
 *
 * When animations are disabled, the entire text is rendered as Zone 1+2
 * without per-character animation.
 */
export const StreamingMessage: React.FC = () => {
  const { displayText } = usePresentationBuffer();
  const animationsEnabled = useSettingsStore(s => s.chatAnimationsEnabled);
  const { completedBlocks, currentBlock } = useBlockSplitter(displayText);

  if (displayText.length === 0) return null;

  return (
    <div className="agent-message streaming-message">
      {/* Zone 1: Completed blocks — full markdown rendering */}
      {completedBlocks.length > 0 && (
        <MarkdownRenderer content={completedBlocks} />
      )}

      {/* Zone 2+3: Current block — animated or plain */}
      {currentBlock.length > 0 && (
        animationsEnabled ? (
          <BlurTextStream text={currentBlock} />
        ) : (
          <span className="whitespace-pre-wrap break-words">{currentBlock}</span>
        )
      )}
    </div>
  );
};
