import '@testing-library/jest-dom/vitest'

// Mock window globals that JCEF injects — these simulate the Kotlin bridge
// In the real app, these are injected by AgentCefPanel.kt on page load
Object.defineProperty(window, '_approvePlan', { value: vi.fn(), writable: true });
Object.defineProperty(window, '_revisePlan', { value: vi.fn(), writable: true });
Object.defineProperty(window, '_openFile', { value: vi.fn(), writable: true });
Object.defineProperty(window, '_navigateToFile', { value: vi.fn(), writable: true });
Object.defineProperty(window, '_cancelTask', { value: vi.fn(), writable: true });
Object.defineProperty(window, '_revertCheckpoint', { value: vi.fn(), writable: true });
Object.defineProperty(window, '_submitPrompt', { value: vi.fn(), writable: true });
Object.defineProperty(window, 'setPlanPending', { value: vi.fn(), writable: true });
Object.defineProperty(window, '_copyToClipboard', { value: vi.fn(), writable: true });
