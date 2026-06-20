/**
 * chatStore — prefix-approval resolution.
 *
 * `resolveApproval('approveCommandPrefix')` must call
 * `kotlinBridge.approveCommandPrefix(pending.commandPrefix)` (guarded on a
 * non-empty prefix). Mirrors the existing `allowForSession` →
 * `kotlinBridge.allowToolForSession(pending.toolName)` branch.
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';

/** Flush the dynamic `import('../bridge/jcef-bridge').then(...)` in resolveApproval. */
const flushImport = () => new Promise<void>((resolve) => setTimeout(resolve, 0));

const approveCommandPrefix = vi.fn();
const allowToolForSession = vi.fn();
const approveToolCall = vi.fn();
const denyToolCall = vi.fn();

vi.mock('../../bridge/jcef-bridge', () => ({
  kotlinBridge: { approveCommandPrefix, allowToolForSession, approveToolCall, denyToolCall },
}));

import { useChatStore } from '../chatStore';

beforeEach(() => {
  vi.clearAllMocks();
  useChatStore.setState({ pendingApproval: null } as never);
});

describe('resolveApproval — approveCommandPrefix', () => {
  it('calls kotlinBridge.approveCommandPrefix with the pending prefix', async () => {
    useChatStore.setState({
      pendingApproval: {
        toolName: 'run_command',
        riskLevel: 'MEDIUM',
        title: 'Run git add . ?',
        allowSessionApproval: false,
        commandPrefix: 'git add',
      },
    } as never);

    useChatStore.getState().resolveApproval('approveCommandPrefix');
    // resolveApproval dispatches via a dynamic import().then() — flush it.
    await flushImport();

    expect(approveCommandPrefix).toHaveBeenCalledTimes(1);
    expect(approveCommandPrefix).toHaveBeenCalledWith('git add');
    expect(useChatStore.getState().pendingApproval).toBeNull();
  });

  it('does NOT call approveCommandPrefix when the pending prefix is empty', async () => {
    useChatStore.setState({
      pendingApproval: {
        toolName: 'run_command',
        riskLevel: 'MEDIUM',
        title: 'Run x ?',
        allowSessionApproval: false,
        commandPrefix: '',
      },
    } as never);

    useChatStore.getState().resolveApproval('approveCommandPrefix');
    await flushImport();

    expect(approveCommandPrefix).not.toHaveBeenCalled();
  });
});

describe('showApproval — commandPrefix plumbing', () => {
  it('stores commandPrefix on the pending approval object', () => {
    useChatStore.getState().showApproval(
      'run_command',
      'MEDIUM',
      'Run git add . ?',
      [],
      undefined,
      undefined,
      false,
      undefined,
      undefined,
      undefined,
      'git add',
    );
    expect(useChatStore.getState().pendingApproval?.commandPrefix).toBe('git add');
  });
});
