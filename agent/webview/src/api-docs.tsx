import { StrictMode, useEffect, useState } from 'react';
import { createRoot } from 'react-dom/client';
import './index.css';
import { ApiDocView, type ApiDocPayload } from './components/api-docs/ApiDocView';

(window as any).applyTheme = (vars: Record<string, string>) => {
  const root = document.documentElement;
  Object.entries(vars).forEach(([k, v]) => root.style.setProperty('--' + k, v));
};

let setPayloadExternal: ((p: ApiDocPayload) => void) | null = null;
let pendingPayload: ApiDocPayload | null = null;

(window as any).renderApiDocs = (json: string) => {
  try {
    const data = JSON.parse(json) as ApiDocPayload;
    if (setPayloadExternal) setPayloadExternal(data);
    else pendingPayload = data;
  } catch (e) {
    const err: ApiDocPayload = { families: [], loadErrors: [{ id: 'parse', error: (e as Error).message }] };
    if (setPayloadExternal) setPayloadExternal(err);
    else pendingPayload = err;
  }
};

function ApiDocsApp() {
  const [payload, setPayload] = useState<ApiDocPayload | null>(null);
  useEffect(() => {
    setPayloadExternal = setPayload;
    if (pendingPayload) { setPayload(pendingPayload); pendingPayload = null; }
    return () => { setPayloadExternal = null; };
  }, []);
  if (!payload) {
    return <div className="flex items-center justify-center h-screen text-[var(--fg-muted,#6e7681)] text-sm">
      Loading API documentation…
    </div>;
  }
  return <ApiDocView doc={payload} />;
}

const root = createRoot(document.getElementById('root')!);
root.render(<StrictMode><ApiDocsApp /></StrictMode>);
