import { describe, it, expect, vi } from 'vitest';
import { render, screen } from '@testing-library/react';
import type { UiMessage } from '../bridge/types';

vi.mock('../util/file-link-scanner', () => ({ scanAndLinkify: vi.fn(async () => {}) }));
vi.mock('../util/symbol-link-scanner', () => ({ scanAndSymbolLinkify: vi.fn(async () => {}) }));

import { AgentMessage } from '../components/chat/AgentMessage';

/**
 * Part 2 — delegated messages get a distinct bubble tint + thin left accent
 * stripe + a "delegated · {repo}" pill on EVERY bubble. The pill always shows
 * the REPO name, never an "ide-NNN" / "IDE-A" / "IDE-B" identifier.
 */
describe('AgentMessage delegated styling', () => {
  const repo = 'team/backend-service';

  it('renders the pill, tint, and left accent stripe on a delegated user bubble', () => {
    const message: UiMessage = {
      ts: Date.now(),
      type: 'SAY',
      say: 'USER_MESSAGE',
      text: 'do the thing',
      delegated: true,
      delegatorRepo: repo,
    };
    const { container } = render(<AgentMessage message={message} />);

    // The pill (copied from analyzedImageBadge shape) carries the repo.
    const pill = screen.getByText(new RegExp(`delegated · ${repo}`));
    expect(pill).toBeTruthy();
    // Never leaks an ide-id.
    expect(container.textContent).not.toMatch(/ide-\d+/i);
    expect(container.textContent).not.toMatch(/IDE-[AB]/);

    // The bubble carries a left accent stripe + tinted background via inline style.
    const bubble = container.querySelector('[data-delegated="true"]') as HTMLElement | null;
    expect(bubble).toBeTruthy();
    expect(bubble!.getAttribute('style')).toMatch(/border-left/i);
    expect(bubble!.getAttribute('style')).toMatch(/badge-read-fg/);
  });

  it('renders the pill on a delegated assistant bubble too (EVERY bubble)', () => {
    const message: UiMessage = {
      ts: Date.now(),
      type: 'SAY',
      say: 'TEXT',
      text: 'working on it',
      delegated: true,
      delegatorRepo: repo,
    };
    const { container } = render(<AgentMessage message={message} />);
    expect(screen.getByText(new RegExp(`delegated · ${repo}`))).toBeTruthy();
    const bubble = container.querySelector('[data-delegated="true"]') as HTMLElement | null;
    expect(bubble).toBeTruthy();
    expect(bubble!.getAttribute('style')).toMatch(/border-left/i);
  });

  it('does NOT render the pill or stripe on a non-delegated message', () => {
    const message: UiMessage = {
      ts: Date.now(),
      type: 'SAY',
      say: 'USER_MESSAGE',
      text: 'normal message',
    };
    const { container } = render(<AgentMessage message={message} />);
    expect(screen.queryByText(/delegated ·/)).toBeNull();
    expect(container.querySelector('[data-delegated="true"]')).toBeNull();
  });
});
