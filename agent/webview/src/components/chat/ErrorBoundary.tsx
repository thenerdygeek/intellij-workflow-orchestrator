import { Component } from 'react';
import type { ReactNode, ErrorInfo } from 'react';

interface Props { children: ReactNode; fallback?: ReactNode; }
interface State { hasError: boolean; error?: Error; }

export class ErrorBoundary extends Component<Props, State> {
  state: State = { hasError: false };

  static getDerivedStateFromError(error: Error): State {
    return { hasError: true, error };
  }

  componentDidCatch(error: Error, info: ErrorInfo) {
    console.error('[ErrorBoundary] Render error:', error, info.componentStack);
  }

  render() {
    if (this.state.hasError) {
      return this.props.fallback ?? (
        <div className="px-4 py-2 text-[11px] rounded-md my-1"
          style={{ color: 'var(--error, #ef4444)', backgroundColor: 'var(--diff-rem-bg, rgba(239,68,68,0.1))' }}>
          Failed to render message.
          <button className="ml-2 underline" onClick={() => this.setState({ hasError: false })}>
            Retry
          </button>
        </div>
      );
    }
    return this.props.children;
  }
}
