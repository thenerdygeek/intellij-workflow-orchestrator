/**
 * Bug #7 — the compress-confirm modal used a single state slot. A second oversize
 * paste/attach while the first prompt was open overwrote the slot, leaving the
 * FIRST attachFile promise's `resolve` orphaned forever (and the per-turn cap
 * counting off). The prompts must serialize: each request gets its own resolve,
 * shown one at a time.
 */
import { describe, it, expect } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import { useCompressQueue } from '../components/input/useCompressQueue';

describe('Bug #7 — useCompressQueue serializes overlapping confirm prompts', () => {
  it('resolves every request even when a second arrives before the first is answered', async () => {
    const { result } = renderHook(() => useCompressQueue());

    let p1!: Promise<boolean>;
    let p2!: Promise<boolean>;
    act(() => { p1 = result.current.request(900, 500, 'a.png'); });
    act(() => { p2 = result.current.request(800, 500, 'b.png'); });

    // Only the first prompt is shown.
    expect(result.current.current?.filename).toBe('a.png');

    // Answer the first → it resolves and the second becomes current.
    act(() => result.current.resolveCurrent(true));
    await expect(p1).resolves.toBe(true);
    expect(result.current.current?.filename).toBe('b.png');

    // Answer the second → it resolves; queue drains.
    act(() => result.current.resolveCurrent(false));
    await expect(p2).resolves.toBe(false);
    expect(result.current.current).toBeNull();
  });

  it('has no current prompt when idle', () => {
    const { result } = renderHook(() => useCompressQueue());
    expect(result.current.current).toBeNull();
  });
});
