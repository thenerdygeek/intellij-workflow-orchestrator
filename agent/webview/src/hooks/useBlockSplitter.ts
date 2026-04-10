import { useMemo } from 'react';

interface BlockSplit {
  /** Completed blocks — structurally closed, safe for markdown parsing. */
  completedBlocks: string;
  /** Current in-progress block — not yet closed, rendered as plain text. */
  currentBlock: string;
}

/**
 * Splits streaming text into completed markdown blocks and the current
 * in-progress block. A block is "complete" when followed by a blank line,
 * a closing code fence, or another block-level element.
 *
 * This is a lightweight boundary scanner, not a full markdown parser.
 */
export function useBlockSplitter(text: string): BlockSplit {
  return useMemo(() => splitBlocks(text), [text]);
}

/** Find the last safe split point in the text. */
export function splitBlocks(text: string): BlockSplit {
  if (text.length === 0) {
    return { completedBlocks: '', currentBlock: '' };
  }

  // Track code fence state to avoid splitting inside fenced blocks
  let inCodeFence = false;
  let lastSafeSplit = 0;
  const lines = text.split('\n');
  let charIndex = 0;

  for (let i = 0; i < lines.length; i++) {
    const line = lines[i]!;
    const trimmed = line.trimStart();

    // Detect code fence boundaries
    if (trimmed.startsWith('```') || trimmed.startsWith('~~~')) {
      inCodeFence = !inCodeFence;
      // If we just closed a code fence, the end of this line is a safe split
      if (!inCodeFence) {
        lastSafeSplit = charIndex + line.length + 1; // +1 for \n
      }
    } else if (!inCodeFence) {
      // Outside code fences: blank lines are safe split points
      if (trimmed === '' && i > 0) {
        lastSafeSplit = charIndex + line.length + 1;
      }
    }

    charIndex += line.length + 1; // +1 for \n
  }

  // If we're inside a code fence, don't split — the whole thing is in-progress
  if (inCodeFence) {
    // Find the opening fence and split before it
    const fenceMatch = text.match(/^([\s\S]*?\n)(```|~~~)/m);
    if (fenceMatch && fenceMatch.index !== undefined && fenceMatch[1]) {
      const splitAt = fenceMatch.index + fenceMatch[1].length;
      if (splitAt > 0 && text.slice(0, splitAt).trim().length > 0) {
        return {
          completedBlocks: text.slice(0, splitAt),
          currentBlock: text.slice(splitAt),
        };
      }
    }
    return { completedBlocks: '', currentBlock: text };
  }

  if (lastSafeSplit === 0) {
    return { completedBlocks: '', currentBlock: text };
  }

  return {
    completedBlocks: text.slice(0, lastSafeSplit),
    currentBlock: text.slice(lastSafeSplit),
  };
}
