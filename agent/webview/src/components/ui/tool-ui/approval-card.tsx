/**
 * ApprovalCard — interactive approve/deny card with receipt mode.
 * Adapted from assistant-ui/tool-ui — removed "use client", Zod, adapter imports.
 * Source: https://github.com/assistant-ui/tool-ui
 */

import * as React from "react";
import { cn } from "@/lib/utils";
import { Separator } from "@/components/ui/separator";

import { icons, Check, X } from "lucide-react";

// ---------- Types (extracted from Zod schemas) ----------

export interface MetadataItem {
  key: string;
  value: string;
}

export type ApprovalDecision = "approved" | "denied";

export interface SerializableApprovalCard {
  id: string;
  role?: "information" | "decision" | "control" | "state" | "composite";
  title: string;
  description?: string;
  icon?: string;
  metadata?: MetadataItem[];
  variant?: "default" | "destructive";
  confirmLabel?: string;
  cancelLabel?: string;
  choice?: ApprovalDecision;
}

export interface ApprovalCardProps extends SerializableApprovalCard {
  className?: string;
  onConfirm?: () => void | Promise<void>;
  onCancel?: () => void | Promise<void>;
}

// ---------- Helpers ----------

type LucideIcon = React.ComponentType<{ className?: string }>;

function getLucideIcon(name: string): LucideIcon | null {
  const pascalName = name
    .split("-")
    .map((part) => part.charAt(0).toUpperCase() + part.slice(1))
    .join("");

  const Icon = icons[pascalName as keyof typeof icons];
  return Icon ?? null;
}

// ---------- Receipt sub-component ----------

interface ApprovalCardReceiptProps {
  id: string;
  title: string;
  choice: ApprovalDecision;
  actionLabel?: string;
  className?: string;
}

function ApprovalCardReceipt({
  id,
  title,
  choice,
  actionLabel,
  className,
}: ApprovalCardReceiptProps) {
  const isApproved = choice === "approved";
  const displayLabel = actionLabel ?? (isApproved ? "Approved" : "Denied");

  return (
    <div
      className={cn(
        "flex w-full flex-col",
        "text-foreground",
        className,
      )}
      data-slot="approval-card"
      data-tool-ui-id={id}
      data-receipt="true"
      role="status"
      aria-label={displayLabel}
    >
      <div className="flex w-full items-center gap-2 rounded-lg border px-3 py-1.5"
        style={{ backgroundColor: 'var(--tool-bg, rgba(0,0,0,0.1))', borderColor: 'var(--border)' }}>
        <span
          className={cn(
            "flex size-5 shrink-0 items-center justify-center rounded-full",
            isApproved ? "text-[var(--success)]" : "text-[var(--fg-muted)]",
          )}
          style={{ backgroundColor: 'var(--hover-overlay-strong)' }}
        >
          {isApproved ? (
            <Check className="size-3" />
          ) : (
            <X className="size-3" />
          )}
        </span>
        <div className="flex items-center gap-1.5">
          <span className="text-[11px] font-medium">{displayLabel}</span>
          <span className="text-[11px]" style={{ color: 'var(--fg-muted)' }}>{title}</span>
        </div>
      </div>
    </div>
  );
}

// ---------- Main component ----------

export function ApprovalCard({
  id,
  title,
  description,
  icon,
  metadata,
  variant,
  confirmLabel,
  cancelLabel,
  className,
  choice,
  onConfirm,
  onCancel,
}: ApprovalCardProps) {
  const resolvedVariant = variant ?? "default";
  const resolvedConfirmLabel = confirmLabel ?? "Approve";
  const resolvedCancelLabel = cancelLabel ?? "Deny";
  const Icon = icon ? getLucideIcon(icon) : null;

  const handleAction = React.useCallback(
    async (actionId: string) => {
      if (actionId === "confirm") {
        await onConfirm?.();
      } else if (actionId === "cancel") {
        await onCancel?.();
      }
    },
    [onConfirm, onCancel],
  );

  const handleKeyDown = React.useCallback(
    (event: React.KeyboardEvent) => {
      if (event.key === "Escape") {
        event.preventDefault();
        onCancel?.();
      }
    },
    [onCancel],
  );

  const isDestructive = resolvedVariant === "destructive";

  const confirmVariant = isDestructive ? "destructive" : "default";
  const actions = [
    { id: "cancel", label: resolvedCancelLabel, variant: "ghost" },
    { id: "confirm", label: resolvedConfirmLabel, variant: confirmVariant },
  ];

  const viewKey = choice ? `receipt-${choice}` : "interactive";

  return (
    <div key={viewKey} className="contents">
      {choice ? (
        <ApprovalCardReceipt
          id={id}
          title={title}
          choice={choice}
          className={className}
        />
      ) : (
        <article
          className={cn(
            "flex w-full flex-col gap-1.5",
            "text-foreground",
            className,
          )}
          data-slot="approval-card"
          data-tool-ui-id={id}
          role="dialog"
          aria-labelledby={`${id}-title`}
          aria-describedby={description ? `${id}-description` : undefined}
          onKeyDown={handleKeyDown}
        >
          <div className="flex w-full flex-col gap-2 rounded-lg border px-3 py-2.5"
            style={{ backgroundColor: 'var(--tool-bg, rgba(0,0,0,0.1))', borderColor: 'var(--border)' }}>
            <div className="flex items-start gap-2">
              {Icon && (
                <span
                  className={cn(
                    "flex size-6 shrink-0 items-center justify-center rounded-md",
                    isDestructive
                      ? "bg-destructive/10 text-destructive"
                      : "bg-primary/10 text-primary",
                  )}
                >
                  <Icon className="size-3.5" />
                </span>
              )}
              <div className="flex flex-1 flex-col gap-0.5">
                <h2
                  id={`${id}-title`}
                  className="text-[12px] font-semibold leading-tight"
                >
                  {title}
                </h2>
                {description && (
                  <p
                    id={`${id}-description`}
                    className="text-[11px]"
                    style={{ color: 'var(--fg-muted)' }}
                  >
                    {description}
                  </p>
                )}
              </div>
            </div>

            {metadata && metadata.length > 0 && (
              <>
                <Separator />
                <dl className="flex flex-col gap-1 text-[11px]">
                  {metadata.map((item, index) => (
                    <div key={index} className="flex justify-between gap-2">
                      <dt className="shrink-0" style={{ color: 'var(--fg-muted)' }}>
                        {item.key}
                      </dt>
                      <dd className="min-w-0 truncate font-mono text-[10px]">{item.value}</dd>
                    </div>
                  ))}
                </dl>
              </>
            )}

            <div className="flex items-center justify-end gap-1.5 pt-0.5">
              {actions.map((action) => (
                <button
                  key={action.id}
                  onClick={() => handleAction(action.id)}
                  className={cn(
                    "rounded-md px-3 py-1 text-[11px] font-medium transition-colors",
                    action.variant === "destructive" || action.variant === "default"
                      ? "text-[var(--bg)] bg-[var(--fg)]"
                      : "bg-transparent border",
                  )}
                  style={action.variant === "ghost" ? {
                    color: 'var(--fg-secondary)',
                    borderColor: 'var(--border)',
                  } : undefined}
                >
                  {action.label}
                </button>
              ))}
            </div>
          </div>
        </article>
      )}
    </div>
  );
}
