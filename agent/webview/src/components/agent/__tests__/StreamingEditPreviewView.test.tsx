// Copyright 2025-2026 Workflow Orchestrator Contributors
// SPDX-License-Identifier: Apache-2.0
import { render, screen, cleanup } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { useChatStore } from '@/stores/chatStore';

// Mock DiffHtml to a marker so we don't have to drive diff2html in tests.
// We assert the marker rendered with the expected `diffSource` prop.
vi.mock('@/components/rich/DiffHtml', () => ({
  DiffHtml: ({ diffSource }: { diffSource: string }) => (
    <div data-testid="diff-html-mock">{diffSource}</div>
  ),
  preloadDiff2Html: () => {},
}));

import { StreamingEditPreviewView } from '@/components/agent/StreamingEditPreviewView';

/**
 * Vitest scenarios for the streaming `edit_file` preview component.
 *
 * The component is a pure projection over `chatStore.streamingEdits`. We drive
 * the store directly (no bridge round-trip) and assert the rendered output.
 *
 * Coverage:
 *  - Empty map → component renders nothing
 *  - Multiple active previews → one card per entry
 *  - status='streaming' → "live" pulse indicator visible
 *  - status='finalized' → "live" indicator hidden
 *  - Path is displayed from store entry
 *  - DiffHtml receives the diff as the `diffSource` prop
 */
describe('StreamingEditPreviewView', () => {
  beforeEach(() => {
    cleanup();
    // Reset to a known empty state before each scenario.
    useChatStore.getState().clearChat();
  });

  afterEach(() => {
    cleanup();
  });

  it('renders nothing when streamingEdits is empty', () => {
    render(<StreamingEditPreviewView />);
    expect(screen.queryByTestId('streaming-edit-preview-root')).not.toBeInTheDocument();
    expect(screen.queryByText(/streaming edit/i)).not.toBeInTheDocument();
  });

  it('renders one card per active streaming edit', () => {
    const store = useChatStore.getState();
    store.streamingEditOpen('edit-1-0', 'src/A.kt', '--- a/A\n+++ b/A\n@@ -1 +1 @@\n-x\n+y');
    store.streamingEditOpen('edit-1-1', 'src/B.kt', '--- a/B\n+++ b/B\n@@ -1 +1 @@\n-p\n+q');

    render(<StreamingEditPreviewView />);

    expect(screen.getAllByText(/streaming edit/i)).toHaveLength(2);
    expect(screen.getByTestId('streaming-edit-edit-1-0')).toBeInTheDocument();
    expect(screen.getByTestId('streaming-edit-edit-1-1')).toBeInTheDocument();
  });

  it('shows the "live" indicator when status is streaming', () => {
    useChatStore.getState().streamingEditOpen('edit-1-0', 'src/A.kt', 'diff body');
    render(<StreamingEditPreviewView />);
    expect(screen.getByTestId('streaming-edit-live-edit-1-0')).toBeInTheDocument();
    expect(screen.getByTestId('streaming-edit-live-edit-1-0')).toHaveTextContent('live');
  });

  it('hides the "live" indicator when status is finalized', () => {
    const store = useChatStore.getState();
    store.streamingEditOpen('edit-1-0', 'src/A.kt', 'diff body');
    store.streamingEditFinalize('edit-1-0');
    render(<StreamingEditPreviewView />);
    expect(screen.queryByTestId('streaming-edit-live-edit-1-0')).not.toBeInTheDocument();
  });

  it('displays the file path from the store entry', () => {
    useChatStore.getState().streamingEditOpen('edit-1-0', 'src/main/Kt/Foo.kt', 'diff body');
    render(<StreamingEditPreviewView />);
    const pathEl = screen.getByTestId('streaming-edit-path-edit-1-0');
    expect(pathEl).toHaveTextContent('src/main/Kt/Foo.kt');
  });

  it('renders DiffHtml with the current diff prop', () => {
    const initial = '--- a\n+++ b\n@@ -1 +1 @@\n-old\n+new';
    const updated = '--- a\n+++ b\n@@ -1 +1 @@\n-old\n+newer';
    const store = useChatStore.getState();
    store.streamingEditOpen('edit-1-0', 'src/A.kt', initial);

    const { rerender } = render(<StreamingEditPreviewView />);
    expect(screen.getByTestId('diff-html-mock')).toHaveTextContent('new');

    store.streamingEditUpdate('edit-1-0', updated);
    rerender(<StreamingEditPreviewView />);
    expect(screen.getByTestId('diff-html-mock')).toHaveTextContent('newer');
  });

  it('drops a preview when cancelled', () => {
    const store = useChatStore.getState();
    store.streamingEditOpen('edit-1-0', 'src/A.kt', 'diff body');
    const { rerender } = render(<StreamingEditPreviewView />);
    expect(screen.getByTestId('streaming-edit-edit-1-0')).toBeInTheDocument();

    store.streamingEditCancel('edit-1-0');
    rerender(<StreamingEditPreviewView />);
    expect(screen.queryByTestId('streaming-edit-edit-1-0')).not.toBeInTheDocument();
    expect(screen.queryByTestId('streaming-edit-preview-root')).not.toBeInTheDocument();
  });
});
