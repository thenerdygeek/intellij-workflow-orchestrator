import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';

/**
 * Pin the sandbox-main.ts cleanup contract:
 *
 * - The window `message` listener that handles 'render' / 'theme' must be
 *   removed on `beforeunload`.
 * - The ResizeObserver wired to the root element must `disconnect()` on
 *   `beforeunload`.
 *
 * Previously both lived at module scope and were never removed. Each new
 * artifact iframe leaked one of each, accumulating across an agent session.
 */
describe('sandbox-main cleanup', () => {
  // Capture the original ResizeObserver once so afterEach can restore it.
  // Vitest isolates modules between files but NOT globalThis mutations within
  // the same worker — without explicit restoration, the MockResizeObserver
  // assigned by the second `it` block would leak into any subsequent test
  // file that touches ResizeObserver (directly or transitively through
  // Radix / charts / virtualization libraries). That's a real CI flake hazard.
  const originalResizeObserver = globalThis.ResizeObserver;

  beforeEach(() => {
    vi.resetModules();
    document.body.innerHTML = '<div id="root"></div>';
  });

  afterEach(() => {
    // Restore the global so the next test file sees the original implementation.
    globalThis.ResizeObserver = originalResizeObserver;
  });

  it('removes the window message listener on beforeunload', async () => {
    const addSpy = vi.spyOn(window, 'addEventListener');
    const removeSpy = vi.spyOn(window, 'removeEventListener');

    await import('../sandbox-main');
    // Allow init-time microtasks to flush.
    await Promise.resolve();

    window.dispatchEvent(new Event('beforeunload'));

    const addedMessageHandlers = addSpy.mock.calls
      .filter((c) => c[0] === 'message')
      .map((c) => c[1]);
    expect(addedMessageHandlers.length).toBeGreaterThan(0);

    const removedMessageHandlers = removeSpy.mock.calls
      .filter((c) => c[0] === 'message')
      .map((c) => c[1]);

    for (const handler of addedMessageHandlers) {
      expect(removedMessageHandlers).toContain(handler);
    }
  });

  it('disconnects the ResizeObserver on beforeunload', async () => {
    let disconnectCount = 0;
    class MockResizeObserver implements ResizeObserver {
      observe = vi.fn();
      unobserve = vi.fn();
      disconnect = vi.fn(() => { disconnectCount++; });
    }
    (globalThis as unknown as { ResizeObserver: typeof ResizeObserver }).ResizeObserver =
      MockResizeObserver as unknown as typeof ResizeObserver;

    await import('../sandbox-main');
    await Promise.resolve();

    expect(disconnectCount).toBe(0);
    window.dispatchEvent(new Event('beforeunload'));
    expect(disconnectCount).toBeGreaterThan(0);
  });
});
