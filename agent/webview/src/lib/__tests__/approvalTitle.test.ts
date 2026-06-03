// Copyright 2025-2026 Workflow Orchestrator Contributors
// SPDX-License-Identifier: Apache-2.0
import { describe, it, expect } from 'vitest';
import { approvalTitle } from '../approvalTitle';

const MEM = '/home/u/.workflow-orchestrator/proj/agent/memory';

describe('approvalTitle', () => {
  it('uses the memory verb for a memory edit', () => {
    expect(approvalTitle('edit_file', 'LOW', `${MEM}/user_prefs.md`)).toBe('Updating memory · prefs');
  });

  it('uses the memory verb for a memory create', () => {
    expect(approvalTitle('create_file', 'LOW', `${MEM}/project_x.md`)).toBe('Creating memory · x (project)');
  });

  it('falls back to the default title for a non-memory edit', () => {
    expect(approvalTitle('edit_file', 'LOW', '/home/u/proj/src/Main.kt')).toBe('Approve edit_file? (LOW risk)');
  });

  it('falls back to the default title when no path is given', () => {
    expect(approvalTitle('run_command', 'HIGH', undefined)).toBe('Approve run_command? (HIGH risk)');
  });
});
