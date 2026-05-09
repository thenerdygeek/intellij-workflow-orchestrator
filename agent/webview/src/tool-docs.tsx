import { StrictMode, useEffect, useState } from 'react';
import { createRoot } from 'react-dom/client';
import './index.css';
import { ToolDocView, type ToolDocPayload } from './components/tool-docs/ToolDocView';

// ── Window globals — called from ToolDocsEditor.kt ───────────────────────────

(window as any).applyTheme = (vars: Record<string, string>) => {
  const root = document.documentElement;
  Object.entries(vars).forEach(([k, v]) => root.style.setProperty('--' + k, v));
};

let setPayloadExternal: ((p: ToolDocPayload | DocError) => void) | null = null;
let pendingPayload: ToolDocPayload | DocError | null = null;

(window as any).renderToolDoc = (json: string) => {
  try {
    const data = JSON.parse(json);
    if (setPayloadExternal) setPayloadExternal(data);
    else pendingPayload = data;
  } catch (e) {
    const err: DocError = { toolName: 'unknown', error: 'Failed to parse tool doc payload: ' + (e as Error).message };
    if (setPayloadExternal) setPayloadExternal(err);
    else pendingPayload = err;
  }
};

interface DocError {
  toolName: string;
  error: string;
}

function isError(p: ToolDocPayload | DocError): p is DocError {
  return 'error' in p && typeof (p as DocError).error === 'string';
}

// ── App ───────────────────────────────────────────────────────────────────────

function ToolDocsApp() {
  const [payload, setPayload] = useState<ToolDocPayload | DocError | null>(null);

  useEffect(() => {
    setPayloadExternal = setPayload;
    if (pendingPayload) {
      setPayload(pendingPayload);
      pendingPayload = null;
    }
    return () => {
      setPayloadExternal = null;
    };
  }, []);

  if (!payload) {
    return (
      <div className="flex items-center justify-center h-screen text-[var(--fg-muted,#6e7681)] text-sm">
        Loading tool documentation…
      </div>
    );
  }

  if (isError(payload)) {
    return (
      <div className="px-8 py-6 max-w-3xl">
        <div
          className="rounded-lg p-4"
          style={{
            background: 'rgba(248,81,73,0.08)',
            border: '1px solid rgba(248,81,73,0.3)',
            color: 'var(--error,#f85149)',
          }}
        >
          <div className="text-sm font-semibold mb-1">{payload.toolName}</div>
          <div className="text-sm">{payload.error}</div>
        </div>
      </div>
    );
  }

  return <ToolDocView doc={payload} />;
}

const root = createRoot(document.getElementById('root')!);
root.render(
  <StrictMode>
    <ToolDocsApp />
  </StrictMode>,
);
