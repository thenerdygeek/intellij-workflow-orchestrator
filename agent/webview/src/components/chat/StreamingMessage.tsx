import React from 'react';
import { LazyMotion, domAnimation, m } from 'motion/react';
import { useSettingsStore } from '@/stores/settingsStore';
import { useBlockSplitter } from '@/hooks/useBlockSplitter';
import { usePresentationBuffer } from '@/hooks/usePresentationBuffer';
import { BlurTextStream } from './BlurTextStream';
import { MarkdownRenderer } from '../markdown/MarkdownRenderer';

/**
 * Crossfade animation for completed blocks transitioning from Zone 2 (plain)
 * into Zone 1 (rendered markdown). Each new block fades in with a subtle
 * upward slide, smoothing the "plain → styled" swap at block boundaries.
 */
const BLOCK_ANIMATION = {
  initial: { opacity: 0, y: 2 },
  animate: { opacity: 1, y: 0 },
  transition: { duration: 0.2, ease: [0.25, 0.1, 0.25, 1] as const },
};

/**
 * Three-zone streaming message renderer:
 *
 * Zone 1 (Markdown):  Completed blocks rendered with full markdown formatting,
 *                     each new block crossfades in as it transitions from Zone 2
 * Zone 2 (Settled):   Current in-progress block — plain text (graduated from blur)
 * Zone 3 (Animated):  Trailing ~15 chars with blur/fade animation
 *
 * When animations are disabled, blocks appear instantly and per-character
 * animation is skipped.
 */
export const StreamingMessage: React.FC = () => {
  const { displayText } = usePresentationBuffer();
  const animationsEnabled = useSettingsStore(s => s.chatAnimationsEnabled);
  const { completedBlocks, currentBlock } = useBlockSplitter(displayText);

  if (displayText.length === 0) return null;

  return (
    <LazyMotion features={domAnimation}>
      <div className="agent-message streaming-message">
        {/* Zone 1: Completed blocks — each crossfades in as it completes */}
        {completedBlocks.map((block, i) =>
          animationsEnabled ? (
            <m.div key={i} {...BLOCK_ANIMATION}>
              <MarkdownRenderer content={block} />
            </m.div>
          ) : (
            <div key={i}>
              <MarkdownRenderer content={block} />
            </div>
          ),
        )}

        {/* Zone 2+3: Current block — animated per-char or plain */}
        {currentBlock.length > 0 &&
          (animationsEnabled ? (
            <BlurTextStream text={currentBlock} />
          ) : (
            <span className="whitespace-pre-wrap break-words">{currentBlock}</span>
          ))}
      </div>
    </LazyMotion>
  );
};
