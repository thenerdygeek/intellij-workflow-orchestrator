/**
 * Tests for editor-tab fullscreen mode — the flag flipped by
 * `AgentVisualizationEditor` when the user clicks "Open in editor tab" on a
 * RichBlock. When true, the chat shell strips its chrome (TopBar, InputBar,
 * etc.) and the single visualization block fills the editor pane via
 * `position: fixed; inset: 0` on the RichBlock wrapper.
 *
 * Covered here:
 * 1. chatStore.setEditorTabMode flips the flag in both directions.
 * 2. RichBlock wrapper switches to fixed-inset-0 chrome-stripped layout.
 * 3. RichBlock hides its header (and its action buttons) in editor-tab mode.
 * 4. ArtifactRenderer's iframe drops the content-height inline style in favor
 *    of h-full / w-full so the flex chain inside the fixed wrapper fills the
 *    pane.
 */
import { describe, it, expect, beforeEach, vi } from 'vitest';
import { render } from '@testing-library/react';
import { chatState, resetChatStore } from './chat-store-test-utils';

vi.mock('@/bridge/jcef-bridge', () => ({
  kotlinBridge: { navigateToFile: vi.fn() },
  openInEditorTab: vi.fn(),
}));

import { RichBlock } from '@/components/rich/RichBlock';
import { ArtifactRenderer } from '@/components/rich/ArtifactRenderer';

describe('chatStore.editorTabMode', () => {
  beforeEach(() => {
    resetChatStore();
    chatState().setEditorTabMode(false);
  });

  it('defaults to false', () => {
    expect(chatState().editorTabMode).toBe(false);
  });

  it('setEditorTabMode toggles the flag', () => {
    chatState().setEditorTabMode(true);
    expect(chatState().editorTabMode).toBe(true);
    chatState().setEditorTabMode(false);
    expect(chatState().editorTabMode).toBe(false);
  });
});

describe('RichBlock — editor-tab mode', () => {
  beforeEach(() => {
    resetChatStore();
    chatState().setEditorTabMode(false);
  });

  it('renders normal wrapper (rounded border + margin) when editorTabMode=false', () => {
    const { container } = render(
      <RichBlock type="mermaid" source="graph LR; A-->B;">
        <div data-testid="payload">payload</div>
      </RichBlock>,
    );
    const wrapper = container.firstElementChild as HTMLElement;
    expect(wrapper.className).toContain('rounded-lg');
    expect(wrapper.className).toContain('border');
    expect(wrapper.className).not.toContain('fixed');
  });

  it('switches to position:fixed inset-0 wrapper in editorTabMode=true', () => {
    chatState().setEditorTabMode(true);
    const { container } = render(
      <RichBlock type="mermaid" source="graph LR; A-->B;">
        <div data-testid="payload">payload</div>
      </RichBlock>,
    );
    const wrapper = container.firstElementChild as HTMLElement;
    expect(wrapper.className).toContain('fixed');
    expect(wrapper.className).toContain('inset-0');
    expect(wrapper.className).not.toContain('rounded-lg');
    expect(wrapper.className).not.toContain('border-[var(--border)]');
  });

  it('hides the header (no "Open in editor tab" / fullscreen / expand buttons) in editorTabMode', () => {
    chatState().setEditorTabMode(true);
    const { queryByLabelText } = render(
      <RichBlock type="mermaid" source="graph LR; A-->B;">
        <div data-testid="payload">payload</div>
      </RichBlock>,
    );
    // Header action buttons are gone — user is already in the editor tab.
    expect(queryByLabelText('Open in editor tab')).toBeNull();
    expect(queryByLabelText('Open fullscreen')).toBeNull();
    expect(queryByLabelText('Expand')).toBeNull();
    expect(queryByLabelText('Collapse')).toBeNull();
  });
});

describe('ArtifactRenderer — editor-tab mode', () => {
  beforeEach(() => {
    resetChatStore();
    chatState().setEditorTabMode(false);
  });

  it('iframe carries inline pixel height in normal mode', () => {
    const { container } = render(
      <ArtifactRenderer source="export default () => <div>x</div>" />,
    );
    const iframe = container.querySelector('iframe') as HTMLIFrameElement;
    expect(iframe).not.toBeNull();
    // Default initial height is 200px (state seed in ArtifactRenderer).
    expect(iframe.style.height).toMatch(/\d+px/);
  });

  it('iframe drops inline pixel height and uses h-full w-full in editor-tab mode', () => {
    chatState().setEditorTabMode(true);
    const { container } = render(
      <ArtifactRenderer source="export default () => <div>x</div>" />,
    );
    const iframe = container.querySelector('iframe') as HTMLIFrameElement;
    expect(iframe).not.toBeNull();
    // No inline pixel height — fills via CSS chain instead.
    expect(iframe.style.height).toBe('');
    expect(iframe.className).toContain('h-full');
    expect(iframe.className).toContain('w-full');
  });

  it('does not render the optional title strip in editor-tab mode', () => {
    chatState().setEditorTabMode(true);
    const { queryByText } = render(
      <ArtifactRenderer source="export default () => <div>x</div>" title="My Artifact" />,
    );
    expect(queryByText('My Artifact')).toBeNull();
  });
});
