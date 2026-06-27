/**
 * BUG-2 (scroll-to-bottom short-landing + mid-stream JUMP).
 *
 * In a long chat with tall, async-rendered code blocks the scroll-to-bottom
 * landed SHORT of the actual bottom: code blocks first render as ~60px Shiki
 * skeletons, then grow to 400–800px once highlighting resolves, so a single
 * `scrollToIndex` positions from Virtuoso's STALE cached heights and stops
 * short. `behavior:'smooth'` additionally raced the height changes, producing
 * the mid-stream JUMP.
 *
 * These tests pin the height-independent contract of MessageList.scrollToBottom:
 *   1. it NEVER animates with `behavior:'smooth'` (kills the JUMP), and always
 *      targets the LAST item with `align:'end'` (height-independent target), and
 *   2. it RE-ISSUES the bottom scroll on a later animation frame, so the final
 *      position is computed against the settled (grown) heights — reaching the
 *      true bottom instead of the stale skeleton offset.
 *
 * Both assertions FAIL against the pre-fix single `scrollToIndex({ ...,
 * behavior:'smooth' })` implementation.
 */
import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { createRef } from 'react';
import { render, act, cleanup } from '@testing-library/react';

// A ref-forwarding Virtuoso stub exposing the imperative handle MessageList
// drives. Captured spies let us assert exactly how scrollToBottom positions.
const { fake } = vi.hoisted(() => ({
  fake: {
    scrollToIndex: vi.fn(),
    autoscrollToBottom: vi.fn(),
  },
}));

vi.mock('react-virtuoso', async () => {
  const React = await import('react');
  return {
    Virtuoso: React.forwardRef((_props: any, ref: any) => {
      React.useImperativeHandle(ref, () => fake);
      return React.createElement('div', { role: 'log' });
    }),
  };
});

import { MessageList, type MessageListHandle } from '@/components/chat/MessageList';

describe('MessageList.scrollToBottom reaches the true bottom despite stale heights', () => {
  let rafQueue: FrameRequestCallback[];

  beforeEach(() => {
    fake.scrollToIndex.mockClear();
    fake.autoscrollToBottom.mockClear();
    rafQueue = [];
    // Deterministic rAF: queue callbacks so the test can flush frames one by one
    // and observe the re-scroll that corrects for heights settling between frames.
    vi.stubGlobal('requestAnimationFrame', (cb: FrameRequestCallback) => {
      rafQueue.push(cb);
      return rafQueue.length;
    });
  });

  afterEach(() => {
    vi.unstubAllGlobals();
    cleanup();
  });

  function flushFrame() {
    const due = rafQueue;
    rafQueue = [];
    act(() => {
      due.forEach((cb) => cb(0));
    });
  }

  it('never animates with behavior:"smooth" and always targets LAST/align:end', () => {
    const ref = createRef<MessageListHandle>();
    render(<MessageList ref={ref} count={3} renderItem={() => null} />);

    act(() => ref.current!.scrollToBottom());
    // Drain the re-scroll frames so every issued scroll is asserted.
    flushFrame();
    flushFrame();

    expect(fake.scrollToIndex).toHaveBeenCalled();
    expect(fake.autoscrollToBottom).not.toHaveBeenCalled();
    for (const call of fake.scrollToIndex.mock.calls) {
      const arg = call[0];
      // No smooth animation — that was the source of the mid-stream JUMP.
      expect(arg).not.toHaveProperty('behavior', 'smooth');
      // Height-independent target: the actual last item, bottom-aligned.
      expect(arg).toMatchObject({ index: 'LAST', align: 'end' });
    }
  });

  it('re-issues the bottom scroll on a later frame so settled heights are honored', () => {
    const ref = createRef<MessageListHandle>();
    render(<MessageList ref={ref} count={3} renderItem={() => null} />);

    act(() => ref.current!.scrollToBottom());
    const afterFirstPaint = fake.scrollToIndex.mock.calls.length;
    expect(afterFirstPaint).toBeGreaterThanOrEqual(1);

    // Heights settle between frames; the deferred re-scroll must fire so the
    // final position uses the grown heights, not the stale first measurement.
    flushFrame();
    expect(fake.scrollToIndex.mock.calls.length).toBeGreaterThan(afterFirstPaint);
  });
});
