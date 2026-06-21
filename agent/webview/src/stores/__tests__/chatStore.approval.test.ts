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

  it('stores the toolName on the pending approval object', () => {
    useChatStore.getState().showApproval(
      'run_command',
      'HIGH',
      'Run npm install ?',
      [],
      undefined,
      undefined,
      false,
      undefined,
      undefined,
      undefined,
      'npm install',
    );
    const pending = useChatStore.getState().pendingApproval;
    expect(pending?.toolName).toBe('run_command');
    expect(pending?.commandPrefix).toBe('npm install');
  });
});

describe('resolveApproval — regression: sibling decisions still call their bridge methods', () => {
  it('resolveApproval("approve") calls kotlinBridge.approveToolCall', async () => {
    useChatStore.setState({
      pendingApproval: {
        toolName: 'edit_file',
        riskLevel: 'MEDIUM',
        title: 'Edit foo.kt ?',
        allowSessionApproval: true,
      },
    } as never);

    useChatStore.getState().resolveApproval('approve');
    await flushImport();

    expect(approveToolCall).toHaveBeenCalledTimes(1);
    expect(denyToolCall).not.toHaveBeenCalled();
  });

  it('resolveApproval("deny") calls kotlinBridge.denyToolCall', async () => {
    useChatStore.setState({
      pendingApproval: {
        toolName: 'edit_file',
        riskLevel: 'HIGH',
        title: 'Edit bar.kt ?',
        allowSessionApproval: true,
      },
    } as never);

    useChatStore.getState().resolveApproval('deny');
    await flushImport();

    expect(denyToolCall).toHaveBeenCalledTimes(1);
    expect(approveToolCall).not.toHaveBeenCalled();
  });

  it('resolveApproval("allowForSession") calls kotlinBridge.allowToolForSession with the toolName', async () => {
    useChatStore.setState({
      pendingApproval: {
        toolName: 'edit_file',
        riskLevel: 'LOW',
        title: 'Edit baz.kt ?',
        allowSessionApproval: true,
      },
    } as never);

    useChatStore.getState().resolveApproval('allowForSession');
    await flushImport();

    expect(allowToolForSession).toHaveBeenCalledTimes(1);
    expect(allowToolForSession).toHaveBeenCalledWith('edit_file');
  });
});

describe('resolveApproval("approveCommandPrefix") — guard when prefix is absent', () => {
  it('does NOT call approveCommandPrefix when commandPrefix is undefined', async () => {
    useChatStore.setState({
      pendingApproval: {
        toolName: 'run_command',
        riskLevel: 'MEDIUM',
        title: 'Run cmd ?',
        allowSessionApproval: false,
        // commandPrefix intentionally absent
      },
    } as never);

    useChatStore.getState().resolveApproval('approveCommandPrefix');
    await flushImport();

    expect(approveCommandPrefix).not.toHaveBeenCalled();
  });
});

describe('addToolCall — autoApproved fields', () => {
  it('stores autoApproved and autoApproveReason on the tool call when provided', () => {
    useChatStore.getState().addToolCall('tc-auto-1', 'run_command', '{}', 'RUNNING' as any, undefined, true, 'safe');
    const tc = useChatStore.getState().activeToolCalls.get('tc-auto-1');
    expect(tc?.autoApproved).toBe(true);
    expect(tc?.autoApproveReason).toBe('safe');
  });

  it('does NOT set autoApproved or autoApproveReason when they are not provided', () => {
    useChatStore.getState().addToolCall('tc-no-auto-1', 'edit_file', '{}', 'RUNNING' as any);
    const tc = useChatStore.getState().activeToolCalls.get('tc-no-auto-1');
    expect(tc?.autoApproved).toBeFalsy();
    expect(tc?.autoApproveReason).toBeFalsy();
  });
});
