/**
 * Tests for the RichBlock "Open in editor tab" button.
 *
 * This covers the bug reported where clicking "Open in editor tab" on an
 * artifact/visualization block did nothing (the Kotlin-side callback was never
 * wired — fixed in AgentController.wireSharedDashboardCallbacks).
 *
 * These tests verify the JS side: clicking the button must call
 * openInEditorTab(type, source) → window._openInEditorTab(JSON.stringify({type, content})).
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { RichBlock } from '@/components/rich/RichBlock';

// Mock jcef-bridge so openInEditorTab is observable
vi.mock('@/bridge/jcef-bridge', () => ({
  openInEditorTab: vi.fn(),
  kotlinBridge: {
    navigateToFile: vi.fn(),
    resolveSymbols: vi.fn(),
    validatePaths: vi.fn(),
  },
}));

import { openInEditorTab } from '@/bridge/jcef-bridge';

const SAMPLE_SOURCE = 'export default function App() { return <div>Hello</div>; }';

describe('RichBlock — Open in editor tab button', () => {
  beforeEach(() => {
    vi.clearAllMocks();
  });

  it('calls openInEditorTab with the correct type and source when button is clicked', () => {
    render(
      <RichBlock type="artifact" source={SAMPLE_SOURCE}>
        <div>content</div>
      </RichBlock>,
    );

    const btn = screen.getByLabelText('Open in editor tab');
    fireEvent.click(btn);

    expect(openInEditorTab).toHaveBeenCalledOnce();
    expect(openInEditorTab).toHaveBeenCalledWith('artifact', SAMPLE_SOURCE);
  });

  it('calls openInEditorTab for mermaid type with the diagram source', () => {
    const mermaidSource = 'graph TD\n  A --> B';
    render(
      <RichBlock type="mermaid" source={mermaidSource}>
        <div>diagram</div>
      </RichBlock>,
    );

    fireEvent.click(screen.getByLabelText('Open in editor tab'));

    expect(openInEditorTab).toHaveBeenCalledWith('mermaid', mermaidSource);
  });

  it('does not call openInEditorTab if the button is not clicked', () => {
    render(
      <RichBlock type="artifact" source={SAMPLE_SOURCE}>
        <div>content</div>
      </RichBlock>,
    );

    expect(openInEditorTab).not.toHaveBeenCalled();
  });
});
