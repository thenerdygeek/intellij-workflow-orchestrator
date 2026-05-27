/**
 * Splits an assistant message body into ordered prose / reasoning segments on
 * `<thinking>…</thinking>` boundaries.
 *
 * Live, AgentController's ThinkingTagSplitter separates reasoning from prose for
 * display. The persisted assistant text (ui_messages.json) keeps the raw tags
 * because the parser only strips tool-call XML, and the splitter never runs on
 * resume — so hydration uses this to recover the collapsible reasoning blocks.
 *
 * An unclosed `<thinking>` (an interrupted stream) is treated best-effort: the
 * remainder after the open tag becomes a reasoning segment.
 */
export interface ThinkingSegment {
  kind: 'text' | 'thinking';
  content: string;
}

const CLOSED = /<thinking>([\s\S]*?)<\/thinking>/g;
const OPEN_TAG = '<thinking>';

export function splitThinkingSegments(text: string): ThinkingSegment[] {
  const out: ThinkingSegment[] = [];
  const push = (kind: ThinkingSegment['kind'], raw: string) => {
    const content = raw.trim();
    if (content) out.push({ kind, content });
  };

  let lastIndex = 0;
  CLOSED.lastIndex = 0;
  let m: RegExpExecArray | null;
  while ((m = CLOSED.exec(text)) !== null) {
    if (m.index > lastIndex) push('text', text.slice(lastIndex, m.index));
    push('thinking', m[1] ?? '');
    lastIndex = CLOSED.lastIndex;
  }

  // Trailing remainder may contain an unclosed (interrupted) <thinking>.
  const rest = text.slice(lastIndex);
  const open = rest.indexOf(OPEN_TAG);
  if (open >= 0) {
    push('text', rest.slice(0, open));
    push('thinking', rest.slice(open + OPEN_TAG.length));
  } else {
    push('text', rest);
  }

  return out;
}
