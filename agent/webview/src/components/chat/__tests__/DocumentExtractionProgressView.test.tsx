// Copyright 2025-2026 Workflow Orchestrator Contributors
// SPDX-License-Identifier: Apache-2.0
import { render, screen, cleanup } from '@testing-library/react';
import { describe, it, expect, beforeEach, afterEach } from 'vitest';
import { useChatStore } from '@/stores/chatStore';
import { DocumentExtractionProgressView } from '@/components/chat/DocumentExtractionProgressView';

/**
 * Vitest scenarios for the document-extraction progress component.
 *
 * The component is a pure projection over `chatStore.documentExtraction`. We drive
 * the store directly (no bridge round-trip) and assert the rendered output.
 *
 * Coverage:
 *  (a) null state → renders nothing
 *  (b) with pagesTotal → shows "page {pagesDone} of {pagesTotal}"
 *  (c) without pagesTotal → shows elapsed only, no "page"
 */
describe('DocumentExtractionProgressView', () => {
  beforeEach(() => {
    cleanup();
    useChatStore.getState().clearChat();
  });

  afterEach(() => {
    cleanup();
  });

  it('(a) renders nothing when documentExtraction is null', () => {
    // Default state after clearChat is null.
    render(<DocumentExtractionProgressView />);
    expect(screen.queryByTestId('document-extraction-progress')).not.toBeInTheDocument();
    expect(screen.queryByTestId('document-extraction-label')).not.toBeInTheDocument();
  });

  it('(b) shows "page {pagesDone} of {pagesTotal}" when pagesTotal is known', () => {
    useChatStore.getState().setDocumentExtraction({
      stage: 'tables',
      pagesDone: 120,
      pagesTotal: 500,
      elapsedMs: 65_000,
    });

    render(<DocumentExtractionProgressView />);

    const label = screen.getByTestId('document-extraction-label');
    expect(label).toBeInTheDocument();
    expect(label.textContent).toContain('page 120 of 500');
    // Also shows elapsed
    expect(label.textContent).toContain('1:05');
  });

  it('(c) shows elapsed only and no "page" text when pagesTotal is null', () => {
    useChatStore.getState().setDocumentExtraction({
      stage: 'reading',
      pagesDone: 0,
      pagesTotal: null,
      elapsedMs: 30_000,
    });

    render(<DocumentExtractionProgressView />);

    const label = screen.getByTestId('document-extraction-label');
    expect(label).toBeInTheDocument();
    // Must show elapsed
    expect(label.textContent).toContain('0:30');
    // Must NOT contain "page"
    expect(label.textContent).not.toContain('page');
  });

  it('clears when clearDocumentExtraction is called', () => {
    useChatStore.getState().setDocumentExtraction({
      stage: 'finalizing',
      pagesDone: 10,
      pagesTotal: 10,
      elapsedMs: 5_000,
    });

    const { rerender } = render(<DocumentExtractionProgressView />);
    expect(screen.getByTestId('document-extraction-progress')).toBeInTheDocument();

    useChatStore.getState().clearDocumentExtraction();
    rerender(<DocumentExtractionProgressView />);

    expect(screen.queryByTestId('document-extraction-progress')).not.toBeInTheDocument();
  });
});
