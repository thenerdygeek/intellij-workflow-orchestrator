/**
 * The sent user-message bubble (AgentMessage, say:'USER_MESSAGE') must reflect
 * what the user composed:
 *   - an attached FILE shows as a file chip (filename), not a broken <img>
 *     (only image attachments are thumbnails);
 *   - a ticket mention chip keeps its validated (green) look, not flip to the
 *     generic blue used for file/folder mentions.
 */
import { describe, it, expect, afterEach } from 'vitest';
import { render, screen } from '@testing-library/react';
import type { UiMessage } from '@/bridge/types';
import { AgentMessage } from '@/components/chat/AgentMessage';

afterEach(() => { document.body.innerHTML = ''; });

describe('user message bubble — attachments', () => {
  it('renders a file attachment as a chip (filename), not an image', () => {
    const msg: UiMessage = {
      ts: 1, type: 'SAY', say: 'USER_MESSAGE', text: 'see the spec',
      attachments: [
        { sha256: 'f'.repeat(64), mime: 'application/pdf', size: 2048, originalFilename: 'spec.pdf', kind: 'file' },
        { sha256: 'a'.repeat(64), mime: 'image/png', size: 512, originalFilename: 'shot.png', kind: 'image' },
      ],
    } as UiMessage;
    const { container } = render(<AgentMessage message={msg} />);

    // File chip: the filename is shown as text, and it is NOT rendered via <img>.
    expect(screen.getByText('spec.pdf')).toBeInTheDocument();
    expect(container.querySelector('img[alt="spec.pdf"]')).toBeNull();

    // Image attachment still renders as a thumbnail.
    expect(container.querySelector(`img[src*="${'a'.repeat(64)}"]`)).not.toBeNull();
  });
});

describe('user message bubble — mention chips', () => {
  it('renders a ticket mention chip in its validated (green) color, not blue', () => {
    const msg: UiMessage = {
      ts: 2, type: 'SAY', say: 'USER_MESSAGE', text: 'fix #PROJ-1 please',
      mentions: [{ type: 'ticket', label: 'PROJ-1', path: 'PROJ-1' }],
    } as UiMessage;
    const { container } = render(<AgentMessage message={msg} />);

    const chip = container.querySelector('span[title="PROJ-1"]') as HTMLElement | null;
    expect(chip, 'ticket chip should render').not.toBeNull();
    // Green (var(--success)) — matching the validated input chip — not the blue
    // var(--accent-read) used for file/folder mentions.
    expect(chip!.style.color).toContain('--success');
    expect(chip!.style.color).not.toContain('--accent-read');
  });

  it('keeps file mention chips blue (unchanged)', () => {
    const msg: UiMessage = {
      ts: 3, type: 'SAY', say: 'USER_MESSAGE', text: 'open @App.tsx',
      mentions: [{ type: 'file', label: 'App.tsx', path: 'src/App.tsx' }],
    } as UiMessage;
    const { container } = render(<AgentMessage message={msg} />);
    const chip = container.querySelector('span[title="src/App.tsx"]') as HTMLElement | null;
    expect(chip, 'file chip should render').not.toBeNull();
    expect(chip!.style.color).toContain('--accent-read');
  });
});
