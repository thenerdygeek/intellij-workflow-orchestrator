/**
 * Mock-bridge harness — replaces the JCEF-injected `window._xxx` and
 * `window.workflowAgent.*` surface so the webview bundle can render in a
 * standard browser (Playwright / manual `npm run dev`) without an IntelliJ
 * Kotlin host.
 *
 * Exposes a single `window.__harness` API so Playwright's `page.evaluate()`
 * can drive state from the test side. Setters are synchronous; React picks
 * up changes via the same polling/event loop that production would.
 *
 * Scope: ONLY the bridges Phase 5/6/7 components actually call. Adding more
 * bridges later is purely additive.
 */

import { useChatStore } from '../stores/chatStore';

interface ImageSettings {
  maxBytes: number;
  mimeWhitelist: string[];
  maxPerTurn: number;
  enabled: boolean;
}

interface HarnessState {
  contextUsage: { used: number; max: number };
  imageSettings: ImageSettings;
  /** Calls captured for assertion (e.g. `_changeModel("foo")`). */
  calls: { method: string; args: unknown[] }[];
  /** Last pushed model-list JSON so re-mounts can rehydrate. */
  lastModelListJson: string | null;
}

declare global {
  interface Window {
    __harness?: {
      setContextUsage: (used: number, max: number) => void;
      setImageSettings: (s: Partial<ImageSettings>) => void;
      pushImageSettingsToWebview: () => Promise<void>;
      pushModelList: (json: string) => void;
      getCalls: () => HarnessState['calls'];
      clearCalls: () => void;
    };
  }
}

const state: HarnessState = {
  contextUsage: { used: 0, max: 132_000 },
  imageSettings: {
    maxBytes: 5_242_880,
    mimeWhitelist: ['image/png', 'image/jpeg', 'image/webp'],
    maxPerTurn: 2,
    enabled: true,
  },
  calls: [],
  lastModelListJson: null,
};

function spy(method: string): (...args: unknown[]) => void {
  return (...args) => {
    state.calls.push({ method, args });
  };
}

export function installMockBridge(): void {
  const w = window as any;

  // ── workflowAgent namespace (Phase 7) ──────────────────────────────────────
  w.workflowAgent = {
    getContextUsage: async () => ({ ...state.contextUsage }),
    refreshImageSettings: async () => {
      // Mirrors AgentCefPanel.pushImageSettings — fetch fresh settings and
      // hand them to the InputBar via __applyImageSettings.
      if (typeof w.__applyImageSettings === 'function') {
        w.__applyImageSettings(JSON.stringify(state.imageSettings));
      }
    },
  };

  // ── Legacy flat namespace ──────────────────────────────────────────────────
  // Only the bridges Phase 5/6/7 components call directly. Spy form so
  // Playwright can assert which were invoked with what args.
  w._getContextUsage = async () => ({ ...state.contextUsage });
  w._getImageSettings = async () => ({ ...state.imageSettings });
  w._sendMessage = spy('_sendMessage');
  w._sendMessageWithMentions = spy('_sendMessageWithMentions');
  w._cancelTask = spy('_cancelTask');
  w._newChat = spy('_newChat');
  w._changeModel = spy('_changeModel');
  w._requestModelList = () => {
    state.calls.push({ method: '_requestModelList', args: [] });
    // Mimic Kotlin's push: invoke updateModelList with the last seeded list
    // so ModelChip rehydrates after a re-mount. Initial seed must come via
    // window.__harness.pushModelList.
    if (typeof w.updateModelList === 'function' && state.lastModelListJson) {
      w.updateModelList(state.lastModelListJson);
    }
  };
  w._togglePlanMode = spy('_togglePlanMode');
  w._compactContext = spy('_compactContext');
  w._navigateToFile = spy('_navigateToFile');
  w._requestFocusIde = spy('_requestFocusIde');
  w._submitPrompt = spy('_submitPrompt');
  w._approvePlan = spy('_approvePlan');
  w._revisePlan = spy('_revisePlan');
  w._dismissPlan = spy('_dismissPlan');
  w._toggleTool = spy('_toggleTool');
  w._activateSkill = spy('_activateSkill');
  w._deactivateSkill = spy('_deactivateSkill');
  w._openSettings = spy('_openSettings');
  w._requestUndo = spy('_requestUndo');
  w._requestViewTrace = spy('_requestViewTrace');
  w._approveToolCall = spy('_approveToolCall');
  w._denyToolCall = spy('_denyToolCall');
  w._allowToolForSession = spy('_allowToolForSession');
  w._reportInteractiveRender = spy('_reportInteractiveRender');

  // ── File/document attachment bridges (mock) ─────────────────────────────────
  // In production these are JVM-backed: _pickAttachment opens a native
  // FileChooser; the drop target pushes _setDropActive; both ultimately call
  // _addAttachmentChip (registered by InputBar's mount effect) with metadata.
  // The harness simulates a "pick" by pushing a synthetic file chip so the
  // menu→pick→chip flow is exercisable in a real browser.
  w._pickAttachment = () => {
    state.calls.push({ method: '_pickAttachment', args: [] });
    if (typeof w._addAttachmentChip === 'function') {
      w._addAttachmentChip({
        sha256: 'harness' + Date.now().toString(16),
        mime: 'application/pdf',
        size: 12345,
        originalFilename: 'harness-spec.pdf',
        kind: 'file',
        path: '/tmp/session/attachments/files/harness-spec.pdf',
      });
    }
  };
  w._setDropActive = (active: boolean) => {
    useChatStore.getState().setDropActive(!!active);
  };

  // ── Ticket (#) mention bridges (mock) ───────────────────────────────────────
  // Real impls hit Jira. Here: search returns nothing (dropdown opens but empty),
  // and validate always resolves "valid" via the per-key callback contract
  // (window[callbackKey](JSON.stringify({valid, summary}))).
  w._searchTickets = (query: string) => {
    state.calls.push({ method: '_searchTickets', args: [query] });
  };
  // @-mention search (mock): push-based via chatStore.receiveMentionResults.
  // Two of the files share a label ("alpha.ts") with different paths so tests
  // can exercise same-name-file mentions. Echoes the search term (sans prefix)
  // so MentionDropdown's staleness gate accepts the results.
  w._searchMentions = (query: string) => {
    state.calls.push({ method: '_searchMentions', args: [query] });
    const colon = query.indexOf(':');
    const searchTerm = colon >= 0 ? query.slice(colon + 1) : query;
    const items = [
      { type: 'file' as const, label: 'alpha.ts', path: 'src/a/alpha.ts', description: 'src/a' },
      { type: 'file' as const, label: 'alpha.ts', path: 'src/b/alpha.ts', description: 'src/b' },
      { type: 'file' as const, label: 'beta.ts', path: 'src/beta.ts', description: 'src' },
    ];
    useChatStore.getState().receiveMentionResults(searchTerm, items);
  };
  w._validateTicket = (ticketKey: string, callbackKey: string) => {
    state.calls.push({ method: '_validateTicket', args: [ticketKey, callbackKey] });
    const cb = (w as Record<string, unknown>)[callbackKey];
    if (typeof cb === 'function') {
      (cb as (json: string) => void)(JSON.stringify({ valid: true, summary: 'mock ticket' }));
    }
  };

  // ── Harness control surface ────────────────────────────────────────────────
  w.__harness = {
    setContextUsage(used: number, max: number) {
      state.contextUsage = { used, max };
    },
    setImageSettings(s: Partial<ImageSettings>) {
      state.imageSettings = { ...state.imageSettings, ...s };
    },
    async pushImageSettingsToWebview() {
      if (typeof w.__applyImageSettings === 'function') {
        w.__applyImageSettings(JSON.stringify(state.imageSettings));
      }
    },
    pushModelList(json: string) {
      state.lastModelListJson = json;
      if (typeof w.updateModelList === 'function') {
        w.updateModelList(json);
      }
    },
    getCalls: () => [...state.calls],
    clearCalls: () => {
      state.calls.length = 0;
    },
  };
}

