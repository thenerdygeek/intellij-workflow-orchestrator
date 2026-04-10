import { useMemo } from 'react';

interface BlockSplit {
  /** Array of completed, structurally-closed markdown blocks. Append-only: existing entries never change. */
  completedBlocks: string[];
  /** Current in-progress block — not yet closed, rendered as plain text. */
  currentBlock: string;
}

/**
 * Splits streaming text into an array of completed markdown blocks plus the
 * current in-progress block. A block is "complete" when followed by a blank
 * line or a closing code fence.
 *
 * The returned `completedBlocks` is append-only — existing entries never
 * change as more text arrives, so consumers can use the index as a stable key
 * for per-block animation without re-triggering on append.
 *
 * This is a lightweight boundary scanner, not a full markdown parser.
 */
export function useBlockSplitter(text: string): BlockSplit {
  return useMemo(() => splitBlocks(text), [text]);
}

export function splitBlocks(text: string): BlockSplit {
  if (text.length === 0) {
    return { completedBlocks: [], currentBlock: '' };
  }

  const blocks: string[] = [];
  const lines = text.split('\n');
  let inCodeFence = false;
  let blockStart = 0;
  let charIndex = 0;

  const emitBlockEndingAt = (endIndex: number) => {
    const block = text.slice(blockStart, endIndex);
    if (block.trim().length > 0) {
      blocks.push(block);
    }
    blockStart = endIndex;
  };

  for (let i = 0; i < lines.length; i++) {
    const line = lines[i]!;
    const trimmed = line.trimStart();

    if (trimmed.startsWith('```') || trimmed.startsWith('~~~')) {
      inCodeFence = !inCodeFence;
      // Closing fence — emit the block (which includes the fence pair)
      if (!inCodeFence) {
        emitBlockEndingAt(charIndex + line.length + 1); // +1 for \n
      }
    } else if (!inCodeFence && trimmed === '' && i > 0) {
      // Blank line outside a fence — emit the paragraph/block before it
      emitBlockEndingAt(charIndex + line.length + 1);
    }

    charIndex += line.length + 1;
  }

  return {
    completedBlocks: blocks,
    currentBlock: text.slice(blockStart),
  };
}
