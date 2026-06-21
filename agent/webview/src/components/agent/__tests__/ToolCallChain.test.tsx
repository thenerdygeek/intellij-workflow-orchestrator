// Copyright 2025-2026 Workflow Orchestrator Contributors
// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest'
import { render, screen, fireEvent, cleanup } from '@testing-library/react'
import { ToolCallChain } from '../ToolCallChain'
import { useChatStore } from '@/stores/chatStore'

// Mocks for heavy dependencies used inside ToolCallChain / ToolCallItem
vi.mock('@/hooks/useShiki', () => ({
  useShiki: () => ({ html: null, isLoading: false }),
}))

vi.mock('@/components/rich/DiffHtml', () => ({
  DiffHtml: () => <div data-testid="diff-html-mock" />,
  preloadDiff2Html: () => {},
}))

vi.mock('@/util/file-link-scanner', () => ({
  scanAndLinkify: () => Promise.resolve(),
  PATH_REGEX: /(?:never-match-please)/g,
}))

const killToolCall = vi.fn()

beforeEach(() => {
  cleanup()
  killToolCall.mockReset()
  vi.spyOn(useChatStore, 'getState').mockReturnValue({ killToolCall } as any)
})

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
})

function tc(over: Partial<{ id: string; name: string; status: string; args: string; result: string }> = {}) {
  return {
    id: 'call-1',
    name: 'web_fetch',
    status: 'RUNNING' as const,
    args: '{}',
    result: '',
    ...over,
  } as any
}

describe('ToolCallChain universal Stop button', () => {
  it('renders a Stop button for a running non-suppressed tool and calls killToolCall(id)', () => {
    render(<ToolCallChain toolCalls={[tc()]} />)
    // aria-label="Stop" is the exact accessible name of the header Stop button
    const stop = screen.getByRole('button', { name: /^stop$/i })
    expect(stop).toBeTruthy()
    fireEvent.click(stop)
    expect(killToolCall).toHaveBeenCalledWith('call-1')
  })

  it('does not render Stop when the tool has completed', () => {
    render(<ToolCallChain toolCalls={[tc({ status: 'COMPLETED' })]} />)
    expect(screen.queryByRole('button', { name: /^stop$/i })).toBeNull()
  })

  it('suppresses Stop for ask_user_input, run_command, background_process', () => {
    for (const name of ['ask_user_input', 'run_command', 'background_process']) {
      cleanup()
      render(<ToolCallChain toolCalls={[tc({ name })]} />)
      // The header Stop button has aria-label="Stop" (exact). The in-terminal stop
      // button has title="Stop process" — we must not count that as the header button.
      expect(screen.queryByRole('button', { name: /^stop$/i })).toBeNull()
    }
  })

  it('suppresses Stop for the agent tool (per-worker Kill is used instead)', () => {
    render(<ToolCallChain toolCalls={[tc({ name: 'agent' })]} />)
    expect(screen.queryByRole('button', { name: /^stop$/i })).toBeNull()
  })
})

describe('ToolCallChain auto-approved badge', () => {
  it('renders an "auto-approved · {reason}" badge when autoApproved is true', () => {
    render(
      <ToolCallChain
        toolCalls={[tc({ name: 'run_command', status: 'COMPLETED', autoApproved: true, autoApproveReason: 'safe' })]}
      />,
    )
    expect(screen.getByText(/auto-approved/i)).toBeTruthy()
    expect(screen.getByText(/safe/)).toBeTruthy()
  })

  it('does not render the auto-approved badge when autoApproved is absent', () => {
    render(<ToolCallChain toolCalls={[tc({ name: 'run_command', status: 'COMPLETED' })]} />)
    expect(screen.queryByText(/auto-approved/i)).toBeNull()
  })

  it('renders the badge for a session-rule reason', () => {
    render(
      <ToolCallChain
        toolCalls={[tc({ name: 'run_command', status: 'COMPLETED', autoApproved: true, autoApproveReason: 'session rule: git add' })]}
      />,
    )
    expect(screen.getByText('auto-approved · session rule: git add')).toBeTruthy()
  })

  it('badge text exactly matches "auto-approved · <reason>"', () => {
    render(
      <ToolCallChain
        toolCalls={[tc({ name: 'run_command', status: 'COMPLETED', autoApproved: true, autoApproveReason: 'safe' })]}
      />,
    )
    expect(screen.getByText('auto-approved · safe')).toBeTruthy()
  })

  it('does NOT render the badge when autoApproved is false even if autoApproveReason is set', () => {
    render(
      <ToolCallChain
        toolCalls={[tc({ name: 'run_command', status: 'COMPLETED', autoApproved: false, autoApproveReason: 'safe' })]}
      />,
    )
    expect(screen.queryByText(/auto-approved/i)).toBeNull()
  })

  it('does NOT render the badge when autoApproved is undefined even if autoApproveReason is set', () => {
    render(
      <ToolCallChain
        toolCalls={[tc({ name: 'run_command', status: 'COMPLETED', autoApproveReason: 'safe' })]}
      />,
    )
    expect(screen.queryByText(/auto-approved/i)).toBeNull()
  })
})
