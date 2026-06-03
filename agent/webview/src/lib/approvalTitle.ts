import { describeMemoryOp } from './describeMemoryOp';

/**
 * Approval-card title. For memory writes, reuse the memory verb label
 * (e.g. "Updating memory · user_prefs"); otherwise the generic approval title.
 */
export function approvalTitle(toolName: string, riskLevel: string, path?: string): string {
  const memoryOp = path ? describeMemoryOp(toolName, path) : null;
  return memoryOp ? memoryOp.title : `Approve ${toolName}? (${riskLevel} risk)`;
}
