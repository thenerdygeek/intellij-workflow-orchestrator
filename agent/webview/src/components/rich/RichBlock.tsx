import { useState, useCallback, useMemo, type ReactNode, type ComponentType } from 'react';
import type { VisualizationType } from '@/bridge/types';
import { openInEditorTab } from '@/bridge/jcef-bridge';
import { useSettingsStore } from '@/stores/settingsStore';
import { useThemeStore } from '@/stores/themeStore';
import {
  Dialog,
  DialogContent,
  DialogHeader,
  DialogTitle,
} from '@/components/ui/dialog';

// ── Type metadata ──

interface TypeMeta {
  icon: string;
  label: string;
}

const TYPE_META: Record<VisualizationType, TypeMeta> = {
  mermaid: { icon: '\u25C7', label: 'Mermaid Diagram' },
  chart: { icon: '\u25CE', label: 'Chart' },
  flow: { icon: '\u2192', label: 'Data Flow' },
  math: { icon: '\u03A3', label: 'Math' },
  diff: { icon: '\u00B1', label: 'Diff' },
  interactiveHtml: { icon: '\u25A3', label: 'Interactive' },
  table: { icon: '\u229E', label: 'Table' },
  output: { icon: '\u{1F4CB}', label: 'Output' },
  progress: { icon: '\u23F3', label: 'Progress' },
  timeline: { icon: '\u23F1', label: 'Timeline' },
  image: { icon: '\uD83D\uDDBC', label: 'Image' },
  artifact: { icon: '\u2B22', label: 'Interactive' },
};

// ── Loading skeleton ──

function LoadingSkeleton() {
  return (
    <div className="space-y-2 p-4" aria-label="Loading visualization">
      <div className="h-3 w-3/4 rounded bg-[var(--hover-overlay)] animate-[shimmer_1.5s_ease-in-out_infinite] bg-[length:200%_100%] bg-gradient-to-r from-[var(--hover-overlay)] via-[var(--hover-overlay-strong)] to-[var(--hover-overlay)]" />
      <div className="h-3 w-1/2 rounded bg-[var(--hover-overlay)] animate-[shimmer_1.5s_ease-in-out_infinite_100ms] bg-[length:200%_100%] bg-gradient-to-r from-[var(--hover-overlay)] via-[var(--hover-overlay-strong)] to-[var(--hover-overlay)]" />
      <div className="h-3 w-5/6 rounded bg-[var(--hover-overlay)] animate-[shimmer_1.5s_ease-in-out_infinite_200ms] bg-[length:200%_100%] bg-gradient-to-r from-[var(--hover-overlay)] via-[var(--hover-overlay-strong)] to-[var(--hover-overlay)]" />
      <div className="h-20 w-full rounded bg-[var(--hover-overlay)] animate-[shimmer_1.5s_ease-in-out_infinite_300ms] bg-[length:200%_100%] bg-gradient-to-r from-[var(--hover-overlay)] via-[var(--hover-overlay-strong)] to-[var(--hover-overlay)]" />
    </div>
  );
}

// ── Error state ──

interface ErrorStateProps {
  error: Error;
  source: string;
  onRetry: () => void;
}

function ErrorState({ error, source, onRetry }: ErrorStateProps) {
  const [showSource, setShowSource] = useState(false);

  return (
    <div className="rounded-lg border border-[var(--error)]/30 bg-[var(--error)]/5 p-4">
      <div className="flex items-center justify-between">
        <div className="flex items-center gap-2 text-sm text-[var(--error)]">
          <svg width="14" height="14" viewBox="0 0 14 14" fill="none">
            <circle cx="7" cy="7" r="6" stroke="currentColor" strokeWidth="1.5" />
            <path d="M7 4v4M7 9.5v.5" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" />
          </svg>
          <span>Failed to render: {error.message}</span>
        </div>
        <div className="flex items-center gap-1">
          <button
            onClick={() => setShowSource(!showSource)}
            className="rounded px-2 py-1 text-xs text-[var(--fg-muted)] transition-colors hover:bg-[var(--hover-overlay)] hover:text-[var(--fg)]"
          >
            {showSource ? 'Hide source' : 'Show source'}
          </button>
          <button
            onClick={onRetry}
            className="rounded px-2 py-1 text-xs text-[var(--link)] transition-colors hover:bg-[var(--hover-overlay)]"
          >
            Retry
          </button>
        </div>
      </div>
      {showSource && (
        <pre className="mt-3 max-h-40 overflow-auto rounded bg-[var(--code-bg)] p-3 text-xs text-[var(--fg-secondary)]">
          {source}
        </pre>
      )}
    </div>
  );
}

// ── Action button ──

interface ActionButtonProps {
  label: string;
  onClick: () => void;
  children: ReactNode;
}

function ActionButton({ label, onClick, children }: ActionButtonProps) {
  return (
    <button
      onClick={onClick}
      title={label}
      aria-label={label}
      className="flex h-6 w-6 items-center justify-center rounded text-[var(--fg-muted)] transition-colors hover:bg-[var(--hover-overlay)] hover:text-[var(--fg)]"
    >
      {children}
    </button>
  );
}

// ── Main RichBlock ──

export interface RichBlockProps {
  type: VisualizationType;
  source: string;
  isLoading?: boolean;
  error?: Error | null;
  onRetry?: () => void;
  children?: ReactNode;
  renderContent?: ComponentType<{ source: string; isDark: boolean }>;
}

export function RichBlock({
  type,
  source,
  isLoading = false,
  error = null,
  onRetry,
  children,
  renderContent: RenderContent,
}: RichBlockProps) {
  const config = useSettingsStore((s) => s.visualizations[type]);
  const isDark = useThemeStore((s) => s.isDark);
  const cssVariables = useThemeStore((s) => s.cssVariables);

  const [isExpanded, setIsExpanded] = useState(config?.defaultExpanded ?? false);
  const [isFullscreen, setIsFullscreen] = useState(false);

  const meta = TYPE_META[type];

  // Theme key forces re-render when theme changes
  const themeKey = useMemo(
    () => `${isDark ? 'dark' : 'light'}-${cssVariables['bg'] ?? ''}`,
    [isDark, cssVariables],
  );

  const handleCopySource = useCallback(() => {
    void navigator.clipboard.writeText(source);
  }, [source]);

  const handleToggleExpand = useCallback(() => {
    setIsExpanded((prev) => !prev);
  }, []);

  const handleOpenInTab = useCallback(() => {
    openInEditorTab(type, source);
  }, [type, source]);

  const handleFullscreen = useCallback(() => {
    setIsFullscreen(true);
  }, []);

  const handleCloseFullscreen = useCallback(() => {
    setIsFullscreen(false);
  }, []);

  // If visualization type is disabled, show raw source
  if (!config?.enabled) {
    return (
      <pre className="overflow-auto rounded-lg bg-[var(--code-bg)] p-3 text-xs text-[var(--fg-secondary)]">
        {source}
      </pre>
    );
  }

  const maxHeight = config?.maxHeight ?? 300;
  const shouldConstrain = maxHeight > 0 && !isExpanded;

  const renderedContent = (
    <div key={themeKey}>
      {isLoading ? (
        <LoadingSkeleton />
      ) : error ? (
        <ErrorState error={error} source={source} onRetry={onRetry ?? (() => {})} />
      ) : RenderContent ? (
        <RenderContent source={source} isDark={isDark} />
      ) : (
        children
      )}
    </div>
  );

  return (
    <>
      <div className="my-2 overflow-hidden rounded-lg border border-[var(--border)] bg-[var(--code-bg)]">
        {/* Header */}
        <div className="flex items-center justify-between border-b border-[var(--border)] px-3 py-1.5">
          <div className="flex items-center gap-2">
            <span className="text-xs text-[var(--fg-muted)]">{meta?.icon}</span>
            <span className="text-xs font-medium text-[var(--fg-secondary)]">
              {meta?.label ?? type}
            </span>
          </div>
          <div className="flex items-center gap-0.5">
            {/* Copy source */}
            <ActionButton label="Copy source" onClick={handleCopySource}>
              <svg width="12" height="12" viewBox="0 0 12 12" fill="none">
                <rect x="3.5" y="3.5" width="7" height="7" rx="1" stroke="currentColor" strokeWidth="1.2" />
                <path d="M8.5 3.5V2a1 1 0 00-1-1H2a1 1 0 00-1 1v5.5a1 1 0 001 1h1.5" stroke="currentColor" strokeWidth="1.2" />
              </svg>
            </ActionButton>

            {/* Expand / collapse */}
            {maxHeight > 0 && (
              <ActionButton
                label={isExpanded ? 'Collapse' : 'Expand'}
                onClick={handleToggleExpand}
              >
                <svg width="12" height="12" viewBox="0 0 12 12" fill="none">
                  {isExpanded ? (
                    <path d="M3 7.5L6 4.5L9 7.5" stroke="currentColor" strokeWidth="1.2" strokeLinecap="round" strokeLinejoin="round" />
                  ) : (
                    <path d="M3 4.5L6 7.5L9 4.5" stroke="currentColor" strokeWidth="1.2" strokeLinecap="round" strokeLinejoin="round" />
                  )}
                </svg>
              </ActionButton>
            )}

            {/* Open in editor tab */}
            <ActionButton label="Open in editor tab" onClick={handleOpenInTab}>
              <svg width="12" height="12" viewBox="0 0 12 12" fill="none">
                <path d="M5 1H2a1 1 0 00-1 1v8a1 1 0 001 1h8a1 1 0 001-1V7" stroke="currentColor" strokeWidth="1.2" strokeLinecap="round" strokeLinejoin="round" />
                <path d="M7 1h4v4M7 5l4-4" stroke="currentColor" strokeWidth="1.2" strokeLinecap="round" strokeLinejoin="round" />
              </svg>
            </ActionButton>

            {/* Fullscreen */}
            <ActionButton label="Open fullscreen" onClick={handleFullscreen}>
              <svg width="12" height="12" viewBox="0 0 12 12" fill="none">
                <path d="M1 4V1h3M8 1h3v3M11 8v3H8M4 11H1V8" stroke="currentColor" strokeWidth="1.2" strokeLinecap="round" strokeLinejoin="round" />
              </svg>
            </ActionButton>
          </div>
        </div>

        {/* Content area */}
        <div className="relative">
          <div
            className="overflow-hidden transition-[max-height] duration-300 ease-in-out"
            style={shouldConstrain ? { maxHeight: `${maxHeight}px` } : undefined}
          >
            {renderedContent}
          </div>

          {/* Gradient fade + Show more button */}
          {shouldConstrain && !isLoading && !error && (
            <div className="absolute right-0 bottom-0 left-0 flex items-end justify-center bg-gradient-to-t from-[var(--code-bg)] to-transparent pt-8 pb-2">
              <button
                onClick={handleToggleExpand}
                className="rounded-md bg-[var(--hover-overlay-strong)] px-3 py-1 text-xs text-[var(--fg-secondary)] transition-colors hover:bg-[var(--hover-overlay)] hover:text-[var(--fg)]"
              >
                Show more
              </button>
            </div>
          )}
        </div>
      </div>

      {/* Fullscreen dialog */}
      <Dialog open={isFullscreen} onOpenChange={(open) => !open && handleCloseFullscreen()}>
        <DialogContent className="max-w-5xl max-h-[90vh] overflow-auto bg-[var(--bg)] border-[var(--border)]">
          <DialogHeader>
            <DialogTitle className="text-sm font-semibold text-[var(--fg)]">
              {meta?.label ?? type}
            </DialogTitle>
          </DialogHeader>
          <div className="flex-1 overflow-auto">
            {renderedContent}
          </div>
        </DialogContent>
      </Dialog>
    </>
  );
}
