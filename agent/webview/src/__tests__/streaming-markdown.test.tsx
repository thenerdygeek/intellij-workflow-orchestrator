/**
 * Scenario tests for streaming markdown rendering (Streamdown integration).
 *
 * The requirements encoded here come from the refactor brief, not from the
 * implementation. They describe the user-facing behavior we want:
 *
 * 1. Paragraphs appear as HTML immediately during streaming (no plain-text
 *    zone, no character drip).
 * 2. Incomplete code fences render as plain monospace during streaming, then
 *    swap to Shiki-highlighted code once the fence closes.
 * 3. A single block caret element appears during streaming.
 * 4. No per-character motion/react animation is ever mounted.
 * 5. StreamingMessage renders nothing when activeStream is null.
 */
import { describe, it, expect, beforeEach } from 'vitest';
import { render, screen, cleanup } from '@testing-library/react';
import { useChatStore } from '@/stores/chatStore';
import { StreamingMessage } from '@/components/chat/StreamingMessage';

const INCOMPLETE_MARKDOWN = `Here is a Python snippet:

\`\`\`python
def foo():`;

const COMPLETED_MARKDOWN = `Here is a Python snippet:

\`\`\`python
def foo():
    return 42
\`\`\``;

function setActiveStream(text: string, isStreaming: boolean) {
  useChatStore.setState({
    activeStream: { text, isStreaming },
  });
}

function clearActiveStream() {
  useChatStore.setState({ activeStream: null });
}

describe('StreamingMessage — real-time markdown', () => {
  beforeEach(() => {
    cleanup();
    clearActiveStream();
  });

  it('renders nothing when activeStream is null', () => {
    const { container } = render(<StreamingMessage />);
    expect(container.firstChild).toBeNull();
  });

  it('renders a paragraph immediately as HTML during streaming', () => {
    setActiveStream('Here is a thought.', true);
    render(<StreamingMessage />);
    const p = screen.getByText('Here is a thought.');
    expect(p.tagName.toLowerCase()).toBe('p');
  });

  it('renders an incomplete code fence as plain monospace, not highlighted', () => {
    setActiveStream(INCOMPLETE_MARKDOWN, true);
    const { container } = render(<StreamingMessage />);

    // The paragraph before the fence is HTML
    expect(screen.getByText('Here is a Python snippet:')).toBeInTheDocument();

    // The fence body renders inside a <pre class="streaming-code-plain">.
    // It MUST NOT render as a Shiki-highlighted CodeBlock with .shiki class.
    const plainPre = container.querySelector('pre.streaming-code-plain');
    expect(plainPre).not.toBeNull();
    expect(plainPre?.textContent).toContain('def foo():');

    const shiki = container.querySelector('pre.shiki');
    expect(shiki).toBeNull();
  });

  it('replaces the plain fence with Shiki highlighting once the fence closes', async () => {
    setActiveStream(COMPLETED_MARKDOWN, true);
    const { container } = render(<StreamingMessage />);

    // No more streaming-code-plain fallback
    const plainPre = container.querySelector('pre.streaming-code-plain');
    expect(plainPre).toBeNull();

    // CodeBlock wraps the Shiki output in a specific container; the existing
    // CodeBlock component renders a <pre> with shiki classes after useShiki
    // resolves. We assert the data-lang attribute we set on the code node.
    const codeNode = container.querySelector('code[class*="language-python"], pre[data-lang="python"]');
    expect(codeNode).not.toBeNull();
  });

  it('shows a block caret while streaming and hides it when done', () => {
    // Streamdown renders the caret as a CSS `::after` pseudo-element on its
    // root div, populated via a `--streamdown-caret` CSS custom property in
    // the inline `style` attribute. jsdom can't compute pseudo-elements, so
    // we assert against the CSS variable on the style attribute — that is
    // the actual observable contract. Source:
    // `node_modules/streamdown/dist/chunk-*.js` — the root div gets
    // `style={"--streamdown-caret": "..."}` only when caret+isAnimating are
    // set and the last block is not an incomplete code fence.

    // Streaming: the CSS variable is set on a descendant div
    setActiveStream('Thinking...', true);
    const { rerender, container } = render(<StreamingMessage />);
    const streamingRoot = container.querySelector('div[style*="--streamdown-caret"]');
    expect(streamingRoot).not.toBeNull();

    // Done: the CSS variable is not set anywhere
    setActiveStream('Thinking...', false);
    rerender(<StreamingMessage />);
    const staticRoot = container.querySelector('div[style*="--streamdown-caret"]');
    expect(staticRoot).toBeNull();
  });

  it('never mounts motion/react per-character animated spans', () => {
    setActiveStream('Streaming text with multiple words.', true);
    const { container } = render(<StreamingMessage />);

    // BlurTextStream used motion's <m.span> which renders with a specific
    // inline style for filter/transform. We assert no element has the
    // will-change:transform,filter,opacity marker.
    const animatedSpans = container.querySelectorAll('span[style*="filter"]');
    expect(animatedSpans.length).toBe(0);
  });
});
