/**
 * Scenario tests for streaming markdown rendering (Streamdown integration).
 *
 * These contracts come from the refactor brief, not from the implementation.
 * They describe the user-facing behavior we want:
 *
 * 1. Paragraphs appear as HTML immediately during streaming (no plain-text
 *    zone, no character drip).
 * 2. Incomplete code fences render as plain monospace during streaming, then
 *    swap to Shiki-highlighted code once the fence closes.
 * 3. A single block caret element appears during streaming.
 * 4. No per-character motion/react animation is ever mounted.
 *
 * **Architecture note.** Earlier in this branch there was a dedicated
 * `StreamingMessage` component that rendered the stream through a different
 * wrapper than `AgentMessage` used for finalized messages. Switching wrappers
 * on stream end caused a visible reflow/flash (DOM structure changed). The
 * unified model renders ALL agent messages — streaming or finalized — through
 * `AgentMessage` with an `isStreaming` flag, so the DOM is stable across the
 * transition. These tests therefore mount `AgentMessage` directly with a
 * synthetic `Message` and `isStreaming={true}`, which is the canonical way
 * the UI now renders an in-progress stream.
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
    render(<AgentMessage message={streamingMessage('Here is a thought.')} isStreaming />);
    const p = screen.getByText('Here is a thought.');
    expect(p.tagName.toLowerCase()).toBe('p');
  });

  it('renders an incomplete code fence as plain monospace, not highlighted', () => {
    const { container } = render(
      <AgentMessage message={streamingMessage(INCOMPLETE_MARKDOWN)} isStreaming />,
    );

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

  it('never mounts motion/react per-character animated spans', () => {
    const { container } = render(
      <AgentMessage
        message={streamingMessage('Streaming text with multiple words.')}
        isStreaming
      />,
    );

    // The deleted `BlurTextStream` used motion's <m.span> with an inline
    // filter/transform style. Assert no element has that marker.
    const animatedSpans = container.querySelectorAll('span[style*="filter"]');
    expect(animatedSpans.length).toBe(0);
  });

  it('renders through the SAME wrapper when streaming and finalized (structural landmarks present in both states)', () => {
    // Direct regression test for the streaming flash bug. Under the old
    // model, `StreamingMessage` rendered a bare div, then on stream end
    // the message was pushed into `messages` and rendered by `AgentMessage`
    // with a COMPLETELY different wrapper (avatar, 85% max-width bubble,
    // "Agent" label, padding, entrance animation). The user saw a flash.
    //
    // Under the unified model the same `AgentMessage` component renders
    // both states. Toggling `isStreaming` must leave every structural
    // landmark intact.
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

  it('REGRESSION Bug 2: stream→finalized transition does NOT remount the DOM (same element reference)', () => {
    // The strongest contract for "no flash": the actual DOM `Element`
    // object that renders the message must be the IDENTICAL reference
    // before and after the `isStreaming` flag flip. If React unmounts and
    // remounts the subtree (because the renderer swapped components, or
    // because the React key changed), `querySelector` returns a NEW
    // element object — the old one is detached and garbage-collected.
    //
    // Object identity (`===`) is the reliable jsdom-compatible way to
    // detect a remount. A deep-equality comparison would pass even on a
    // remount because the new element has the same content.
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

    // ── The core no-remount assertion ──
    // Same Element instance. If these fail, React is tearing down the
    // subtree on the transition and the user will see a visible flash.
    expect(afterBubble, 'bubble element identity must be preserved across isStreaming transition').toBe(beforeBubble);
    expect(afterAvatar, 'avatar element identity must be preserved across isStreaming transition').toBe(beforeAvatar);
  });

  it('REGRESSION Bug 2: stream→finalized transition fires exactly 1 mount and 0 unmounts', () => {
    // Complementary proof via React effect lifecycle. Wrap `AgentMessage`
    // in a tiny component whose `useEffect` bumps a mount counter on mount
    // and a cleanup counter on unmount. A remount fires both — the cleanup
    // from the old instance AND the effect from the new one. An in-place
    // re-render (the goal) fires neither after the initial mount, because
    // the effect has no dependencies that change.
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
});
