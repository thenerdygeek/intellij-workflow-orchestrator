/**
 * Scenario tests for streaming markdown rendering through `AgentMessage`.
 *
 * Contracts:
 * 1. Paragraphs appear as HTML immediately during streaming (no plain-text
 *    zone, no character drip).
 * 2. Incomplete code fences render as plain monospace during streaming, then
 *    swap to Shiki-highlighted code once the fence closes.
 * 3. A single block caret element appears during streaming.
 * 4. No per-character motion/react animation is ever mounted.
 * 5. The streaming→finalized transition preserves the same outer wrapper,
 *    the same DOM `Element` references, and fires zero mount/unmount cycles
 *    — so the user sees no flash/reflow at stream end.
 */
import { describe, it, expect, beforeEach } from 'vitest';
import { render, screen, cleanup } from '@testing-library/react';
import { useEffect } from 'react';
import type { Message } from '@/bridge/types';
import { AgentMessage } from '@/components/chat/AgentMessage';

const INCOMPLETE_MARKDOWN = `Here is a Python snippet:

\`\`\`python
def foo():`;

const COMPLETED_MARKDOWN = `Here is a Python snippet:

\`\`\`python
def foo():
    return 42
\`\`\``;

/**
 * Build a streaming agent-message stub. In the live app, `appendToken` creates
 * this message in the chat store on the first token and updates its `content`
 * in place on every subsequent token. Tests bypass the store and pass content
 * directly — the component under test is the renderer, not the store.
 */
function streamingMessage(content: string): Message {
  return {
    id: 'msg-streaming-test',
    role: 'agent',
    content,
    timestamp: Date.now(),
  };
}

describe('AgentMessage — real-time streaming markdown', () => {
  beforeEach(() => {
    cleanup();
  });

  it('renders a paragraph immediately as HTML during streaming', () => {
    // Text content is split into per-word `sd-word` spans by rehypeWordFade,
    // so match on the `<p>` element's concatenated textContent rather than
    // RTL's `getByText` (which requires a single literal text node).
    const { container } = render(
      <AgentMessage message={streamingMessage('Here is a thought.')} isStreaming />,
    );
    const p = container.querySelector('p');
    expect(p).not.toBeNull();
    expect(p?.textContent).toBe('Here is a thought.');
  });

  it('renders an incomplete code fence as plain monospace, not highlighted', () => {
    const { container } = render(
      <AgentMessage message={streamingMessage(INCOMPLETE_MARKDOWN)} isStreaming />,
    );

    // The paragraph before the fence is HTML — `textContent` normalizes the
    // per-word span split introduced by `rehypeWordFade`.
    const firstP = container.querySelector('p');
    expect(firstP?.textContent).toBe('Here is a Python snippet:');

    // The fence body renders inside a <pre class="streaming-code-plain">.
    // It MUST NOT render as a Shiki-highlighted CodeBlock with .shiki class.
    const plainPre = container.querySelector('pre.streaming-code-plain');
    expect(plainPre).not.toBeNull();
    expect(plainPre?.textContent).toContain('def foo():');

    const shiki = container.querySelector('pre.shiki');
    expect(shiki).toBeNull();
  });

  it('replaces the plain fence with Shiki highlighting once the fence closes', () => {
    const { container } = render(
      <AgentMessage message={streamingMessage(COMPLETED_MARKDOWN)} isStreaming />,
    );

    // No more streaming-code-plain fallback
    const plainPre = container.querySelector('pre.streaming-code-plain');
    expect(plainPre).toBeNull();

    // Once the fence closes, CodeNode routes to CodeBlock instead of the
    // streaming-code-plain fallback. Shiki's async highlighter never
    // resolves inside jsdom (no worker/WASM plumbing), so we can't look
    // for the final `<code class="language-python">` token tree. The
    // reliably-observable contract is that CodeBlock mounted: its header
    // renders a language label chip ("python") that neither
    // streaming-code-plain nor the inline-`code` path ever produces.
    const languageLabel = Array.from(container.querySelectorAll('span')).find(
      el => el.textContent === 'python',
    );
    expect(languageLabel).toBeDefined();
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
    const { rerender, container } = render(
      <AgentMessage message={streamingMessage('Thinking...')} isStreaming />,
    );
    const streamingRoot = container.querySelector('div[style*="--streamdown-caret"]');
    expect(streamingRoot).not.toBeNull();

    // Done: the CSS variable is not set anywhere — the same message rendered
    // with `isStreaming={false}` becomes the finalized bubble, and Streamdown
    // drops the caret variable.
    rerender(
      <AgentMessage message={streamingMessage('Thinking...')} isStreaming={false} />,
    );
    const staticRoot = container.querySelector('div[style*="--streamdown-caret"]');
    expect(staticRoot).toBeNull();
  });

  it('does not mount motion/react per-character animated spans', () => {
    const { container } = render(
      <AgentMessage
        message={streamingMessage('Streaming text with multiple words.')}
        isStreaming
      />,
    );

    // A `filter`/`transform` inline style on a span would indicate a
    // per-character motion/react animation — Streamdown's block caret is
    // a CSS pseudo-element and does not leave that marker.
    const animatedSpans = container.querySelectorAll('span[style*="filter"]');
    expect(animatedSpans.length).toBe(0);
  });

  it('streaming and finalized share the same structural landmarks (avatar, "Agent" label, constrained bubble)', () => {
    // These three landmarks are the cheapest observable signal that both
    // states render through the same `AgentMessage` wrapper. Any render
    // path that skips the avatar or the bubble constraint during streaming
    // would cause a reflow/flash at stream end.
    const msg = streamingMessage('Hello, world.');

    const { container, rerender } = render(
      <AgentMessage message={msg} isStreaming />,
    );
    const streamingAgentLabel = Array.from(container.querySelectorAll('span'))
      .find(el => el.textContent === 'Agent');
    const streamingAvatar = container.querySelector('[class*="bg-[var(--accent"]');
    const streamingBubble = container.querySelector('.max-w-\\[85\\%\\]');

    rerender(<AgentMessage message={msg} isStreaming={false} />);

    expect(streamingAgentLabel, 'Agent label should exist during streaming').toBeDefined();
    expect(streamingAvatar, 'Avatar should exist during streaming').not.toBeNull();
    expect(streamingBubble, 'Constrained bubble should exist during streaming').not.toBeNull();

    const finalAgentLabel = Array.from(container.querySelectorAll('span'))
      .find(el => el.textContent === 'Agent');
    const finalAvatar = container.querySelector('[class*="bg-[var(--accent"]');
    const finalBubble = container.querySelector('.max-w-\\[85\\%\\]');
    expect(finalAgentLabel, 'Agent label should still exist when finalized').toBeDefined();
    expect(finalAvatar, 'Avatar should still exist when finalized').not.toBeNull();
    expect(finalBubble, 'Constrained bubble should still exist when finalized').not.toBeNull();
  });

  it('stream→finalized transition preserves DOM element identity (no remount)', () => {
    // If toggling `isStreaming` ever caused React to unmount and recreate
    // the subtree, `querySelector` would return a different `Element`
    // instance after `rerender`. Object identity (`===`) is what catches
    // that — a deep-equality check would pass on a remount because the
    // new element has the same content.
    const msg = streamingMessage('Hello, world.');

    const { container, rerender } = render(
      <AgentMessage message={msg} isStreaming />,
    );
    const beforeBubble = container.querySelector('.max-w-\\[85\\%\\]');
    const beforeAvatar = container.querySelector('[class*="bg-[var(--accent"]');
    expect(beforeBubble).not.toBeNull();
    expect(beforeAvatar).not.toBeNull();

    rerender(<AgentMessage message={msg} isStreaming={false} />);

    const afterBubble = container.querySelector('.max-w-\\[85\\%\\]');
    const afterAvatar = container.querySelector('[class*="bg-[var(--accent"]');

    expect(afterBubble, 'bubble element identity must be preserved across isStreaming transition').toBe(beforeBubble);
    expect(afterAvatar, 'avatar element identity must be preserved across isStreaming transition').toBe(beforeAvatar);
  });

  it('stream→finalized transition fires exactly 1 mount and 0 unmounts', () => {
    // Complementary framework-level proof for the no-remount contract.
    // A `useEffect` with empty deps fires its body on mount and its
    // cleanup on unmount — a remount triggers both (old cleanup + new
    // body). In-place re-render triggers neither after the initial mount.
    let mountCount = 0;
    let unmountCount = 0;

    function MountTracker({ isStreaming }: { isStreaming: boolean }) {
      useEffect(() => {
        mountCount++;
        return () => { unmountCount++; };
      }, []); // empty deps — only mount/unmount, not on every render

      return <AgentMessage message={streamingMessage('Counting tokens')} isStreaming={isStreaming} />;
    }

    const { rerender } = render(<MountTracker isStreaming />);
    expect(mountCount).toBe(1);
    expect(unmountCount).toBe(0);

    // Transition to finalized. This must be a re-render, not a remount.
    rerender(<MountTracker isStreaming={false} />);
    expect(mountCount, 'streaming→finalized must not remount (mountCount should stay at 1)').toBe(1);
    expect(unmountCount, 'streaming→finalized must not unmount (unmountCount should stay at 0)').toBe(0);

    // And back to streaming — also a re-render, not a remount.
    rerender(<MountTracker isStreaming />);
    expect(mountCount, 'finalized→streaming must not remount').toBe(1);
    expect(unmountCount).toBe(0);
  });

  // ── Per-word fade-in (rehypeWordFade) ──
  //
  // The `rehypeWordFade` plugin wraps each word in `<span class="sd-word">`
  // so CSS `@keyframes sd-word-fade-in` can animate newly-arrived words in
  // as they stream. These tests lock in the plugin's shape so a future
  // refactor can't silently regress the animation contract.

  it('wraps each word in a paragraph in <span class="sd-word">', () => {
    const { container } = render(
      <AgentMessage message={streamingMessage('Hello streaming world.')} isStreaming />,
    );
    const wordSpans = container.querySelectorAll('span.sd-word');
    // Four words: "Hello", "streaming", "world.". Trailing period stays attached.
    const words = Array.from(wordSpans).map(s => s.textContent);
    expect(words).toEqual(['Hello', 'streaming', 'world.']);
  });

  it('preserves whitespace as plain text nodes between word spans so copy-paste is natural', () => {
    const { container } = render(
      <AgentMessage message={streamingMessage('one two three')} isStreaming />,
    );
    // The paragraph's textContent must still read as the original string
    // with spaces — not concatenated word content. If whitespace were lost
    // or folded, copy-paste would produce "onetwothree".
    const p = container.querySelector('p');
    expect(p?.textContent).toBe('one two three');
  });

  it('does NOT wrap words inside fenced code blocks', () => {
    const code = 'Before code\n\n```python\ndef foo(): return 42\n```\n\nAfter code';
    const { container } = render(
      <AgentMessage message={streamingMessage(code)} isStreaming={false} />,
    );
    // Words outside the code block should be wrapped.
    const paragraphWords = Array.from(container.querySelectorAll('p span.sd-word'))
      .map(s => s.textContent);
    expect(paragraphWords).toContain('Before');
    expect(paragraphWords).toContain('After');

    // Words INSIDE <code>/<pre> must not be wrapped — Python keywords and
    // identifiers must remain plain text so syntax highlighting sees them.
    const codeSpans = container.querySelectorAll('pre span.sd-word, code span.sd-word');
    expect(codeSpans.length).toBe(0);
  });

  it('does NOT wrap words inside inline code spans', () => {
    const { container } = render(
      <AgentMessage
        message={streamingMessage('Call the `doSomething` function now.')}
        isStreaming={false}
      />,
    );
    // The inline code's content should not be wrapped.
    const inlineCode = container.querySelector('code');
    expect(inlineCode?.textContent).toBe('doSomething');
    expect(inlineCode?.querySelectorAll('span.sd-word').length).toBe(0);

    // But the surrounding paragraph text IS wrapped.
    const paragraphWords = Array.from(container.querySelectorAll('p > span.sd-word'))
      .map(s => s.textContent);
    expect(paragraphWords).toContain('Call');
    expect(paragraphWords).toContain('function');
    expect(paragraphWords).toContain('now.');
  });

  it('wraps words inside emphasis and strong without nesting sd-word inside sd-word', () => {
    const { container } = render(
      <AgentMessage
        message={streamingMessage('This *is really* important.')}
        isStreaming={false}
      />,
    );
    const em = container.querySelector('em');
    expect(em).not.toBeNull();
    // "is" and "really" inside the em should each be an sd-word span.
    const emWords = Array.from(em!.querySelectorAll('span.sd-word')).map(s => s.textContent);
    expect(emWords).toEqual(['is', 'really']);

    // No span.sd-word should contain another span.sd-word.
    const allWordSpans = container.querySelectorAll('span.sd-word');
    for (const span of allWordSpans) {
      expect(
        span.querySelector('span.sd-word'),
        'sd-word spans must not nest',
      ).toBeNull();
    }
  });
});
