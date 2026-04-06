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
