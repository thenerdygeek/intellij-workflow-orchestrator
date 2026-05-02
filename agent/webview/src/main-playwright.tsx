import { StrictMode } from 'react';
import { createRoot } from 'react-dom/client';
import './index.css';
import { installMockBridge } from './harness/mock-bridge';
import { HarnessApp } from './harness/HarnessApp';

// Install the mock bridge BEFORE React mounts so any `useEffect` that calls
// `window._xxx` / `window.workflowAgent.*` on first render finds the stubs.
installMockBridge();

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <HarnessApp />
  </StrictMode>
);
