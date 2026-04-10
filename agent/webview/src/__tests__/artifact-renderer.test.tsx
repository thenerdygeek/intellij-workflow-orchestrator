/**
 * Tests for ArtifactRenderer — the sandboxed iframe component
 * that renders interactive React artifacts in the chat UI.
 *
 * Key scenarios:
 * 1. Iframe renders with correct sandbox attribute (allow-scripts only)
 * 2. isLoading=false always passed to RichBlock (iframe handles own loading overlay)
 * 3. Same-origin is NOT allowed (security)
 * 4. Accepts source and title props without errors
 *
 * RichBlock conditionally hides children during loading. ArtifactRenderer
 * always passes isLoading=false so the iframe stays in the DOM and can
 * initialize the sandbox (preventing the ready-message deadlock).
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import type { ReactNode } from 'react';

// Track props passed to RichBlock so we can assert on isLoading, etc.
const richBlockProps: Record<string, unknown>[] = [];

// Mock RichBlock to always render children (bypass loading/error gating)
vi.mock('@/components/rich/RichBlock', () => ({
  RichBlock: (props: { children?: ReactNode; isLoading?: boolean; type?: string; source?: string }) => {
    richBlockProps.push({ ...props });
    return <div data-testid="rich-block">{props.children}</div>;
  },
}));

// Mock jcef-bridge
vi.mock('@/bridge/jcef-bridge', () => ({
  kotlinBridge: { navigateToFile: vi.fn() },
  openInEditorTab: vi.fn(),
}));

import { ArtifactRenderer } from '@/components/rich/ArtifactRenderer';

const SAMPLE_SOURCE = 'export default function App({ bridge }) { return <div>Hello</div>; }';

describe('ArtifactRenderer', () => {
  beforeEach(() => {
    vi.clearAllMocks();
    richBlockProps.length = 0;
  });

  it('renders iframe with sandbox attribute', () => {
    const { container } = render(
      <ArtifactRenderer source={SAMPLE_SOURCE} title="Test" />,
    );

    const iframe = container.querySelector('iframe');
    expect(iframe).toBeInTheDocument();
    expect(iframe?.getAttribute('sandbox')).toBe('allow-scripts');
  });

  it('always passes isLoading=false to RichBlock (iframe handles its own loading)', () => {
    render(<ArtifactRenderer source={SAMPLE_SOURCE} title="Test" />);

    // ArtifactRenderer now always passes isLoading=false to RichBlock so the
    // iframe is always rendered in the DOM (preventing the deadlock where the
    // iframe was hidden by the loading skeleton and could never send 'ready').
    expect(richBlockProps.length).toBeGreaterThan(0);
    const lastProps = richBlockProps[richBlockProps.length - 1];
    expect(lastProps.isLoading).toBe(false);
  });

  it('does not allow same-origin', () => {
    const { container } = render(
      <ArtifactRenderer source={SAMPLE_SOURCE} title="Test" />,
    );

    const iframe = container.querySelector('iframe');
    expect(iframe).toBeInTheDocument();
    const sandbox = iframe?.getAttribute('sandbox') ?? '';
    expect(sandbox).not.toContain('allow-same-origin');
  });

  it('accepts source and title props', () => {
    const { container } = render(
      <ArtifactRenderer source={SAMPLE_SOURCE} title="Module Dependencies" />,
    );

    // Title should appear in the component
    expect(screen.getByText('Module Dependencies')).toBeInTheDocument();

    // Iframe should be present
    const iframe = container.querySelector('iframe');
    expect(iframe).toBeInTheDocument();
  });
});
