/**
 * Tool UI schema types.
 * Adapted from assistant-ui/tool-ui — Zod schemas replaced with plain TypeScript types.
 * Source: https://github.com/assistant-ui/tool-ui
 */

import type { ReactNode } from "react";

/**
 * Every tool UI should have a unique identifier that:
 * - Is stable across re-renders
 * - Is meaningful (not auto-generated)
 * - Is unique within the conversation
 *
 * Format recommendation: `{component-type}-{semantic-identifier}`
 * Examples: "data-table-expenses-q3", "option-list-deploy-target"
 */
export type ToolUIId = string;

/**
 * Primary role of a Tool UI surface in a chat context.
 */
export type ToolUIRole =
  | "information"
  | "decision"
  | "control"
  | "state"
  | "composite";

export type ToolUIReceiptOutcome =
  | "success"
  | "partial"
  | "failed"
  | "cancelled";

/**
 * Optional receipt metadata: a durable summary of an outcome.
 */
export interface ToolUIReceipt {
  outcome: ToolUIReceiptOutcome;
  summary: string;
  identifiers?: Record<string, string>;
  at: string;
}

/**
 * Base schema for Tool UI payloads (id + optional role/receipt).
 */
export interface ToolUISurface {
  id: ToolUIId;
  role?: ToolUIRole;
  receipt?: ToolUIReceipt;
}

export interface Action {
  id: string;
  label: string;
  /** Canonical narration the assistant can use after this action is taken. */
  sentence?: string;
  confirmLabel?: string;
  variant?: "default" | "destructive" | "secondary" | "ghost" | "outline";
  icon?: ReactNode;
  loading?: boolean;
  disabled?: boolean;
  shortcut?: string;
}

export type LocalAction = Action;
export type DecisionAction = Action;

export interface DecisionResult<
  TPayload extends Record<string, unknown> = Record<string, unknown>,
> {
  kind: "decision";
  version: 1;
  decisionId: string;
  actionId: string;
  actionLabel: string;
  at: string;
  payload?: TPayload;
}

export function createDecisionResult<
  TPayload extends Record<string, unknown> = Record<string, unknown>,
>(args: {
  decisionId: string;
  action: { id: string; label: string };
  payload?: TPayload;
}): DecisionResult<TPayload> {
  return {
    kind: "decision",
    version: 1,
    decisionId: args.decisionId,
    actionId: args.action.id,
    actionLabel: args.action.label,
    at: new Date().toISOString(),
    payload: args.payload,
  };
}

export interface ActionsConfig {
  items: Action[];
  align?: "left" | "center" | "right";
  confirmTimeout?: number;
}

export type SerializableAction = Omit<Action, "icon" | "className">;

export type SerializableActionsConfig = {
  items: SerializableAction[];
  align?: "left" | "center" | "right";
  confirmTimeout?: number;
};
