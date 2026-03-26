/**
 * ChainOfThought — collapsible step-by-step reasoning display.
 * Adapted from assistant-ui/prompt-kit — removed "use client".
 * Source: https://github.com/assistant-ui/prompt-kit
 */

import * as React from "react";
import { useState } from "react";
import { cn } from "@/lib/utils";
import {
  Collapsible,
  CollapsibleTrigger,
  CollapsibleContent,
} from "@/components/ui/collapsible";
import { ChevronDown, Circle } from "lucide-react";

// ---------- ChainOfThought (wrapper) ----------

export interface ChainOfThoughtProps {
  children: React.ReactNode;
  className?: string;
}

export function ChainOfThought({ children, className }: ChainOfThoughtProps) {
  return (
    <div
      className={cn("space-y-1", className)}
      role="list"
      aria-label="Chain of thought"
    >
      {children}
    </div>
  );
}

// ---------- ChainOfThoughtStep ----------

export interface ChainOfThoughtStepProps {
  children: React.ReactNode;
  className?: string;
  defaultOpen?: boolean;
  /** When true, force the step open (controlled mode). */
  forceOpen?: boolean;
}

export function ChainOfThoughtStep({
  children,
  className,
  defaultOpen = false,
  forceOpen,
}: ChainOfThoughtStepProps) {
  const [isOpen, setIsOpen] = useState(defaultOpen || !!forceOpen);

  // Force open when forceOpen transitions to true
  React.useEffect(() => {
    if (forceOpen) setIsOpen(true);
  }, [forceOpen]);

  return (
    <Collapsible open={isOpen} onOpenChange={setIsOpen}>
      <div
        className={cn("rounded-md", className)}
        role="listitem"
      >
        {children}
      </div>
    </Collapsible>
  );
}

// ---------- ChainOfThoughtTrigger ----------

export interface ChainOfThoughtTriggerProps {
  children: React.ReactNode;
  className?: string;
  icon?: React.ReactNode;
  isActive?: boolean;
}

export function ChainOfThoughtTrigger({
  children,
  className,
  icon,
  isActive = false,
}: ChainOfThoughtTriggerProps) {
  return (
    <CollapsibleTrigger asChild>
      <button
        className={cn(
          "flex w-full items-center gap-2 rounded-md px-2 py-1.5 text-left text-[12px] transition-colors",
          "hover:bg-[var(--hover-overlay,rgba(255,255,255,0.04))]",
          isActive && "text-[var(--accent,hsl(var(--primary)))]",
          !isActive && "text-[var(--fg-secondary,hsl(var(--muted-foreground)))]",
          className,
        )}
      >
        <span className="shrink-0">
          {icon ?? (
            <Circle
              className={cn(
                "size-2",
                isActive
                  ? "fill-[var(--accent,hsl(var(--primary)))] text-[var(--accent,hsl(var(--primary)))]"
                  : "fill-[var(--fg-muted,hsl(var(--muted-foreground)))] text-[var(--fg-muted,hsl(var(--muted-foreground)))]",
              )}
            />
          )}
        </span>
        <span className="flex-1 truncate">{children}</span>
        <ChevronDown className="size-3.5 shrink-0 text-[var(--fg-muted,hsl(var(--muted-foreground)))] transition-transform duration-200 [[data-state=open]_&]:rotate-180" />
      </button>
    </CollapsibleTrigger>
  );
}

// ---------- ChainOfThoughtContent ----------

export interface ChainOfThoughtContentProps {
  children: React.ReactNode;
  className?: string;
}

export function ChainOfThoughtContent({
  children,
  className,
}: ChainOfThoughtContentProps) {
  return (
    <CollapsibleContent>
      <div
        className={cn(
          "ml-4 border-l-2 border-[var(--border,hsl(var(--border)))] pl-3 pb-2",
          className,
        )}
      >
        {children}
      </div>
    </CollapsibleContent>
  );
}

// ---------- ChainOfThoughtItem ----------

export interface ChainOfThoughtItemProps {
  children: React.ReactNode;
  className?: string;
}

export function ChainOfThoughtItem({
  children,
  className,
}: ChainOfThoughtItemProps) {
  return (
    <div
      className={cn(
        "py-1 text-[11px] text-[var(--fg-secondary,hsl(var(--muted-foreground)))] leading-relaxed",
        className,
      )}
    >
      {children}
    </div>
  );
}
