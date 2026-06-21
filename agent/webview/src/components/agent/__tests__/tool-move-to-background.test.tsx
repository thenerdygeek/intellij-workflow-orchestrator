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

const moveToolToBackground = vi.fn()
const killToolCall = vi.fn()

beforeEach(() => {
  cleanup()
  moveToolToBackground.mockReset()
  killToolCall.mockReset()
  vi.spyOn(useChatStore, 'getState').mockReturnValue({ killToolCall, moveToolToBackground } as any)
})

afterEach(() => {
  cleanup()
  vi.restoreAllMocks()
})

function tc(over: Partial<{ id: string; name: string; status: string; args: string; result: string }> = {}) {
  return {
    id: 'tc9',
    name: 'search_code',
    status: 'RUNNING' as const,
    args: '{}',
    result: '',
    ...over,
  } as any
}

describe('move to background', () => {
  it('calls moveToolToBackground on click for a running generic tool', () => {
    render(<ToolCallChain toolCalls={[tc()]} />)
    const btn = screen.getByRole('button', { name: /^move to background$/i })
    expect(btn).toBeTruthy()
    fireEvent.click(btn)
    expect(moveToolToBackground).toHaveBeenCalledWith('tc9')
  })

  it('does not render Move to background when the tool has completed', () => {
    render(<ToolCallChain toolCalls={[tc({ status: 'COMPLETED' })]} />)
    expect(screen.queryByRole('button', { name: /^move to background$/i })).toBeNull()
  })

  it('suppresses Move to background for run_command, background_process, ask_user_input, agent', () => {
    for (const name of ['run_command', 'background_process', 'ask_user_input', 'agent']) {
      cleanup()
      render(<ToolCallChain toolCalls={[tc({ name })]} />)
      expect(screen.queryByRole('button', { name: /^move to background$/i })).toBeNull()
    }
  })
})
