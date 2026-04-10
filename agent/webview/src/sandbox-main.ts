/**
 * Entry point for artifact-sandbox.html.
 *
 * This file is imported by the sandbox HTML via <script type="module" src="...">.
 * Vite resolves the bare module imports (react, react-dom/client, react-runner)
 * at build time, bundling them into the sandbox's output.
 */

import React, { useState, useEffect, useCallback, useMemo, useRef, Fragment } from 'react'
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

// ── UI Primitive Components ──

const h = React.createElement

const Card = ({ className, children, onClick }: { className?: string; children?: React.ReactNode; onClick?: () => void }) =>
  h('div', {
    className: `rounded-lg border border-[var(--border)] bg-[var(--code-bg)] shadow-sm ${onClick ? 'cursor-pointer' : ''} ${className || ''}`.trim(),
    onClick,
  }, children)

const CardHeader = ({ className, children }: { className?: string; children?: React.ReactNode }) =>
  h('div', { className: `flex flex-col space-y-1.5 p-4 pb-2 ${className || ''}`.trim() }, children)

const CardTitle = ({ className, children }: { className?: string; children?: React.ReactNode }) =>
  h('h3', { className: `text-sm font-semibold leading-none tracking-tight ${className || ''}`.trim() }, children)

const CardDescription = ({ className, children }: { className?: string; children?: React.ReactNode }) =>
  h('p', { className: `text-xs text-[var(--fg-muted)] ${className || ''}`.trim() }, children)

const CardContent = ({ className, children }: { className?: string; children?: React.ReactNode }) =>
  h('div', { className: `p-4 pt-0 ${className || ''}`.trim() }, children)

const badgeVariants: Record<string, string> = {
  default: 'bg-[var(--accent)]/10 text-[var(--accent)] border-[var(--accent)]/20',
  success: 'bg-[var(--success)]/10 text-[var(--success)] border-[var(--success)]/20',
  warning: 'bg-[var(--warning)]/10 text-[var(--warning)] border-[var(--warning)]/20',
  error: 'bg-[var(--error)]/10 text-[var(--error)] border-[var(--error)]/20',
  outline: 'bg-transparent text-[var(--fg)] border-[var(--border)]',
}

const Badge = ({ variant = 'default', className, children }: { variant?: string; className?: string; children?: React.ReactNode }) =>
  h('span', {
    className: `inline-flex items-center rounded-full border px-2.5 py-0.5 text-xs font-medium transition-colors ${badgeVariants[variant] || badgeVariants.default} ${className || ''}`.trim(),
  }, children)

const Tabs = ({ value, onValueChange, children }: { value: string; onValueChange: (v: string) => void; children?: React.ReactNode }) =>
  h('div', { className: 'w-full' },
    React.Children.map(children, child =>
      React.isValidElement(child)
        ? React.cloneElement(child as React.ReactElement<any>, { _activeTab: value, _onTabChange: onValueChange })
        : child
    )
  )

const TabsList = ({ className, children, _activeTab, _onTabChange }: { className?: string; children?: React.ReactNode; _activeTab?: string; _onTabChange?: (v: string) => void }) =>
  h('div', {
    className: `inline-flex h-9 items-center justify-center rounded-lg bg-[var(--code-bg)] p-1 ${className || ''}`.trim(),
  },
    React.Children.map(children, child =>
      React.isValidElement(child)
        ? React.cloneElement(child as React.ReactElement<any>, { _activeTab, _onTabChange })
        : child
    )
  )

const TabsTrigger = ({ value, children, _activeTab, _onTabChange }: { value: string; children?: React.ReactNode; _activeTab?: string; _onTabChange?: (v: string) => void }) => {
  const isActive = _activeTab === value
  return h('button', {
    className: `inline-flex items-center justify-center whitespace-nowrap rounded-md px-3 py-1 text-xs font-medium transition-all ${
      isActive
        ? 'bg-[var(--bg)] text-[var(--fg)] shadow-sm'
        : 'text-[var(--fg-muted)] hover:text-[var(--fg)]'
    }`.trim(),
    onClick: () => _onTabChange?.(value),
  }, children)
}

const TabsContent = ({ value, children, _activeTab }: { value: string; children?: React.ReactNode; _activeTab?: string }) => {
  if (_activeTab !== value) return null
  return h('div', { className: 'mt-3' }, children)
}

const progressVariants: Record<string, string> = {
  default: 'bg-[var(--accent)]',
  success: 'bg-[var(--success)]',
  warning: 'bg-[var(--warning)]',
  error: 'bg-[var(--error)]',
}

const Progress = ({ value, variant = 'default', className }: { value: number; variant?: string; className?: string }) =>
  h('div', {
    className: `rounded-full bg-[var(--code-bg)] h-2 ${className || ''}`.trim(),
  },
    h('div', {
      className: `h-full rounded-full transition-all ${progressVariants[variant] || progressVariants.default}`.trim(),
      style: { width: `${Math.max(0, Math.min(100, value))}%` },
    })
  )

const Separator = ({ className }: { className?: string }) =>
  h('div', { className: `h-px w-full bg-[var(--border)] ${className || ''}`.trim() })

const Accordion = ({ children }: { children?: React.ReactNode }) =>
  h('div', { className: 'divide-y divide-[var(--border)] rounded-lg border border-[var(--border)]' }, children)

const AccordionItem = ({ title, children, defaultOpen = false }: { title: React.ReactNode; children?: React.ReactNode; defaultOpen?: boolean }) => {
  const [open, setOpen] = React.useState(defaultOpen)
  return h('div', null,
    h('button', {
      className: 'flex w-full items-center justify-between p-3 text-sm font-medium hover:bg-[var(--code-bg)] transition-colors',
      onClick: () => setOpen(!open),
    },
      h('span', null, title),
      h('svg', {
        className: `h-4 w-4 shrink-0 transition-transform ${open ? 'rotate-180' : ''}`.trim(),
        xmlns: 'http://www.w3.org/2000/svg',
        viewBox: '0 0 24 24',
        fill: 'none',
        stroke: 'currentColor',
        strokeWidth: 2,
        strokeLinecap: 'round',
        strokeLinejoin: 'round',
      }, h('polyline', { points: '6 9 12 15 18 9' }))
    ),
    open ? h('div', { className: 'px-3 pb-3 text-sm' }, children) : null
  )
}

const Tooltip = ({ content, children }: { content: React.ReactNode; children?: React.ReactNode }) => {
  const [show, setShow] = React.useState(false)
  return h('div', {
    className: 'relative inline-block',
    onMouseEnter: () => setShow(true),
    onMouseLeave: () => setShow(false),
  },
    children,
    show
      ? h('div', {
          className: 'absolute bottom-full left-1/2 -translate-x-1/2 mb-2 px-2 py-1 text-xs rounded bg-[var(--fg)] text-[var(--bg)] whitespace-nowrap z-50 pointer-events-none',
        }, content)
      : null
  )
}

// ── State ──

let reactRoot: Root | null = null
let bridgeState = { isDark: false, colors: {} as Record<string, string>, projectName: '' }

// ── Error display ──

function showError(phase: string, message: string, line?: number) {
  const el = document.getElementById('error-display')
  const rootEl = document.getElementById('root')
  if (el) {
    el.style.display = 'block'
    el.textContent = `[${phase}] ${line ? `Line ${line}: ` : ''}${message}`
  }
  if (rootEl) rootEl.style.display = 'none'
  sendToParent({ type: 'error', phase, message, line })
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
  sendToParent({ type: 'rendered', height })
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
      // React hooks
      React,
      useState,
      useEffect,
      useCallback,
      useMemo,
      useRef,
      Fragment,
      // Bridge
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

      // UI Primitives (after LucideIcons so our Badge/Tooltip win)
      Card, CardHeader, CardTitle, CardDescription, CardContent,
      Badge,
      Tabs, TabsList, TabsTrigger, TabsContent,
      Progress,
      Separator,
      Accordion, AccordionItem,
      Tooltip,

      // ── Recharts (after LucideIcons to win name collisions) ──
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

      // ── Geographic maps ──
      ComposableMap, Geographies, Geography, Marker, MapLine,
      ZoomableGroup, Graticule, Sphere,

      // User-provided scope (libraries)
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
