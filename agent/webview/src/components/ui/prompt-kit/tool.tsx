import { Button } from "@/components/ui/button"
import {
  Collapsible,
  CollapsibleContent,
  CollapsibleTrigger,
} from "@/components/ui/collapsible"
import { cn } from "@/lib/utils"
import {
  CheckCircle,
  ChevronDown,
  Loader2,
  Settings,
  XCircle,
} from "lucide-react"
import { useState, type ReactNode } from "react"

export type ToolPart = {
  type: string
  state:
    | "input-streaming"
    | "input-available"
    | "output-available"
    | "output-error"
  input?: Record<string, unknown>
  output?: Record<string, unknown>
  toolCallId?: string
  errorText?: string
}

export type ToolProps = {
  toolPart: ToolPart
  defaultOpen?: boolean
  open?: boolean
  onOpenChange?: (open: boolean) => void
  className?: string
  headerExtra?: ReactNode
  footerExtra?: ReactNode
}

const Tool = ({
  toolPart,
  defaultOpen = false,
  open: controlledOpen,
  onOpenChange,
  className,
  headerExtra,
  footerExtra,
}: ToolProps) => {
  const [internalOpen, setInternalOpen] = useState(defaultOpen)
  const isOpen = controlledOpen ?? internalOpen
  const handleOpenChange = (v: boolean) => {
    setInternalOpen(v)
    onOpenChange?.(v)
  }

  const { state, input, output, toolCallId } = toolPart

  const getStateIcon = () => {
    switch (state) {
      case "input-streaming":
        return <Loader2 className="h-4 w-4 animate-spin text-[var(--accent)]" />
      case "input-available":
        return <Settings className="h-4 w-4 text-[var(--accent-edit,#f97316)]" />
      case "output-available":
        return <CheckCircle className="h-4 w-4 text-[var(--success)]" />
      case "output-error":
        return <XCircle className="h-4 w-4 text-[var(--error)]" />
      default:
        return <Settings className="text-[var(--fg-muted)] h-4 w-4" />
    }
  }

  const getStateBadge = () => {
    const baseClasses = "px-2 py-1 rounded-full text-xs font-medium"
    switch (state) {
      case "input-streaming":
        return (
          <span
            className={cn(
              baseClasses,
              "bg-[var(--badge-read-bg,#1e3a5f)] text-[var(--badge-read-fg,#7cb3f0)]"
            )}
          >
            Processing
          </span>
        )
      case "input-available":
        return (
          <span
            className={cn(
              baseClasses,
              "bg-[var(--badge-edit-bg,#2d1f3d)] text-[var(--badge-edit-fg,#c084fc)]"
            )}
          >
            Ready
          </span>
        )
      case "output-available":
        return (
          <span
            className={cn(
              baseClasses,
              "bg-[var(--badge-cmd-bg,#1a2e1a)] text-[var(--badge-cmd-fg,#6ee77a)]"
            )}
          >
            Completed
          </span>
        )
      case "output-error":
        return (
          <span
            className={cn(
              baseClasses,
              "bg-[var(--diff-rem-bg)] text-[var(--error)]"
            )}
          >
            Error
          </span>
        )
      default:
        return (
          <span
            className={cn(
              baseClasses,
              "bg-[var(--chip-bg,#2a2a2a)] text-[var(--fg-muted)]"
            )}
          >
            Pending
          </span>
        )
    }
  }

  const formatValue = (value: unknown): string => {
    if (value === null) return "null"
    if (value === undefined) return "undefined"
    if (typeof value === "string") return value
    if (typeof value === "object") {
      return JSON.stringify(value, null, 2)
    }
    return String(value)
  }

  return (
    <div
      className={cn(
        "border-[var(--border)] mt-3 overflow-hidden rounded-lg border",
        className
      )}
    >
      <Collapsible open={isOpen} onOpenChange={handleOpenChange}>
        <CollapsibleTrigger asChild>
          <Button
            variant="ghost"
            className="h-auto w-full justify-between rounded-b-none px-3 py-2 font-normal"
            style={{ backgroundColor: "var(--tool-bg)" }}
          >
            <div className="flex items-center gap-2">
              {getStateIcon()}
              <span className="font-mono text-sm font-medium">
                {toolPart.type}
              </span>
              {getStateBadge()}
              {headerExtra}
            </div>
            <ChevronDown className={cn("h-4 w-4", isOpen && "rotate-180")} />
          </Button>
        </CollapsibleTrigger>
        <CollapsibleContent
          className={cn(
            "border-[var(--border)] border-t",
            "data-[state=closed]:animate-collapsible-up data-[state=open]:animate-collapsible-down overflow-hidden"
          )}
        >
          <div className="space-y-3 p-3" style={{ backgroundColor: "var(--tool-bg)" }}>
            {input && Object.keys(input).length > 0 && (
              <div>
                <h4 className="text-[var(--fg-muted)] mb-2 text-sm font-medium">
                  Input
                </h4>
                <div className="rounded border border-[var(--border)] p-2 font-mono text-sm bg-[var(--code-bg)]">
                  {Object.entries(input).map(([key, value]) => (
                    <div key={key} className="mb-1">
                      <span className="text-[var(--fg-muted)]">{key}:</span>{" "}
                      <span>{formatValue(value)}</span>
                    </div>
                  ))}
                </div>
              </div>
            )}

            {output && (
              <div>
                <h4 className="text-[var(--fg-muted)] mb-2 text-sm font-medium">
                  Output
                </h4>
                <div className="max-h-60 overflow-auto rounded border border-[var(--border)] p-2 font-mono text-sm bg-[var(--code-bg)]">
                  <pre className="whitespace-pre-wrap">
                    {formatValue(output)}
                  </pre>
                </div>
              </div>
            )}

            {state === "output-error" && toolPart.errorText && (
              <div>
                <h4 className="mb-2 text-sm font-medium text-[var(--error)]">Error</h4>
                <div className="rounded border border-[var(--error)] p-2 text-sm bg-[var(--diff-rem-bg)]">
                  {toolPart.errorText}
                </div>
              </div>
            )}

            {state === "input-streaming" && (
              <div className="text-[var(--fg-muted)] text-sm">
                Processing tool call...
              </div>
            )}

            {toolCallId && (
              <div className="text-[var(--fg-muted)] border-t border-[var(--border)] pt-2 text-xs">
                <span className="font-mono">Call ID: {toolCallId}</span>
              </div>
            )}
          </div>
        </CollapsibleContent>
      </Collapsible>
      {footerExtra}
    </div>
  )
}

export { Tool }
