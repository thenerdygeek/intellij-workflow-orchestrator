/**
 * Plan — interactive plan card with todo list, progress bar, and status indicators.
 * Adapted from assistant-ui/tool-ui — removed Zod, uses plain TypeScript interfaces.
 * Source: https://github.com/assistant-ui/tool-ui
 */

import { useState, useMemo } from "react";
import { cn } from "@/lib/utils";
import { Card, CardHeader, CardTitle, CardDescription, CardContent } from "@/components/ui/card";
import {
  Collapsible,
  CollapsibleTrigger,
} from "@/components/ui/collapsible";
import {
  Accordion,
  AccordionItem,
  AccordionTrigger,
  AccordionContent,
} from "@/components/ui/accordion";
import { TextShimmer } from "@/components/ui/prompt-kit/text-shimmer";
import { Loader2, Check, X, MoreHorizontal, ChevronRight } from "lucide-react";

// ---------- Types ----------

export type PlanTodoStatus = "pending" | "in_progress" | "completed" | "cancelled";

export interface PlanTodo {
  id: string;
  label: string;
  status: PlanTodoStatus;
  description?: string;
}

export interface PlanProps {
  id: string;
  title: string;
  description?: string;
  todos: PlanTodo[];
  maxVisibleTodos?: number;
  className?: string;
}

export interface PlanCompactProps {
  todos: PlanTodo[];
  maxVisibleTodos?: number;
  className?: string;
}

// ---------- Status Icon ----------

function StatusIcon({ status }: { status: PlanTodoStatus }) {
  switch (status) {
    case "completed":
      return (
        <div className="flex size-5 shrink-0 items-center justify-center rounded-full bg-[var(--success,hsl(var(--primary)))]">
          <Check className="size-3 text-[var(--bg,hsl(var(--primary-foreground)))]" />
        </div>
      );
    case "in_progress":
      return (
        <Loader2 className="size-5 shrink-0 animate-spin text-[var(--accent,hsl(var(--primary)))]" />
      );
    case "cancelled":
      return (
        <div className="flex size-5 shrink-0 items-center justify-center rounded-full bg-[var(--error,hsl(var(--destructive)))]">
          <X className="size-3 text-[var(--bg,hsl(var(--destructive-foreground)))]" />
        </div>
      );
    case "pending":
    default:
      return (
        <div className="size-5 shrink-0 rounded-full border-2 border-[var(--fg-muted,hsl(var(--muted-foreground)))]" />
      );
  }
}

// ---------- Todo Item ----------

function TodoItem({
  todo,
  index,
}: {
  todo: PlanTodo;
  index: number;
}) {
  const [isOpen, setIsOpen] = useState(false);
  const hasDescription = !!todo.description;

  const label =
    todo.status === "in_progress" ? (
      <TextShimmer className="text-[12px]">{todo.label}</TextShimmer>
    ) : (
      <span
        className={cn(
          "text-[12px]",
          todo.status === "completed" && "line-through opacity-60",
          todo.status === "cancelled" && "line-through opacity-40",
        )}
      >
        {todo.label}
      </span>
    );

  const content = (
    <div
      className="flex items-start gap-2 py-1.5 animate-in fade-in slide-in-from-left-2"
      style={{ animationDelay: `${index * 50}ms`, animationFillMode: "both" }}
    >
      <div className="mt-0.5">
        <StatusIcon status={todo.status} />
      </div>
      <div className="flex-1 min-w-0">
        <div className="flex items-center gap-1">
          {label}
          {hasDescription && (
            <ChevronRight
              className={cn(
                "size-3 shrink-0 text-[var(--fg-muted,hsl(var(--muted-foreground)))] transition-transform duration-200",
                isOpen && "rotate-90",
              )}
            />
          )}
        </div>
        {isOpen && todo.description && (
          <p className="mt-1 text-[11px] text-[var(--fg-secondary,hsl(var(--muted-foreground)))] leading-relaxed">
            {todo.description}
          </p>
        )}
      </div>
    </div>
  );

  if (hasDescription) {
    return (
      <Collapsible open={isOpen} onOpenChange={setIsOpen}>
        <CollapsibleTrigger asChild>
          <button className="w-full text-left cursor-pointer hover:bg-[var(--hover-overlay,rgba(255,255,255,0.04))] rounded-md px-1 -mx-1 transition-colors">
            {content}
          </button>
        </CollapsibleTrigger>
        {/* Description is rendered inline in the content above via isOpen */}
      </Collapsible>
    );
  }

  return content;
}

// ---------- Progress Bar ----------

function PlanProgressBar({
  completed,
  total,
}: {
  completed: number;
  total: number;
}) {
  const percentage = total > 0 ? Math.round((completed / total) * 100) : 0;
  const isComplete = percentage === 100;

  return (
    <div className="space-y-1.5">
      <div className="flex items-center justify-between">
        <span className="text-[11px] text-[var(--fg-secondary,hsl(var(--muted-foreground)))]">
          {completed}/{total} completed
        </span>
        <span
          className={cn(
            "text-[12px] font-semibold tabular-nums",
            isComplete
              ? "text-[var(--success,hsl(var(--primary)))]"
              : "text-[var(--accent,hsl(var(--primary)))]",
          )}
        >
          {percentage}%
          {isComplete && " \u2728"}
        </span>
      </div>
      <div
        className="h-1.5 w-full overflow-hidden rounded-full"
        style={{ backgroundColor: "var(--code-bg, hsl(var(--muted)))" }}
      >
        <div
          className={cn(
            "h-full rounded-full transition-all duration-500 ease-out",
            isComplete
              ? "bg-[var(--success,hsl(var(--primary)))]"
              : "bg-[var(--accent,hsl(var(--primary)))]",
          )}
          style={{ width: `${percentage}%` }}
        />
      </div>
    </div>
  );
}

// ---------- Collapsed Group ----------

function CollapsedGroup({
  count,
  label,
  todos,
}: {
  count: number;
  label: string;
  todos: PlanTodo[];
}) {
  if (count <= 0) return null;

  return (
    <Accordion type="single" collapsible>
      <AccordionItem value="group" className="border-b-0">
        <AccordionTrigger className="py-1.5 text-[11px] text-[var(--fg-muted,hsl(var(--muted-foreground)))] hover:text-[var(--fg-secondary,hsl(var(--foreground)))]">
          <span className="flex items-center gap-1.5">
            <MoreHorizontal className="size-3.5" />
            {label}
          </span>
        </AccordionTrigger>
        <AccordionContent>
          <div className="space-y-0.5">
            {todos.map((todo, i) => (
              <TodoItem key={todo.id} todo={todo} index={i} />
            ))}
          </div>
        </AccordionContent>
      </AccordionItem>
    </Accordion>
  );
}

// ---------- Todo List ----------

function TodoList({
  todos,
  maxVisible,
}: {
  todos: PlanTodo[];
  maxVisible: number;
}) {
  // Sliding window: show `maxVisible` items centered around the active step.
  // Above the window: "+N completed" collapsed group.
  // Below the window: "+N pending" collapsed group.
  // Falls back to showing first `maxVisible` if no active step yet.

  if (todos.length <= maxVisible) {
    // All fit — no collapsing needed
    return (
      <div className="space-y-0.5">
        {todos.map((todo, i) => (
          <TodoItem key={todo.id} todo={todo} index={i} />
        ))}
      </div>
    );
  }

  // Find the active (in_progress) step, or the first pending step
  const activeIndex = todos.findIndex(t => t.status === "in_progress");
  const anchorIndex = activeIndex >= 0
    ? activeIndex
    : todos.findIndex(t => t.status === "pending");

  // If everything is completed or no anchor found, anchor to the end
  const anchor = anchorIndex >= 0 ? anchorIndex : todos.length - 1;

  // Place the window so the anchor is in the middle slot.
  // For maxVisible=3: [anchor-1, anchor, anchor+1]
  const halfBefore = Math.floor((maxVisible - 1) / 2);
  let windowStart = anchor - halfBefore;
  let windowEnd = windowStart + maxVisible;

  // Clamp to bounds
  if (windowStart < 0) {
    windowStart = 0;
    windowEnd = maxVisible;
  }
  if (windowEnd > todos.length) {
    windowEnd = todos.length;
    windowStart = Math.max(0, windowEnd - maxVisible);
  }

  const beforeTodos = todos.slice(0, windowStart);
  const visibleTodos = todos.slice(windowStart, windowEnd);
  const afterTodos = todos.slice(windowEnd);

  const beforeCompleted = beforeTodos.filter(t => t.status === "completed").length;
  const beforeLabel = beforeCompleted === beforeTodos.length
    ? `${beforeTodos.length} completed`
    : `${beforeTodos.length} step${beforeTodos.length !== 1 ? "s" : ""}`;

  const afterPending = afterTodos.filter(t => t.status === "pending").length;
  const afterLabel = afterPending === afterTodos.length
    ? `${afterTodos.length} pending`
    : `${afterTodos.length} step${afterTodos.length !== 1 ? "s" : ""}`;

  return (
    <div>
      <CollapsedGroup
        count={beforeTodos.length}
        label={beforeLabel}
        todos={beforeTodos}
      />

      <div className="space-y-0.5">
        {visibleTodos.map((todo, i) => (
          <TodoItem key={todo.id} todo={todo} index={windowStart + i} />
        ))}
      </div>

      <CollapsedGroup
        count={afterTodos.length}
        label={afterLabel}
        todos={afterTodos}
      />
    </div>
  );
}

// ---------- Plan (Full Card) ----------

export function Plan({
  title,
  description,
  todos,
  maxVisibleTodos = 4,
  className,
}: PlanProps) {
  const { completed, total } = useMemo(() => {
    const completed = todos.filter((t) => t.status === "completed").length;
    return { completed, total: todos.length };
  }, [todos]);

  return (
    <Card
      className={cn(
        "gap-0 overflow-hidden py-0",
        "border-[var(--border)] bg-[var(--tool-bg,hsl(var(--card)))]",
        className,
      )}
    >
      <CardHeader className="gap-1 px-4 py-3" style={{ borderBottom: "1px solid var(--border)" }}>
        <CardTitle className="text-[13px]" style={{ color: "var(--fg)" }}>
          {title}
        </CardTitle>
        {description && (
          <CardDescription className="text-[11px]" style={{ color: "var(--fg-secondary)" }}>
            {description}
          </CardDescription>
        )}
      </CardHeader>
      <CardContent className="px-4 py-3 space-y-3">
        <PlanProgressBar completed={completed} total={total} />
        <TodoList todos={todos} maxVisible={maxVisibleTodos} />
      </CardContent>
    </Card>
  );
}

// ---------- PlanCompact (no card wrapper) ----------

export function PlanCompact({
  todos,
  maxVisibleTodos = 4,
  className,
}: PlanCompactProps) {
  const { completed, total } = useMemo(() => {
    const completed = todos.filter((t) => t.status === "completed").length;
    return { completed, total: todos.length };
  }, [todos]);

  return (
    <div className={cn("space-y-3", className)}>
      <PlanProgressBar completed={completed} total={total} />
      <TodoList todos={todos} maxVisible={maxVisibleTodos} />
    </div>
  );
}
