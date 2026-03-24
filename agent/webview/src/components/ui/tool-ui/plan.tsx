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

// ---------- Todo List ----------

function TodoList({
  todos,
  maxVisible,
}: {
  todos: PlanTodo[];
  maxVisible: number;
}) {
  const visibleTodos = todos.slice(0, maxVisible);
  const hiddenTodos = todos.slice(maxVisible);
  const hasHidden = hiddenTodos.length > 0;

  return (
    <div>
      <div className="space-y-0.5">
        {visibleTodos.map((todo, i) => (
          <TodoItem key={todo.id} todo={todo} index={i} />
        ))}
      </div>

      {hasHidden && (
        <Accordion type="single" collapsible>
          <AccordionItem value="more" className="border-b-0">
            <AccordionTrigger className="py-2 text-[11px] text-[var(--fg-muted,hsl(var(--muted-foreground)))] hover:text-[var(--fg-secondary,hsl(var(--foreground)))]">
              <span className="flex items-center gap-1.5">
                <MoreHorizontal className="size-3.5" />
                {hiddenTodos.length} more step{hiddenTodos.length !== 1 ? "s" : ""}
              </span>
            </AccordionTrigger>
            <AccordionContent>
              <div className="space-y-0.5">
                {hiddenTodos.map((todo, i) => (
                  <TodoItem
                    key={todo.id}
                    todo={todo}
                    index={maxVisible + i}
                  />
                ))}
              </div>
            </AccordionContent>
          </AccordionItem>
        </Accordion>
      )}
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
