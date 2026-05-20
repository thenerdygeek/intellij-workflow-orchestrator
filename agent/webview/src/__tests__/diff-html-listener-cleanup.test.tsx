import { describe, it, expect, vi } from 'vitest';
import { render, cleanup, waitFor } from '@testing-library/react';
import { DiffHtml } from '../components/rich/DiffHtml';

describe('DiffHtml lifecycle', () => {
  it('removes the delegated click listener from the diff container on unmount', async () => {
    const { container, unmount } = render(
      <DiffHtml
        diffSource={`--- a/a.kt\n+++ b/a.kt\n@@ -1 +1 @@\n-x\n+y`}
        onAcceptHunk={() => {}}
        onRejectHunk={() => {}}
      />
    );
    // Wait for diff2html to populate innerHTML and the effect to attach.
    await waitFor(() => {
      expect(container.querySelector('[data-diff-action]')).not.toBeNull();
    });

    // The useEffect attaches click on containerRef.current — the <div class="diff-container">.
    const diffContainer = container.querySelector('.diff-container') as HTMLElement;
    expect(diffContainer).not.toBeNull();

    const spy = vi.spyOn(diffContainer, 'removeEventListener');
    unmount();
    expect(spy).toHaveBeenCalledWith('click', expect.any(Function));
    cleanup();
  });
});
