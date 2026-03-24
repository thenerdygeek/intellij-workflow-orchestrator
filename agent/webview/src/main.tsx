import { StrictMode } from 'react'
import { createRoot } from 'react-dom/client'
import './index.css'
import './styles/animations.css'
import App from './App'

// Fix Radix Popper positioning: @floating-ui miscalculates the portal position
// in our layout (flex + overflow: hidden chain), sending dropdowns off-screen.
// We fix Y via CSS (bottom: 60px) and align X to the trigger element here.
const popperObserver = new MutationObserver((mutations) => {
  for (const m of mutations) {
    for (const node of m.addedNodes) {
      if (!(node instanceof HTMLElement)) continue;
      const wrapper = node.matches('[data-radix-popper-content-wrapper]')
        ? node
        : node.querySelector?.('[data-radix-popper-content-wrapper]');
      if (!wrapper) continue;

      // Find the expanded trigger button that opened this dropdown
      const trigger = document.querySelector('[data-slot="dropdown-menu-trigger"][aria-expanded="true"]');
      if (trigger) {
        const triggerRect = trigger.getBoundingClientRect();
        (wrapper as HTMLElement).style.setProperty('--popper-fix-x', `${triggerRect.left}px`);
      }
    }
  }
});
popperObserver.observe(document.body, { childList: true, subtree: true });

createRoot(document.getElementById('root')!).render(
  <StrictMode>
    <App />
  </StrictMode>,
)
