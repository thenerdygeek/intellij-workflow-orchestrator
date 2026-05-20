import { describe, it, expect, vi, beforeEach } from 'vitest';
import { act, render, waitFor } from '@testing-library/react';
import { useEffect, useState } from 'react';
import type { UiMessage } from '../bridge/types';

// vi.hoisted runs before vi.mock factories, so the mocks below can reference
// these spy functions without tripping the hoisted-vi.mock initialization rule.
const { scanFileMock, scanSymbolMock } = vi.hoisted(() => ({
  scanFileMock: vi.fn(async () => {}),
  scanSymbolMock: vi.fn(async () => {}),
}));

vi.mock('../util/file-link-scanner', () => ({
  scanAndLinkify: scanFileMock,
}));
vi.mock('../util/symbol-link-scanner', () => ({
  scanAndSymbolLinkify: scanSymbolMock,
}));

import { AgentMessage } from '../components/chat/AgentMessage';

/**
 * Pin the AgentMessage scanner memoization contract — BOTH directions:
 *
 *   1. RE-RUN when message.text actually changes. The original implementation
 *      gated the effect on `[isFinalized]` only, so a content change after
 *      finalization would never trigger a fresh scan.
 *   2. DO NOT re-run when the parent re-renders with a fresh message object
 *      but unchanged content. This is the primary motivation for the
 *      useMemo([message.text]) gate; without it, every parent re-render
 *      with a new message reference (common during streaming sibling
 *      updates) would re-pay the scanner cost on every prior message.
 */
describe('AgentMessage memoizes scanner output', () => {
  beforeEach(() => {
    scanFileMock.mockClear();
    scanSymbolMock.mockClear();
  });

  it('re-runs the scanners when the message content actually changes', async () => {
    let bumpText: ((v: string) => void) | null = null;
    function Harness() {
      const [text, setText] = useState('open File.kt:42 please');
      useEffect(() => { bumpText = setText; }, []);
      const message: UiMessage = {
        ts: 1,
        type: 'SAY',
        say: 'TEXT',
        text,
      };
      return <AgentMessage message={message} isStreaming={false} />;
    }

    render(<Harness />);
    await waitFor(() => expect(scanFileMock).toHaveBeenCalled());
    const beforeFileCalls = scanFileMock.mock.calls.length;
    const beforeSymbolCalls = scanSymbolMock.mock.calls.length;

    // Change content — scanners must re-run.
    await act(async () => {
      bumpText?.('different content with Other.kt:99');
    });
    await waitFor(() =>
      expect(scanFileMock.mock.calls.length).toBeGreaterThan(beforeFileCalls)
    );

    expect(scanFileMock.mock.calls.length).toBeGreaterThan(beforeFileCalls);
    expect(scanSymbolMock.mock.calls.length).toBeGreaterThan(beforeSymbolCalls);
  });

  it('does not re-run scanners when parent re-renders with unchanged content', async () => {
    // Force the parent to re-render with a FRESH message object every render
    // (defeating React.memo's shallow prop compare) while keeping `text`
    // stable. The content-gated useMemo + effect deps must prevent the
    // scanner from re-firing.
    let bump: ((v: number) => void) | null = null;
    const text = 'open File.kt:42 please';

    function Harness() {
      const [n, setN] = useState(0);
      useEffect(() => { bump = setN; }, []);
      // Fresh object literal every render — React.memo sees a new reference
      // and re-renders AgentMessage, but `message.text` is identical so the
      // memoized scanContent keeps its value and the effect skips.
      const message: UiMessage = {
        ts: 1,
        type: 'SAY',
        say: 'TEXT',
        text,
      };
      void n;
      return <AgentMessage message={message} isStreaming={false} />;
    }

    render(<Harness />);
    await waitFor(() => expect(scanFileMock).toHaveBeenCalled());
    const initialFileCalls = scanFileMock.mock.calls.length;
    const initialSymbolCalls = scanSymbolMock.mock.calls.length;

    // Two forced parent re-renders, identical content.
    await act(async () => { bump?.(1); });
    await new Promise((r) => setTimeout(r, 30));
    await act(async () => { bump?.(2); });
    await new Promise((r) => setTimeout(r, 30));

    expect(scanFileMock.mock.calls.length).toBe(initialFileCalls);
    expect(scanSymbolMock.mock.calls.length).toBe(initialSymbolCalls);
  });
});
