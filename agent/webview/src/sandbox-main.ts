/**
 * Entry point for artifact-sandbox.html.
 *
 * This file is imported by the sandbox HTML via <script type="module" src="...">.
 * Vite resolves the bare module imports (react, react-dom/client, react-runner)
 * at build time, bundling them into the sandbox's output.
 */

import React, {
  useState, useEffect, useCallback, useMemo, useRef, useReducer,
  useLayoutEffect, useId, useTransition, Fragment,
} from 'react'
import { createRoot, type Root } from 'react-dom/client'
import { Runner } from 'react-runner'

// ── Visualization Libraries (available to LLM-generated artifacts) ──

import * as LucideIcons from 'lucide-react'

import {
  BarChart, Bar, LineChart, Line, AreaChart, Area, PieChart, Pie, Cell,
  XAxis, YAxis, CartesianGrid, Tooltip as RechartsTooltip, Legend,
  ResponsiveContainer, RadialBarChart, RadialBar, ComposedChart,
  Scatter, ScatterChart, Treemap, FunnelChart, Funnel, RadarChart,
  Radar, PolarGrid, PolarAngleAxis, PolarRadiusAxis, LabelList,
} from 'recharts'

import * as d3 from 'd3'

import createGlobe from 'cobe'

import {
  motion, AnimatePresence, useMotionValue, useTransform, useSpring,
  useInView, useScroll, useAnimation,
} from 'motion/react'

import rough from 'roughjs'

import {
  ComposableMap, Geographies, Geography, Marker, Line as MapLine,
  ZoomableGroup, Graticule, Sphere,
} from 'react-simple-maps'

// ── Radix Primitives (wrapped with shadcn-compatible APIs below) ──

import * as RadixAvatar from '@radix-ui/react-avatar'
import * as RadixPopover from '@radix-ui/react-popover'
import * as RadixHoverCard from '@radix-ui/react-hover-card'
import * as RadixScrollArea from '@radix-ui/react-scroll-area'
import * as RadixCheckbox from '@radix-ui/react-checkbox'
import * as RadixSlider from '@radix-ui/react-slider'
import * as RadixToggleGroup from '@radix-ui/react-toggle'
import * as RadixTabs from '@radix-ui/react-tabs'
import * as RadixDialog from '@radix-ui/react-dialog'
import * as RadixDropdownMenu from '@radix-ui/react-dropdown-menu'
import * as RadixSelect from '@radix-ui/react-select'
import * as RadixSwitch from '@radix-ui/react-switch'
import * as RadixTooltip from '@radix-ui/react-tooltip'
import * as RadixAccordion from '@radix-ui/react-accordion'
import * as RadixProgress from '@radix-ui/react-progress'
import * as RadixSeparator from '@radix-ui/react-separator'

// ── Utility Libraries (data viz + formatting) ──

import * as dateFns from 'date-fns'
import * as XYFlow from '@xyflow/react'
import * as ReactTable from '@tanstack/react-table'
import { colord, extend as colordExtend } from 'colord'

// ── UI Primitive Components ──
//
// Shadcn-compatible primitives backed by Radix UI where applicable. All styling
// uses the sandbox CSS variables declared in `artifact-sandbox.html`
// (--bg, --fg, --fg-muted, --fg-secondary, --border, --code-bg, --hover-overlay,
// --accent, --success, --warning, --error). The goal is API compatibility with
// the version of shadcn/ui the LLM was trained on — identifier names, prop
// names, and component structure should match so generated code works
// out-of-the-box without needing retries through the self-repair loop.
//
// Pure styled-div primitives (Card, Badge, Alert, Skeleton, Breadcrumb, Input,
// Label, Textarea) are hand-rolled. Complex interactive primitives (Tabs,
// Accordion, Dialog, Popover, HoverCard, Select, Switch, Checkbox, Slider,
// Toggle, DropdownMenu, ScrollArea, Tooltip, Avatar, Progress, Separator) use
// Radix under the hood and expose a shadcn-shaped surface.

const h = React.createElement

/** Compact className joiner — drops falsy values and collapses whitespace. */
function cn(...parts: Array<string | false | null | undefined>): string {
  return parts.filter(Boolean).join(' ').replace(/\s+/g, ' ').trim()
}

// ── Primitives: Card ──

const Card = ({ className, children, onClick }: { className?: string; children?: React.ReactNode; onClick?: () => void }) =>
  h('div', {
    className: cn('rounded-lg border border-[var(--border)] bg-[var(--code-bg)] shadow-sm', onClick && 'cursor-pointer', className),
    onClick,
  }, children)

const CardHeader = ({ className, children }: { className?: string; children?: React.ReactNode }) =>
  h('div', { className: cn('flex flex-col space-y-1.5 p-4 pb-2', className) }, children)

const CardTitle = ({ className, children }: { className?: string; children?: React.ReactNode }) =>
  h('h3', { className: cn('text-sm font-semibold leading-none tracking-tight', className) }, children)

const CardDescription = ({ className, children }: { className?: string; children?: React.ReactNode }) =>
  h('p', { className: cn('text-xs text-[var(--fg-muted)]', className) }, children)

const CardContent = ({ className, children }: { className?: string; children?: React.ReactNode }) =>
  h('div', { className: cn('p-4 pt-0', className) }, children)

const CardFooter = ({ className, children }: { className?: string; children?: React.ReactNode }) =>
  h('div', { className: cn('flex items-center p-4 pt-0', className) }, children)

// ── Primitives: Badge ──

const badgeVariants: Record<string, string> = {
  default: 'bg-[var(--accent)]/10 text-[var(--accent)] border-[var(--accent)]/20',
  primary: 'bg-[var(--accent)] text-white border-transparent',
  success: 'bg-[var(--success)]/10 text-[var(--success)] border-[var(--success)]/20',
  warning: 'bg-[var(--warning)]/10 text-[var(--warning)] border-[var(--warning)]/20',
  error: 'bg-[var(--error)]/10 text-[var(--error)] border-[var(--error)]/20',
  destructive: 'bg-[var(--error)]/10 text-[var(--error)] border-[var(--error)]/20',
  outline: 'bg-transparent text-[var(--fg)] border-[var(--border)]',
  secondary: 'bg-[var(--hover-overlay)] text-[var(--fg)] border-transparent',
}

const Badge = ({ variant = 'default', className, children, ...props }: { variant?: string; className?: string; children?: React.ReactNode } & React.HTMLAttributes<HTMLSpanElement>) =>
  h('span', {
    className: cn('inline-flex items-center rounded-full border px-2.5 py-0.5 text-xs font-medium transition-colors',
      badgeVariants[variant] || badgeVariants.default, className),
    ...props,
  }, children)

// ── Primitives: Button ──

const buttonVariants: Record<string, string> = {
  default: 'bg-[var(--accent)] text-white hover:bg-[var(--accent)]/90',
  primary: 'bg-[var(--accent)] text-white hover:bg-[var(--accent)]/90',
  destructive: 'bg-[var(--error)] text-white hover:bg-[var(--error)]/90',
  outline: 'border border-[var(--border)] bg-transparent hover:bg-[var(--hover-overlay)]',
  secondary: 'bg-[var(--hover-overlay)] text-[var(--fg)] hover:bg-[var(--hover-overlay)]/80',
  ghost: 'hover:bg-[var(--hover-overlay)]',
  link: 'text-[var(--accent)] underline-offset-4 hover:underline',
}

const buttonSizes: Record<string, string> = {
  default: 'h-9 px-4 py-2 text-sm',
  sm: 'h-8 rounded-md px-3 text-xs',
  lg: 'h-10 rounded-md px-8 text-sm',
  icon: 'h-9 w-9',
}

interface ButtonProps extends React.ButtonHTMLAttributes<HTMLButtonElement> {
  variant?: string
  size?: string
}

const Button = React.forwardRef<HTMLButtonElement, ButtonProps>(
  ({ variant = 'default', size = 'default', className, children, ...props }, ref) =>
    h('button', {
      ref,
      className: cn(
        'inline-flex items-center justify-center gap-2 rounded-md font-medium transition-colors focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-[var(--accent)] disabled:pointer-events-none disabled:opacity-50',
        buttonVariants[variant] || buttonVariants.default,
        buttonSizes[size] || buttonSizes.default,
        className,
      ),
      ...props,
    }, children)
)
Button.displayName = 'Button'

// ── Primitives: Alert ──

const alertVariants: Record<string, string> = {
  default: 'bg-[var(--code-bg)] text-[var(--fg)] border-[var(--border)]',
  destructive: 'bg-[var(--error)]/10 text-[var(--error)] border-[var(--error)]/30',
  success: 'bg-[var(--success)]/10 text-[var(--success)] border-[var(--success)]/30',
  warning: 'bg-[var(--warning)]/10 text-[var(--warning)] border-[var(--warning)]/30',
  info: 'bg-[var(--accent)]/10 text-[var(--accent)] border-[var(--accent)]/30',
}

const Alert = ({ variant = 'default', className, children, ...props }: { variant?: string; className?: string; children?: React.ReactNode } & React.HTMLAttributes<HTMLDivElement>) =>
  h('div', {
    role: 'alert',
    className: cn('relative w-full rounded-lg border p-4', alertVariants[variant] || alertVariants.default, className),
    ...props,
  }, children)

const AlertTitle = ({ className, children, ...props }: { className?: string; children?: React.ReactNode } & React.HTMLAttributes<HTMLHeadingElement>) =>
  h('h5', { className: cn('mb-1 font-medium leading-none tracking-tight', className), ...props }, children)

const AlertDescription = ({ className, children, ...props }: { className?: string; children?: React.ReactNode } & React.HTMLAttributes<HTMLDivElement>) =>
  h('div', { className: cn('text-sm opacity-90', className), ...props }, children)

// ── Primitives: Skeleton ──

const Skeleton = ({ className, ...props }: { className?: string } & React.HTMLAttributes<HTMLDivElement>) =>
  h('div', { className: cn('animate-pulse rounded-md bg-[var(--hover-overlay)]', className), ...props })

// ── Primitives: Avatar (Radix) ──

const Avatar = ({ className, children, ...props }: React.ComponentProps<typeof RadixAvatar.Root>) =>
  h(RadixAvatar.Root, {
    className: cn('relative flex h-10 w-10 shrink-0 overflow-hidden rounded-full', className),
    ...props,
  }, children)

const AvatarImage = ({ className, ...props }: React.ComponentProps<typeof RadixAvatar.Image>) =>
  h(RadixAvatar.Image, { className: cn('aspect-square h-full w-full', className), ...props })

const AvatarFallback = ({ className, children, ...props }: React.ComponentProps<typeof RadixAvatar.Fallback>) =>
  h(RadixAvatar.Fallback, {
    className: cn('flex h-full w-full items-center justify-center rounded-full bg-[var(--hover-overlay)]', className),
    ...props,
  }, children)

// ── Primitives: ScrollArea (Radix) ──

const ScrollArea = ({ className, children, ...props }: React.ComponentProps<typeof RadixScrollArea.Root>) =>
  h(RadixScrollArea.Root, {
    className: cn('relative overflow-hidden', className),
    ...props,
  },
    h(RadixScrollArea.Viewport, { className: 'h-full w-full rounded-[inherit]' }, children),
    h(RadixScrollArea.Scrollbar, {
      orientation: 'vertical',
      className: 'flex touch-none select-none transition-colors h-full w-2.5 border-l border-l-transparent p-[1px]',
    },
      h(RadixScrollArea.Thumb, { className: 'relative flex-1 rounded-full bg-[var(--border)]' })
    ),
    h(RadixScrollArea.Corner, null)
  )

const ScrollBar = ({ className, orientation = 'vertical', ...props }: React.ComponentProps<typeof RadixScrollArea.Scrollbar>) =>
  h(RadixScrollArea.Scrollbar, {
    orientation,
    className: cn('flex touch-none select-none transition-colors',
      orientation === 'vertical' ? 'h-full w-2.5 border-l border-l-transparent p-[1px]' : 'h-2.5 flex-col border-t border-t-transparent p-[1px]',
      className),
    ...props,
  }, h(RadixScrollArea.Thumb, { className: 'relative flex-1 rounded-full bg-[var(--border)]' }))

// ── Primitives: Tabs (Radix) ──

const Tabs = (props: React.ComponentProps<typeof RadixTabs.Root>) => h(RadixTabs.Root, props)

const TabsList = ({ className, ...props }: React.ComponentProps<typeof RadixTabs.List>) =>
  h(RadixTabs.List, {
    className: cn('inline-flex h-9 items-center justify-center rounded-lg bg-[var(--code-bg)] p-1', className),
    ...props,
  })

const TabsTrigger = ({ className, ...props }: React.ComponentProps<typeof RadixTabs.Trigger>) =>
  h(RadixTabs.Trigger, {
    className: cn('inline-flex items-center justify-center whitespace-nowrap rounded-md px-3 py-1 text-xs font-medium transition-all focus-visible:outline-none disabled:pointer-events-none disabled:opacity-50',
      'data-[state=active]:bg-[var(--bg)] data-[state=active]:text-[var(--fg)] data-[state=active]:shadow-sm',
      'data-[state=inactive]:text-[var(--fg-muted)] hover:text-[var(--fg)]', className),
    ...props,
  })

const TabsContent = ({ className, ...props }: React.ComponentProps<typeof RadixTabs.Content>) =>
  h(RadixTabs.Content, { className: cn('mt-3 focus-visible:outline-none', className), ...props })

// ── Primitives: Accordion (Radix) ──

const Accordion = (props: React.ComponentProps<typeof RadixAccordion.Root>) => h(RadixAccordion.Root, props)

const AccordionItem = ({ className, ...props }: React.ComponentProps<typeof RadixAccordion.Item>) =>
  h(RadixAccordion.Item, { className: cn('border-b border-[var(--border)] last:border-b-0', className), ...props })

const AccordionTrigger = ({ className, children, ...props }: React.ComponentProps<typeof RadixAccordion.Trigger>) =>
  h(RadixAccordion.Header, { className: 'flex' },
    h(RadixAccordion.Trigger, {
      className: cn('flex flex-1 items-center justify-between py-4 text-sm font-medium transition-all hover:underline [&[data-state=open]>svg]:rotate-180', className),
      ...props,
    },
      children,
      h('svg', {
        xmlns: 'http://www.w3.org/2000/svg', viewBox: '0 0 24 24', fill: 'none',
        stroke: 'currentColor', strokeWidth: 2, strokeLinecap: 'round', strokeLinejoin: 'round',
        className: 'h-4 w-4 shrink-0 transition-transform duration-200',
      }, h('polyline', { points: '6 9 12 15 18 9' }))
    )
  )

const AccordionContent = ({ className, children, ...props }: React.ComponentProps<typeof RadixAccordion.Content>) =>
  h(RadixAccordion.Content, {
    className: 'overflow-hidden text-sm',
    ...props,
  },
    h('div', { className: cn('pb-4 pt-0', className) }, children)
  )

// ── Primitives: Progress (Radix) ──

const progressVariants: Record<string, string> = {
  default: 'bg-[var(--accent)]',
  success: 'bg-[var(--success)]',
  warning: 'bg-[var(--warning)]',
  error: 'bg-[var(--error)]',
}

const Progress = ({ value, variant = 'default', className, ...props }: React.ComponentProps<typeof RadixProgress.Root> & { variant?: string }) =>
  h(RadixProgress.Root, {
    className: cn('relative h-2 w-full overflow-hidden rounded-full bg-[var(--code-bg)]', className),
    value: value as number,
    ...props,
  },
    h(RadixProgress.Indicator, {
      className: cn('h-full w-full flex-1 transition-all', progressVariants[variant] || progressVariants.default),
      style: { transform: `translateX(-${100 - Math.max(0, Math.min(100, (value as number) || 0))}%)` },
    })
  )

// ── Primitives: Separator (Radix) ──

const Separator = ({ className, orientation = 'horizontal', decorative = true, ...props }: React.ComponentProps<typeof RadixSeparator.Root>) =>
  h(RadixSeparator.Root, {
    decorative,
    orientation,
    className: cn('shrink-0 bg-[var(--border)]',
      orientation === 'horizontal' ? 'h-[1px] w-full' : 'h-full w-[1px]', className),
    ...props,
  })

// ── Primitives: Tooltip (Radix) ──

const TooltipProvider = RadixTooltip.Provider

const Tooltip = ({ children, content, delayDuration = 200 }: { children: React.ReactNode; content: React.ReactNode; delayDuration?: number }) => {
  // Children must be passed via the props object for RadixTooltip.Provider
  // (its TypeScript definition requires `children` as a named prop rather than
  // accepting React.createElement's variadic children). Same pattern for Root.
  const content_el = h(RadixTooltip.Content, {
    sideOffset: 4,
    className: 'z-50 overflow-hidden rounded-md bg-[var(--fg)] px-3 py-1.5 text-xs text-[var(--bg)]',
    children: [content, h(RadixTooltip.Arrow, { className: 'fill-[var(--fg)]', key: 'arrow' })],
  })
  const root = h(RadixTooltip.Root, {
    children: [
      h(RadixTooltip.Trigger, { asChild: true, children, key: 'trigger' }),
      h(RadixTooltip.Portal, { children: content_el, key: 'portal' }),
    ],
  })
  return h(RadixTooltip.Provider, { delayDuration, children: root })
}

// ── Primitives: Popover (Radix) ──

const Popover = RadixPopover.Root
const PopoverTrigger = RadixPopover.Trigger
const PopoverAnchor = RadixPopover.Anchor

const PopoverContent = ({ className, align = 'center', sideOffset = 4, children, ...props }: React.ComponentProps<typeof RadixPopover.Content>) =>
  h(RadixPopover.Portal, null,
    h(RadixPopover.Content, {
      align, sideOffset,
      className: cn('z-50 w-72 rounded-md border border-[var(--border)] bg-[var(--bg)] p-4 text-[var(--fg)] shadow-md outline-none', className),
      ...props,
    }, children)
  )

// ── Primitives: HoverCard (Radix) ──

const HoverCard = RadixHoverCard.Root
const HoverCardTrigger = RadixHoverCard.Trigger

const HoverCardContent = ({ className, align = 'center', sideOffset = 4, children, ...props }: React.ComponentProps<typeof RadixHoverCard.Content>) =>
  h(RadixHoverCard.Portal, null,
    h(RadixHoverCard.Content, {
      align, sideOffset,
      className: cn('z-50 w-64 rounded-md border border-[var(--border)] bg-[var(--bg)] p-4 text-[var(--fg)] shadow-md outline-none', className),
      ...props,
    }, children)
  )

// ── Primitives: Dialog (Radix) ──

const Dialog = RadixDialog.Root
const DialogTrigger = RadixDialog.Trigger
const DialogClose = RadixDialog.Close
const DialogPortal = RadixDialog.Portal
const DialogOverlay = ({ className, ...props }: React.ComponentProps<typeof RadixDialog.Overlay>) =>
  h(RadixDialog.Overlay, {
    className: cn('fixed inset-0 z-50 bg-black/60 backdrop-blur-sm', className),
    ...props,
  })

const DialogContent = ({ className, children, ...props }: React.ComponentProps<typeof RadixDialog.Content>) =>
  h(RadixDialog.Portal, null,
    h(DialogOverlay, null),
    h(RadixDialog.Content, {
      className: cn('fixed left-1/2 top-1/2 z-50 w-full max-w-lg -translate-x-1/2 -translate-y-1/2 rounded-lg border border-[var(--border)] bg-[var(--bg)] p-6 text-[var(--fg)] shadow-lg outline-none', className),
      ...props,
    }, children,
      h(RadixDialog.Close, {
        className: 'absolute right-4 top-4 rounded-sm opacity-70 hover:opacity-100 focus:outline-none',
      },
        h('svg', {
          xmlns: 'http://www.w3.org/2000/svg', viewBox: '0 0 24 24', fill: 'none',
          stroke: 'currentColor', strokeWidth: 2, strokeLinecap: 'round', strokeLinejoin: 'round',
          className: 'h-4 w-4',
        }, h('line', { x1: 18, y1: 6, x2: 6, y2: 18 }), h('line', { x1: 6, y1: 6, x2: 18, y2: 18 }))
      )
    )
  )

const DialogHeader = ({ className, children, ...props }: { className?: string; children?: React.ReactNode } & React.HTMLAttributes<HTMLDivElement>) =>
  h('div', { className: cn('flex flex-col space-y-1.5 text-center sm:text-left', className), ...props }, children)

const DialogFooter = ({ className, children, ...props }: { className?: string; children?: React.ReactNode } & React.HTMLAttributes<HTMLDivElement>) =>
  h('div', { className: cn('flex flex-col-reverse sm:flex-row sm:justify-end sm:space-x-2', className), ...props }, children)

const DialogTitle = ({ className, ...props }: React.ComponentProps<typeof RadixDialog.Title>) =>
  h(RadixDialog.Title, { className: cn('text-lg font-semibold leading-none tracking-tight', className), ...props })

const DialogDescription = ({ className, ...props }: React.ComponentProps<typeof RadixDialog.Description>) =>
  h(RadixDialog.Description, { className: cn('text-sm text-[var(--fg-muted)]', className), ...props })

// ── Primitives: Sheet (Radix Dialog, side-anchored) ──

const Sheet = RadixDialog.Root
const SheetTrigger = RadixDialog.Trigger
const SheetClose = RadixDialog.Close

const sheetVariants: Record<string, string> = {
  top: 'inset-x-0 top-0 border-b',
  bottom: 'inset-x-0 bottom-0 border-t',
  left: 'inset-y-0 left-0 h-full w-3/4 max-w-sm border-r',
  right: 'inset-y-0 right-0 h-full w-3/4 max-w-sm border-l',
}

const SheetContent = ({ side = 'right', className, children, ...props }: React.ComponentProps<typeof RadixDialog.Content> & { side?: string }) =>
  h(RadixDialog.Portal, null,
    h(DialogOverlay, null),
    h(RadixDialog.Content, {
      className: cn('fixed z-50 gap-4 border border-[var(--border)] bg-[var(--bg)] p-6 text-[var(--fg)] shadow-lg',
        sheetVariants[side] || sheetVariants.right, className),
      ...props,
    }, children)
  )

const SheetHeader = DialogHeader
const SheetFooter = DialogFooter
const SheetTitle = DialogTitle
const SheetDescription = DialogDescription

// ── Primitives: Select (Radix) ──

const Select = RadixSelect.Root
const SelectValue = RadixSelect.Value
const SelectGroup = RadixSelect.Group

const SelectTrigger = ({ className, children, ...props }: React.ComponentProps<typeof RadixSelect.Trigger>) =>
  h(RadixSelect.Trigger, {
    className: cn('flex h-9 w-full items-center justify-between whitespace-nowrap rounded-md border border-[var(--border)] bg-transparent px-3 py-2 text-sm shadow-sm focus:outline-none focus:ring-1 focus:ring-[var(--accent)] disabled:cursor-not-allowed disabled:opacity-50 [&>span]:line-clamp-1', className),
    ...props,
  }, children,
    h(RadixSelect.Icon, { asChild: true },
      h('svg', {
        xmlns: 'http://www.w3.org/2000/svg', viewBox: '0 0 24 24', fill: 'none',
        stroke: 'currentColor', strokeWidth: 2, strokeLinecap: 'round', strokeLinejoin: 'round',
        className: 'h-4 w-4 opacity-50',
      }, h('polyline', { points: '6 9 12 15 18 9' }))
    )
  )

const SelectContent = ({ className, children, position = 'popper', ...props }: React.ComponentProps<typeof RadixSelect.Content>) =>
  h(RadixSelect.Portal, null,
    h(RadixSelect.Content, {
      position,
      className: cn('relative z-50 max-h-96 min-w-[8rem] overflow-hidden rounded-md border border-[var(--border)] bg-[var(--bg)] text-[var(--fg)] shadow-md',
        position === 'popper' && 'data-[side=bottom]:translate-y-1 data-[side=left]:-translate-x-1 data-[side=right]:translate-x-1 data-[side=top]:-translate-y-1',
        className),
      ...props,
    },
      h(RadixSelect.Viewport, { className: 'p-1' }, children)
    )
  )

const SelectItem = ({ className, children, ...props }: React.ComponentProps<typeof RadixSelect.Item>) =>
  h(RadixSelect.Item, {
    className: cn('relative flex w-full cursor-default select-none items-center rounded-sm py-1.5 pl-8 pr-2 text-sm outline-none focus:bg-[var(--hover-overlay)] data-[disabled]:pointer-events-none data-[disabled]:opacity-50', className),
    ...props,
  },
    h('span', { className: 'absolute left-2 flex h-3.5 w-3.5 items-center justify-center' },
      h(RadixSelect.ItemIndicator, null,
        h('svg', {
          xmlns: 'http://www.w3.org/2000/svg', viewBox: '0 0 24 24', fill: 'none',
          stroke: 'currentColor', strokeWidth: 2, strokeLinecap: 'round', strokeLinejoin: 'round',
          className: 'h-4 w-4',
        }, h('polyline', { points: '20 6 9 17 4 12' }))
      )
    ),
    h(RadixSelect.ItemText, null, children)
  )

const SelectLabel = ({ className, ...props }: React.ComponentProps<typeof RadixSelect.Label>) =>
  h(RadixSelect.Label, { className: cn('px-2 py-1.5 text-sm font-semibold', className), ...props })

const SelectSeparator = ({ className, ...props }: React.ComponentProps<typeof RadixSelect.Separator>) =>
  h(RadixSelect.Separator, { className: cn('-mx-1 my-1 h-px bg-[var(--border)]', className), ...props })

// ── Primitives: Switch (Radix) ──

const Switch = ({ className, ...props }: React.ComponentProps<typeof RadixSwitch.Root>) =>
  h(RadixSwitch.Root, {
    className: cn('peer inline-flex h-5 w-9 shrink-0 cursor-pointer items-center rounded-full border-2 border-transparent transition-colors focus-visible:outline-none focus-visible:ring-2 focus-visible:ring-[var(--accent)] disabled:cursor-not-allowed disabled:opacity-50 data-[state=checked]:bg-[var(--accent)] data-[state=unchecked]:bg-[var(--hover-overlay)]', className),
    ...props,
  },
    h(RadixSwitch.Thumb, {
      className: 'pointer-events-none block h-4 w-4 rounded-full bg-white shadow-lg ring-0 transition-transform data-[state=checked]:translate-x-4 data-[state=unchecked]:translate-x-0',
    })
  )

// ── Primitives: Checkbox (Radix) ──

const Checkbox = ({ className, ...props }: React.ComponentProps<typeof RadixCheckbox.Root>) =>
  h(RadixCheckbox.Root, {
    className: cn('peer h-4 w-4 shrink-0 rounded-sm border border-[var(--border)] focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-[var(--accent)] disabled:cursor-not-allowed disabled:opacity-50 data-[state=checked]:bg-[var(--accent)] data-[state=checked]:text-white', className),
    ...props,
  },
    h(RadixCheckbox.Indicator, { className: 'flex items-center justify-center text-current' },
      h('svg', {
        xmlns: 'http://www.w3.org/2000/svg', viewBox: '0 0 24 24', fill: 'none',
        stroke: 'currentColor', strokeWidth: 2, strokeLinecap: 'round', strokeLinejoin: 'round',
        className: 'h-3 w-3',
      }, h('polyline', { points: '20 6 9 17 4 12' }))
    )
  )

// ── Primitives: Slider (Radix) ──

const Slider = ({ className, ...props }: React.ComponentProps<typeof RadixSlider.Root>) =>
  h(RadixSlider.Root, {
    className: cn('relative flex w-full touch-none select-none items-center', className),
    ...props,
  },
    h(RadixSlider.Track, { className: 'relative h-1.5 w-full grow overflow-hidden rounded-full bg-[var(--hover-overlay)]' },
      h(RadixSlider.Range, { className: 'absolute h-full bg-[var(--accent)]' })
    ),
    h(RadixSlider.Thumb, {
      className: 'block h-4 w-4 rounded-full border border-[var(--accent)] bg-[var(--bg)] shadow transition-colors focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-[var(--accent)] disabled:pointer-events-none disabled:opacity-50',
    })
  )

// ── Primitives: Toggle (Radix) ──

const Toggle = ({ className, children, ...props }: React.ComponentProps<typeof RadixToggleGroup.Root>) =>
  h(RadixToggleGroup.Root, {
    className: cn('inline-flex items-center justify-center rounded-md text-sm font-medium transition-colors hover:bg-[var(--hover-overlay)] focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-[var(--accent)] disabled:pointer-events-none disabled:opacity-50 data-[state=on]:bg-[var(--hover-overlay)] data-[state=on]:text-[var(--fg)] h-9 px-3', className),
    ...props,
  }, children)

// ── Primitives: DropdownMenu (Radix) ──

const DropdownMenu = RadixDropdownMenu.Root
const DropdownMenuTrigger = RadixDropdownMenu.Trigger
const DropdownMenuGroup = RadixDropdownMenu.Group
const DropdownMenuPortal = RadixDropdownMenu.Portal
const DropdownMenuSub = RadixDropdownMenu.Sub
const DropdownMenuRadioGroup = RadixDropdownMenu.RadioGroup

const DropdownMenuContent = ({ className, sideOffset = 4, children, ...props }: React.ComponentProps<typeof RadixDropdownMenu.Content>) =>
  h(RadixDropdownMenu.Portal, null,
    h(RadixDropdownMenu.Content, {
      sideOffset,
      className: cn('z-50 min-w-[8rem] overflow-hidden rounded-md border border-[var(--border)] bg-[var(--bg)] p-1 text-[var(--fg)] shadow-md', className),
      ...props,
    }, children)
  )

const DropdownMenuItem = ({ className, ...props }: React.ComponentProps<typeof RadixDropdownMenu.Item>) =>
  h(RadixDropdownMenu.Item, {
    className: cn('relative flex cursor-default select-none items-center rounded-sm px-2 py-1.5 text-sm outline-none transition-colors focus:bg-[var(--hover-overlay)] data-[disabled]:pointer-events-none data-[disabled]:opacity-50', className),
    ...props,
  })

const DropdownMenuLabel = ({ className, ...props }: React.ComponentProps<typeof RadixDropdownMenu.Label>) =>
  h(RadixDropdownMenu.Label, { className: cn('px-2 py-1.5 text-sm font-semibold', className), ...props })

const DropdownMenuSeparator = ({ className, ...props }: React.ComponentProps<typeof RadixDropdownMenu.Separator>) =>
  h(RadixDropdownMenu.Separator, { className: cn('-mx-1 my-1 h-px bg-[var(--border)]', className), ...props })

const DropdownMenuShortcut = ({ className, children }: { className?: string; children?: React.ReactNode }) =>
  h('span', { className: cn('ml-auto text-xs tracking-widest opacity-60', className) }, children)

// ── Primitives: Breadcrumb (semantic HTML) ──

const Breadcrumb = ({ className, children, ...props }: React.HTMLAttributes<HTMLElement>) =>
  h('nav', { 'aria-label': 'breadcrumb', className, ...props }, children)

const BreadcrumbList = ({ className, children, ...props }: React.HTMLAttributes<HTMLOListElement>) =>
  h('ol', {
    className: cn('flex flex-wrap items-center gap-1.5 break-words text-sm text-[var(--fg-muted)] sm:gap-2.5', className),
    ...props,
  }, children)

const BreadcrumbItem = ({ className, children, ...props }: React.HTMLAttributes<HTMLLIElement>) =>
  h('li', { className: cn('inline-flex items-center gap-1.5', className), ...props }, children)

const BreadcrumbLink = ({ className, href, children, ...props }: React.AnchorHTMLAttributes<HTMLAnchorElement>) =>
  h('a', { href, className: cn('transition-colors hover:text-[var(--fg)]', className), ...props }, children)

const BreadcrumbPage = ({ className, children, ...props }: React.HTMLAttributes<HTMLSpanElement>) =>
  h('span', {
    role: 'link',
    'aria-disabled': 'true',
    'aria-current': 'page',
    className: cn('font-normal text-[var(--fg)]', className),
    ...props,
  }, children)

const BreadcrumbSeparator = ({ className, children, ...props }: React.HTMLAttributes<HTMLLIElement>) =>
  h('li', { role: 'presentation', 'aria-hidden': 'true', className: cn('[&>svg]:size-3.5', className), ...props },
    children || h('svg', {
      xmlns: 'http://www.w3.org/2000/svg', viewBox: '0 0 24 24', fill: 'none',
      stroke: 'currentColor', strokeWidth: 2, strokeLinecap: 'round', strokeLinejoin: 'round',
      className: 'h-3.5 w-3.5',
    }, h('polyline', { points: '9 18 15 12 9 6' }))
  )

// ── Primitives: Input / Label / Textarea ──

const Input = React.forwardRef<HTMLInputElement, React.InputHTMLAttributes<HTMLInputElement>>(
  ({ className, type, ...props }, ref) =>
    h('input', {
      ref, type,
      className: cn('flex h-9 w-full rounded-md border border-[var(--border)] bg-transparent px-3 py-1 text-sm shadow-sm transition-colors file:border-0 file:bg-transparent file:text-sm file:font-medium placeholder:text-[var(--fg-muted)] focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-[var(--accent)] disabled:cursor-not-allowed disabled:opacity-50', className),
      ...props,
    })
)
Input.displayName = 'Input'

const Label = React.forwardRef<HTMLLabelElement, React.LabelHTMLAttributes<HTMLLabelElement>>(
  ({ className, ...props }, ref) =>
    h('label', {
      ref,
      className: cn('text-sm font-medium leading-none peer-disabled:cursor-not-allowed peer-disabled:opacity-70', className),
      ...props,
    })
)
Label.displayName = 'Label'

const Textarea = React.forwardRef<HTMLTextAreaElement, React.TextareaHTMLAttributes<HTMLTextAreaElement>>(
  ({ className, ...props }, ref) =>
    h('textarea', {
      ref,
      className: cn('flex min-h-[60px] w-full rounded-md border border-[var(--border)] bg-transparent px-3 py-2 text-sm shadow-sm placeholder:text-[var(--fg-muted)] focus-visible:outline-none focus-visible:ring-1 focus-visible:ring-[var(--accent)] disabled:cursor-not-allowed disabled:opacity-50', className),
      ...props,
    })
)
Textarea.displayName = 'Textarea'

// ── State ──

let reactRoot: Root | null = null
let bridgeState = { isDark: false, colors: {} as Record<string, string>, projectName: '' }
// Correlation id for the current render. Set on every 'render' message, echoed
// back in every outbound 'rendered' / 'error' message so the parent (and Kotlin)
// can match outcomes to the originating render_artifact tool call.
let currentRenderId: string | null = null

// ── Error parsing ──

/**
 * Extract identifiers that look like missing scope symbols from a runtime error.
 *
 * Handles the three common V8 phrasings produced when LLM-generated code
 * references a symbol that isn't in the sandbox scope:
 *
 *   1. `ReferenceError: AccordionTrigger is not defined`
 *      — primary case: identifier used at the top of an expression.
 *   2. `TypeError: Cannot read properties of undefined (reading 'StrictMode')`
 *      — happens when code does `React.StrictMode` but `React` is undefined.
 *      The missing symbol is the property name (best guess — there's no reliable
 *      way to know whether the fix is to add `React` or to add `StrictMode` to
 *      scope, but the property name is usually what the LLM wanted).
 *   3. `ReferenceError: Cannot access 'someVar' before initialization`
 *      — temporal dead zone; the symbol name is the TDZ-tripping identifier.
 *
 * All three regexes run with the `g` flag so multiple distinct symbols across
 * the message are extracted. The returned list is deduped.
 *
 * NOTE: These patterns assume English-locale V8 error messages. JCEF ships with
 * English Chromium by default in IntelliJ, so this is reliable today. If JCEF
 * ever exposes locale-specific error messages, Layer 2 (static scope diffing)
 * would be the locale-independent fallback.
 */
function extractMissingSymbols(message: string): string[] {
  if (!message) return []
  const found = new Set<string>()

  const patterns: RegExp[] = [
    /(\w+)\s+is\s+not\s+defined/g,
    /Cannot\s+read\s+propert(?:y|ies)\s+of\s+undefined\s+\(reading\s+['"](\w+)['"]\)/g,
    /Cannot\s+access\s+['"](\w+)['"]\s+before\s+initialization/g,
  ]

  for (const re of patterns) {
    let m: RegExpExecArray | null
    // Reset lastIndex defensively — regex literals are created fresh each call
    // so this is just belt-and-braces against future refactors.
    re.lastIndex = 0
    while ((m = re.exec(message)) !== null) {
      const symbol = m[1]
      if (symbol) found.add(symbol)
    }
  }

  return Array.from(found)
}

// ── Error display ──

function showError(phase: string, message: string, line?: number) {
  const el = document.getElementById('error-display')
  const rootEl = document.getElementById('root')
  if (el) {
    el.style.display = 'block'
    el.textContent = `[${phase}] ${line ? `Line ${line}: ` : ''}${message}`
  }
  if (rootEl) rootEl.style.display = 'none'
  const missingSymbols = extractMissingSymbols(message)
  sendToParent({
    type: 'error',
    phase,
    message,
    line,
    missingSymbols,
    renderId: currentRenderId,
  })
}

function clearError() {
  const el = document.getElementById('error-display')
  const rootEl = document.getElementById('root')
  if (el) el.style.display = 'none'
  if (rootEl) rootEl.style.display = 'block'
}

// ── Communication ──

function sendToParent(msg: Record<string, unknown>) {
  try { window.parent.postMessage(msg, '*') } catch { /* iframe may be detached */ }
}

function reportHeight() {
  const height = document.body.scrollHeight
  sendToParent({ type: 'rendered', height, renderId: currentRenderId })
}

// ── Console interception ──

const originalConsole = {
  log: console.log.bind(console),
  warn: console.warn.bind(console),
  error: console.error.bind(console),
}

;(['log', 'warn', 'error'] as const).forEach(level => {
  console[level] = (...args: unknown[]) => {
    originalConsole[level](...args)
    sendToParent({ type: 'console', level, args: args.map(a => String(a)) })
  }
})

// ── Error Boundary ──

class ErrorBoundary extends React.Component<
  { children: React.ReactNode },
  { hasError: boolean; error: Error | null }
> {
  constructor(props: { children: React.ReactNode }) {
    super(props)
    this.state = { hasError: false, error: null }
  }

  static getDerivedStateFromError(error: Error) {
    return { hasError: true, error }
  }

  componentDidCatch(error: Error) {
    showError('render', error.message)
  }

  render() {
    if (this.state.hasError) {
      return React.createElement('div', {
        style: { padding: 12, color: 'var(--error-fg, #f48771)', fontSize: 12, fontFamily: 'monospace' }
      }, `Render error: ${this.state.error?.message || 'Unknown'}`)
    }
    return this.props.children
  }
}

// ── Render ──

function renderComponent(source: string, scope: Record<string, unknown>) {
  if (!reactRoot) {
    showError('runtime', 'Sandbox not initialized')
    return
  }

  clearError()

  try {
    const fullScope: Record<string, unknown> = {
      // ── React hooks ──
      React,
      useState, useEffect, useCallback, useMemo, useRef,
      useReducer, useLayoutEffect, useId, useTransition,
      Fragment,

      // ── Bridge ──
      bridge: {
        navigateToFile(path: string, line?: number) {
          sendToParent({ type: 'bridge', action: 'navigateToFile', args: [path, line] })
        },
        get isDark() { return bridgeState.isDark },
        get colors() { return { ...bridgeState.colors } },
        get projectName() { return bridgeState.projectName },
      },

      // ── Lucide Icons (all 1500+ icons as individual scope vars) ──
      // Spread first so that UI primitives, Recharts, and other libraries
      // override colliding names (e.g. Badge, BarChart, PieChart, Radar, Funnel)
      ...LucideIcons,

      // ── UI Primitives: Layout & Content ──
      Card, CardHeader, CardTitle, CardDescription, CardContent, CardFooter,
      Badge,
      Button,
      Separator,
      ScrollArea, ScrollBar,
      Skeleton,

      // ── UI Primitives: Feedback ──
      Alert, AlertTitle, AlertDescription,
      Progress,
      Tooltip, TooltipProvider,

      // ── UI Primitives: Navigation & Disclosure ──
      Tabs, TabsList, TabsTrigger, TabsContent,
      Accordion, AccordionItem, AccordionTrigger, AccordionContent,
      Breadcrumb, BreadcrumbList, BreadcrumbItem, BreadcrumbLink, BreadcrumbPage, BreadcrumbSeparator,

      // ── UI Primitives: Overlays ──
      Dialog, DialogTrigger, DialogClose, DialogPortal, DialogOverlay,
      DialogContent, DialogHeader, DialogFooter, DialogTitle, DialogDescription,
      Sheet, SheetTrigger, SheetClose, SheetContent, SheetHeader, SheetFooter, SheetTitle, SheetDescription,
      Popover, PopoverTrigger, PopoverContent, PopoverAnchor,
      HoverCard, HoverCardTrigger, HoverCardContent,
      DropdownMenu, DropdownMenuTrigger, DropdownMenuContent, DropdownMenuItem,
      DropdownMenuLabel, DropdownMenuSeparator, DropdownMenuShortcut,
      DropdownMenuGroup, DropdownMenuPortal, DropdownMenuSub, DropdownMenuRadioGroup,

      // ── UI Primitives: Forms / Inputs ──
      Input, Label, Textarea,
      Select, SelectValue, SelectGroup, SelectTrigger, SelectContent,
      SelectItem, SelectLabel, SelectSeparator,
      Switch,
      Checkbox,
      Slider,
      Toggle,

      // ── UI Primitives: Data display ──
      Avatar, AvatarImage, AvatarFallback,

      // ── Recharts ──
      BarChart, Bar, LineChart, Line, AreaChart, Area, PieChart, Pie, Cell,
      XAxis, YAxis, CartesianGrid, RechartsTooltip, Legend,
      ResponsiveContainer, RadialBarChart, RadialBar, ComposedChart,
      Scatter, ScatterChart, Treemap, FunnelChart, Funnel, RadarChart,
      Radar, PolarGrid, PolarAngleAxis, PolarRadiusAxis, LabelList,

      // ── D3 ──
      d3,

      // ── Globe ──
      createGlobe,

      // ── Animation (motion/react) ──
      motion, AnimatePresence, useMotionValue, useTransform, useSpring,
      useInView, useScroll, useAnimation,

      // ── Hand-drawn graphics ──
      rough,

      // ── Geographic maps (react-simple-maps) ──
      ComposableMap, Geographies, Geography, Marker, MapLine,
      ZoomableGroup, Graticule, Sphere,

      // ── Node-edge graphs (@xyflow/react) ──
      // Exposed as the namespace `ReactFlow` plus common named exports.
      // Usage: <ReactFlow.ReactFlow nodes={...} edges={...} />
      //        const { nodes, edges } = ReactFlow.useReactFlow()
      ReactFlow: XYFlow,
      // Also expose the default ReactFlow component and popular hooks/utilities
      // directly so generated code can use `<ReactFlowCanvas />` style too.
      ReactFlowCanvas: XYFlow.ReactFlow,
      Background: XYFlow.Background,
      Controls: XYFlow.Controls,
      MiniMap: XYFlow.MiniMap,
      Handle: XYFlow.Handle,
      Position: XYFlow.Position,
      MarkerType: XYFlow.MarkerType,
      useNodesState: XYFlow.useNodesState,
      useEdgesState: XYFlow.useEdgesState,
      useReactFlow: XYFlow.useReactFlow,
      addEdge: XYFlow.addEdge,
      applyNodeChanges: XYFlow.applyNodeChanges,
      applyEdgeChanges: XYFlow.applyEdgeChanges,
      ReactFlowProvider: XYFlow.ReactFlowProvider,

      // ── Headless tables (@tanstack/react-table) ──
      // Exposed as the namespace `ReactTable` plus the most-used entry points.
      ReactTable,
      useReactTable: ReactTable.useReactTable,
      getCoreRowModel: ReactTable.getCoreRowModel,
      getSortedRowModel: ReactTable.getSortedRowModel,
      getFilteredRowModel: ReactTable.getFilteredRowModel,
      getPaginationRowModel: ReactTable.getPaginationRowModel,
      getGroupedRowModel: ReactTable.getGroupedRowModel,
      getExpandedRowModel: ReactTable.getExpandedRowModel,
      flexRender: ReactTable.flexRender,
      createColumnHelper: ReactTable.createColumnHelper,

      // ── Date / time (date-fns) ──
      // Exposed as `dateFns` namespace. Common named exports also spread for
      // terser generated code: `format(date, 'yyyy-MM-dd')`, `formatDistance(a, b)`.
      dateFns,
      format: dateFns.format,
      formatDistance: dateFns.formatDistance,
      formatDistanceToNow: dateFns.formatDistanceToNow,
      formatRelative: dateFns.formatRelative,
      parseISO: dateFns.parseISO,
      addDays: dateFns.addDays,
      subDays: dateFns.subDays,
      addHours: dateFns.addHours,
      subHours: dateFns.subHours,
      differenceInDays: dateFns.differenceInDays,
      differenceInHours: dateFns.differenceInHours,
      differenceInMinutes: dateFns.differenceInMinutes,
      isAfter: dateFns.isAfter,
      isBefore: dateFns.isBefore,
      isSameDay: dateFns.isSameDay,
      startOfDay: dateFns.startOfDay,
      endOfDay: dateFns.endOfDay,
      startOfWeek: dateFns.startOfWeek,
      endOfWeek: dateFns.endOfWeek,
      startOfMonth: dateFns.startOfMonth,
      endOfMonth: dateFns.endOfMonth,

      // ── Colors (colord) ──
      colord,
      colordExtend,

      // ── User-provided scope (libraries) ──
      ...scope,
    }

    const handleRendered = (error: Error | null) => {
      if (error) {
        showError('runtime', error.message || 'Runtime error')
      } else {
        requestAnimationFrame(() => reportHeight())
      }
    }

    const element = React.createElement(
      ErrorBoundary,
      null,
      React.createElement(Runner, {
        code: source,
        scope: fullScope,
        onRendered: handleRendered,
      } as any)
    )

    reactRoot.render(element)
  } catch (err: unknown) {
    showError('transpile', err instanceof Error ? err.message : String(err))
  }
}

// ── Message listener ──

window.addEventListener('message', (e: MessageEvent) => {
  const data = e.data
  if (!data || typeof data !== 'object' || typeof data.type !== 'string') return

  switch (data.type) {
    case 'render':
      // Capture the correlation id for this render round-trip. All outbound
      // 'rendered' / 'error' messages will include this so Kotlin can match
      // outcomes to the originating render_artifact tool call. Null-tolerant
      // for legacy callers that don't supply renderId.
      currentRenderId = typeof data.renderId === 'string' ? data.renderId : null
      renderComponent(data.source as string, (data.scope as Record<string, unknown>) || {})
      break

    case 'theme':
      bridgeState.isDark = Boolean(data.isDark)
      bridgeState.colors = (data.colors as Record<string, string>) || {}
      bridgeState.projectName = (data.projectName as string) || ''
      // Apply CSS variables
      const root = document.documentElement
      root.style.colorScheme = bridgeState.isDark ? 'dark' : 'light'
      if (data.colors) {
        Object.entries(data.colors as Record<string, string>).forEach(([key, value]) => {
          root.style.setProperty(`--${key}`, value)
        })
      }
      // Toggle Tailwind dark mode class
      document.documentElement.classList.toggle('dark', bridgeState.isDark)
      break
  }
})

// ── Height observer ──

const rootEl = document.getElementById('root')
if (rootEl) {
  const resizeObserver = new ResizeObserver(() => reportHeight())
  resizeObserver.observe(rootEl)
}

// ── Initialize ──

const rootContainer = document.getElementById('root')
if (rootContainer) {
  reactRoot = createRoot(rootContainer)
  sendToParent({ type: 'ready' })
} else {
  showError('init', 'Root element not found')
}
