import { render, screen, fireEvent, cleanup } from '@testing-library/react';
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { CommandPreview } from '@/components/agent/CommandPreview';

vi.mock('@/hooks/useShiki', () => ({
  useShiki: (code: string, _lang: string) => ({
    html: `<pre class="shiki"><code>${code.replace(/</g, '&lt;')}</code></pre>`,
    isLoading: false,
  }),
}));

function mockJcefEnvironment() {
  Object.defineProperty(window, 'location', {
    value: { ...window.location, origin: 'http://workflow-agent' },
    writable: true,
    configurable: true,
  });
}

function mockBrowserEnvironment() {
  Object.defineProperty(window, 'location', {
    value: { ...window.location, origin: 'http://localhost' },
    writable: true,
    configurable: true,
  });
}

describe('CommandPreview', () => {
  beforeEach(() => {
    cleanup();
    mockJcefEnvironment();
    (window as any)._copyToClipboard = vi.fn();
  });

  afterEach(() => {
    mockBrowserEnvironment();
  });

  it('renders the full multi-line command as a bash-highlighted code block', () => {
    render(
      <CommandPreview
        command={'echo hello\nls -la /tmp'}
        shell="/bin/bash"
        cwd="/home/me/project"
        env={[{ key: 'CI', value: '1' }]}
      />
    );
    const container = screen.getByTestId('command-preview');
    expect(container.textContent).toContain('echo hello');
    expect(container.textContent).toContain('ls -la /tmp');
    expect(container.querySelector('.shiki')).toBeTruthy();
    // CodeBlock header shows the language (rendered uppercase via CSS, DOM text is lowercase).
    expect(screen.getByText('bash')).toBeInTheDocument();
  });

  it('copy button copies the raw command text via the JCEF bridge', () => {
    render(
      <CommandPreview
        command="rm -rf build/"
        shell="/bin/bash"
        cwd="/x"
        env={[]}
      />
    );
    fireEvent.click(screen.getByTitle('Copy code'));
    expect((window as any)._copyToClipboard).toHaveBeenCalledWith('rm -rf build/');
  });

  it('renders shell and cwd chips plus an env chip per variable', () => {
    render(
      <CommandPreview
        command="x"
        shell="/bin/zsh"
        cwd="/tmp"
        env={[
          { key: 'FOO', value: 'bar' },
          { key: 'BAZ', value: 'qux' },
        ]}
      />
    );
    expect(screen.getByText('/bin/zsh')).toBeInTheDocument();
    expect(screen.getByText('/tmp')).toBeInTheDocument();
    expect(screen.getByText('FOO=bar')).toBeInTheDocument();
    expect(screen.getByText('BAZ=qux')).toBeInTheDocument();
  });

  it('shows a separate-stderr chip only when separateStderr=true', () => {
    const { rerender } = render(
      <CommandPreview command="x" shell="/bin/bash" cwd="/x" env={[]} />
    );
    expect(screen.queryByText(/separate-stderr/i)).not.toBeInTheDocument();

    rerender(
      <CommandPreview command="x" shell="/bin/bash" cwd="/x" env={[]} separateStderr />
    );
    expect(screen.getByText(/separate-stderr/i)).toBeInTheDocument();
  });
});
