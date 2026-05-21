import { render, screen, cleanup } from '@testing-library/react';
import userEvent from '@testing-library/user-event';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { DiffHtml } from '../DiffHtml';

// diff2html is heavy and async; we only care about our own filename/header DOM here.
// Replace it with a stub so the component falls back to its raw-diff renderer
// (which already contains the filename div we test).
vi.mock('diff2html', () => ({
  html: () => '<div data-testid="diff2html-stub"></div>',
}));
vi.mock('diff2html/bundles/css/diff2html.min.css', () => ({}));

describe('DiffHtml — filename hyperlink with real line numbers', () => {
  beforeEach(() => {
    // Re-stub on each test — setup.ts defines _navigateToFile as a vi.fn,
    // but we want a fresh spy per test so call assertions don't leak.
    (window as any)._navigateToFile = vi.fn();
  });

  afterEach(() => {
    cleanup();
    vi.clearAllMocks();
  });

  it('renders filename with parsed start line from @@ header', () => {
    const diff = `--- a/src/Foo.kt
+++ b/src/Foo.kt
@@ -42,3 +42,4 @@
 line 42
-line 43 old
+line 43 new
+line 44 added
 line 45`;
    render(<DiffHtml diffSource={diff} />);
    // The clickable filename button (role=button) must show "src/Foo.kt" + ":42".
    const filenameBtn = screen.getByRole('button', { name: /src\/Foo\.kt/ });
    expect(filenameBtn).toBeInTheDocument();
    expect(filenameBtn).toHaveTextContent(/:42/);
  });

  it('clicking the filename invokes _navigateToFile with path:line', async () => {
    const user = userEvent.setup();
    const diff = `--- a/src/Foo.kt
+++ b/src/Foo.kt
@@ -42,3 +42,4 @@
 line 42
-line 43 old
+line 43 new`;
    render(<DiffHtml diffSource={diff} />);
    const btn = screen.getByRole('button', { name: /src\/Foo\.kt/ });
    await user.click(btn);
    expect((window as any)._navigateToFile).toHaveBeenCalledWith('src/Foo.kt:42');
  });

  it('falls back to line 1 if no @@ header', () => {
    const diff = `--- a/x.kt
+++ b/x.kt
`;
    render(<DiffHtml diffSource={diff} />);
    const filenameBtn = screen.getByRole('button', { name: /x\.kt/ });
    expect(filenameBtn).toHaveTextContent(/:1/);
  });

  it('keyboard Enter on filename triggers navigation', async () => {
    const user = userEvent.setup();
    const diff = `--- a/x.kt
+++ b/x.kt
@@ -10,1 +10,1 @@
-old
+new`;
    render(<DiffHtml diffSource={diff} />);
    const link = screen.getByRole('button', { name: /x\.kt/ });
    link.focus();
    await user.keyboard('{Enter}');
    expect((window as any)._navigateToFile).toHaveBeenCalledWith('x.kt:10');
  });
});
