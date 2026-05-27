/**
 * splitThinkingSegments — splits an assistant message body into ordered prose /
 * reasoning segments on <thinking>…</thinking>. Used on resume to recover the
 * collapsible reasoning blocks the live ThinkingTagSplitter produced (the raw
 * tags are persisted in ui_messages.json; the splitter never runs on resume).
 */
import { describe, it, expect } from 'vitest';
import { splitThinkingSegments } from '../util/thinking-segments';

describe('splitThinkingSegments', () => {
  it('returns a single text segment when there is no thinking', () => {
    expect(splitThinkingSegments('plain answer')).toEqual([{ kind: 'text', content: 'plain answer' }]);
  });

  it('splits leading thinking from trailing prose', () => {
    expect(splitThinkingSegments('<thinking>reason here</thinking>The answer is 42')).toEqual([
      { kind: 'thinking', content: 'reason here' },
      { kind: 'text', content: 'The answer is 42' },
    ]);
  });

  it('preserves order across multiple interleaved blocks', () => {
    expect(splitThinkingSegments('<thinking>A</thinking>B<thinking>C</thinking>D')).toEqual([
      { kind: 'thinking', content: 'A' },
      { kind: 'text', content: 'B' },
      { kind: 'thinking', content: 'C' },
      { kind: 'text', content: 'D' },
    ]);
  });

  it('drops empty/whitespace-only segments', () => {
    expect(splitThinkingSegments('<thinking>   </thinking>real')).toEqual([
      { kind: 'text', content: 'real' },
    ]);
  });

  it('recovers an unclosed (interrupted) thinking block best-effort', () => {
    expect(splitThinkingSegments('prose<thinking>cut off mid-thought')).toEqual([
      { kind: 'text', content: 'prose' },
      { kind: 'thinking', content: 'cut off mid-thought' },
    ]);
  });

  it('returns nothing for empty input', () => {
    expect(splitThinkingSegments('')).toEqual([]);
    expect(splitThinkingSegments('   ')).toEqual([]);
  });
});
