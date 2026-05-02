import { memo, useState, useCallback, useEffect, useRef, useMemo } from 'react';
import { Plus, ArrowUp, Square, ChevronDown, Sparkles, Brain, ListChecks, File, Folder, Hash, SquareKanban, Zap, Image as ImageIcon } from 'lucide-react';
import { useChatStore } from '@/stores/chatStore';
import type { Mention, MentionSearchResult } from '@/bridge/types';
import {
  PromptInputActions,
} from '@/components/ui/prompt-kit/prompt-input';
import { RichInput, type RichInputHandle } from './RichInput';
import { Button } from '@/components/ui/button';
import {
  DropdownMenu,
  DropdownMenuTrigger,
  DropdownMenuContent,
  DropdownMenuItem,
} from '@/components/ui/dropdown-menu';
import { MentionDropdown, relevanceScore } from './MentionDropdown';
import { SkillDropdown } from './SkillDropdown';
import { TicketDropdown } from './TicketDropdown';
import { useDropdownKeyboard } from '@/hooks/useDropdownKeyboard';
import { AttachmentManager, type PendingAttachment } from './AttachmentManager';
import { ChipPreview } from './ChipPreview';
import { UsageIndicator } from './UsageIndicator';

// Phase 5: image-attachment defaults. These mirror the Kotlin
// PluginSettings.State defaults; eventually a settings bridge will push live
// values, but the user's settings UI only changes them rarely so the static
// defaults are correct for the first cut. Kotlin runs the same checks
// server-side, so user customizations still take effect end-to-end.
const IMAGE_DEFAULT_SETTINGS = {
  maxBytes: 5_242_880,
  mimeWhitelist: ['image/png', 'image/jpeg', 'image/webp', 'image/heic', 'image/heif'],
  maxPerTurn: 2,
  enabled: true,
};

// Phase 6 (F-P5-3): the model-list payload pushed by Kotlin's `updateModelList`
// includes a `vision: boolean` field per model. We cache the most-recent
// payload in a module-scope singleton so both `<ModelChip>` (which renders
// the dropdown) and `<InputBar>`'s Send handler (which gates image-bearing
// turns at Send) can read the same source of truth without lifting state.
let cachedModelItems: DropdownItem[] = [];
function rememberModels(items: DropdownItem[]): void {
  cachedModelItems = items;
}
function modelByName(name: string): DropdownItem | undefined {
  return cachedModelItems.find(m => m.name === name);
}

// ── Types ──

/**
 * Multimodal-agent Phase 7 — extends the original 5-field dropdown payload with
 * `contextWindow` (per-model capacity), `capabilities` (icon strip source), and
 * `status` (drives the deprecated badge). All new fields are optional so older
 * Kotlin builds (or stale cached payloads) still render without crashing —
 * `<ModelPickerRow>` defends against undefined for each.
 */
interface DropdownItem {
  id: string;
  name: string;
  provider?: string;
  thinking?: boolean;
  vision?: boolean;
  description?: string;
  // Phase 7 enrichments — optional so legacy payloads still render.
  contextWindow?: { maxInputTokens: number; maxUserInputTokens?: number };
  capabilities?: string[];
  status?: 'experimental' | 'beta' | 'stable' | 'deprecated' | string;
}

/**
 * Multimodal-agent Phase 7 — single picker row with capacity strip + capability
 * badges (👁 vision · 🔧 tools · 🧠 reasoning · ⚠ deprecated). Exported so the
 * vitest suite can render it without going through Radix's portal-mounted
 * `<DropdownMenuContent>` (which jsdom doesn't fully support).
 */
export function ModelPickerRow({ model }: { model: DropdownItem }) {
  const cap = model.contextWindow;
  const caps = model.capabilities ?? [];
  const isDeprecated = model.status === 'deprecated';
  return (
    <div className="flex flex-col flex-1 min-w-0">
      <span className="text-[12px]">{model.name}</span>
      {model.provider && <span className="text-[10px] capitalize" style={{ color: 'var(--fg-muted)' }}>{model.provider}</span>}
      {cap && (
        <span className="text-[10px]" style={{ color: 'var(--fg-muted)' }}>
          {Math.round(cap.maxInputTokens / 1000)}K context
          {typeof cap.maxUserInputTokens === 'number'
            ? ` · ${Math.round(cap.maxUserInputTokens / 1000)}K per-message`
            : ''}
        </span>
      )}
      {(caps.length > 0 || isDeprecated) && (
        <span className="text-[10px] flex items-center gap-1 mt-0.5" style={{ color: 'var(--fg-muted)' }}>
          {caps.includes('vision') && <span title="vision" aria-label="vision">{'\u{1F441}'}</span>}
          {caps.includes('tools') && <span title="tools" aria-label="tools">{'\u{1F527}'}</span>}
          {caps.includes('reasoning') && <span title="reasoning" aria-label="reasoning">{'\u{1F9E0}'}</span>}
          {isDeprecated && <span title="deprecated" aria-label="deprecated" style={{ color: 'var(--warning, #d97706)' }}>{'⚠'}</span>}
        </span>
      )}
    </div>
  );
}

// ── Provider logos (inline SVG, 14×14) ──

const ProviderLogo = memo(function ProviderLogo({ provider, size = 14 }: { provider?: string; size?: number }) {
  const s = `${size}`;
  switch (provider?.toLowerCase()) {
    case 'anthropic':
      // Source: Simple Icons (CC0) — https://simpleicons.org/?q=anthropic
      return (
        <svg width={s} height={s} viewBox="0 0 24 24" fill="currentColor" role="img">
          <path d="M17.3041 3.541h-3.6718l6.696 16.918H24Zm-10.6082 0L0 20.459h3.7442l1.3693-3.5527h7.0052l1.3693 3.5528h3.7442L10.5363 3.5409Zm-.3712 10.2232 2.2914-5.9456 2.2914 5.9456Z" />
        </svg>
      );
    case 'openai':
      // Source: Simple Icons (CC0) — https://simpleicons.org/?q=openai
      return (
        <svg width={s} height={s} viewBox="0 0 24 24" fill="currentColor" role="img">
          <path d="M22.2819 9.8211a5.9847 5.9847 0 0 0-.5157-4.9108 6.0462 6.0462 0 0 0-6.5098-2.9A6.0651 6.0651 0 0 0 4.9807 4.1818a5.9847 5.9847 0 0 0-3.9977 2.9 6.0462 6.0462 0 0 0 .7427 7.0966 5.98 5.98 0 0 0 .511 4.9107 6.051 6.051 0 0 0 6.5146 2.9001A5.9847 5.9847 0 0 0 13.2599 24a6.0557 6.0557 0 0 0 5.7718-4.2058 5.9894 5.9894 0 0 0 3.9977-2.9001 6.0557 6.0557 0 0 0-.7475-7.0729zm-9.022 12.6081a4.4755 4.4755 0 0 1-2.8764-1.0408l.1419-.0804 4.7783-2.7582a.7948.7948 0 0 0 .3927-.6813v-6.7369l2.02 1.1686a.071.071 0 0 1 .038.052v5.5826a4.504 4.504 0 0 1-4.4945 4.4944zm-9.6607-4.1254a4.4708 4.4708 0 0 1-.5346-3.0137l.142.0852 4.783 2.7582a.7712.7712 0 0 0 .7806 0l5.8428-3.3685v2.3324a.0804.0804 0 0 1-.0332.0615L9.74 19.9502a4.4992 4.4992 0 0 1-6.1408-1.6464zM2.3408 7.8956a4.485 4.485 0 0 1 2.3655-1.9728V11.6a.7664.7664 0 0 0 .3879.6765l5.8144 3.3543-2.0201 1.1685a.0757.0757 0 0 1-.071 0l-4.8303-2.7865A4.504 4.504 0 0 1 2.3408 7.872zm16.5963 3.8558L13.1038 8.364 15.1192 7.2a.0757.0757 0 0 1 .071 0l4.8303 2.7913a4.4944 4.4944 0 0 1-.6765 8.1042v-5.6772a.79.79 0 0 0-.407-.667zm2.0107-3.0231-.142-.0852-4.7735-2.7818a.7759.7759 0 0 0-.7854 0L9.409 9.2297V6.8974a.0662.0662 0 0 1 .0284-.0615l4.8303-2.7866a4.4992 4.4992 0 0 1 6.6802 4.66zM8.3065 12.863l-2.02-1.1638a.0804.0804 0 0 1-.038-.0567V6.0742a4.4992 4.4992 0 0 1 7.3757-3.4537l-.142.0805L8.704 5.459a.7948.7948 0 0 0-.3927.6813zm1.0976-2.3654l2.602-1.4998 2.6069 1.4998v2.9994l-2.5974 1.4997-2.6067-1.4997Z" />
        </svg>
      );
    case 'google':
      // Source: Simple Icons (CC0) — https://simpleicons.org/?q=google
      return (
        <svg width={s} height={s} viewBox="0 0 24 24" fill="currentColor" role="img">
          <path d="M12.48 10.92v3.28h7.84c-.24 1.84-.853 3.187-1.787 4.133-1.147 1.147-2.933 2.4-6.053 2.4-4.827 0-8.6-3.893-8.6-8.72s3.773-8.72 8.6-8.72c2.6 0 4.507 1.027 5.907 2.347l2.307-2.307C18.747 1.44 16.133 0 12.48 0 5.867 0 .307 5.387.307 12s5.56 12 12.173 12c3.573 0 6.267-1.173 8.373-3.36 2.16-2.16 2.84-5.213 2.84-7.667 0-.76-.053-1.467-.173-2.053H12.48z" />
        </svg>
      );
    default:
      return <span className="h-[14px] w-[14px] rounded-full shrink-0" style={{ backgroundColor: 'var(--accent)' }} />;
  }
});

// ── ModelChip ──

const ModelChip = memo(function ModelChip({
  model,
  fallbackReason,
}: {
  model: string;
  /** Non-null when the active model is the result of an automatic fallback. The string is the human-readable reason shown as the chip's tooltip. null = primary model, no indicator. */
  fallbackReason: string | null;
}) {
  const [items, setItems] = useState<DropdownItem[]>([]);
  const activeItem = useMemo(() => items.find(m => m.name === model), [items, model]);

  useEffect(() => {
    (window as any).updateModelList = (json: string) => {
      try {
        const parsed = JSON.parse(json) as DropdownItem[];
        setItems(parsed);
        // Phase 6 (F-P5-3): mirror to module-scope cache so the Send handler
        // can read the active model's vision capability without lifting state.
        rememberModels(parsed);
      } catch { /* ignore */ }
    };
    // Pull on mount: if the initial Kotlin push was lost (timing) or returned empty
    // (network/auth failure at startup), this recovers the dropdown without an IDE restart.
    window._requestModelList?.();
  }, []);

  // Re-pull when the user opens the dropdown and the list is still empty.
  const handleOpenChange = useCallback((open: boolean) => {
    if (open && items.length === 0) window._requestModelList?.();
  }, [items.length]);

  const isFallback = fallbackReason !== null;
  // Subtle amber indicator when in fallback mode: border tint + Zap icon + tooltip.
  // Uses existing CSS variables so it adapts to light/dark themes.
  const fallbackBorderStyle = isFallback
    ? {
        borderColor: 'var(--warning, #d97706)',
        borderWidth: '1px',
        borderStyle: 'solid' as const,
      }
    : undefined;
  const tooltipText = isFallback
    ? (fallbackReason || 'Automatic fallback model active')
    : undefined;

  return (
    <DropdownMenu onOpenChange={handleOpenChange}>
      <DropdownMenuTrigger asChild>
        <Button
          variant="ghost"
          size="sm"
          className="h-7 gap-1 px-1.5 text-[12px] font-medium whitespace-nowrap"
          style={fallbackBorderStyle}
          title={tooltipText}
        >
          <ProviderLogo provider={activeItem?.provider} size={12} />
          <span>{model || 'Model'}</span>
          {activeItem?.thinking && <Brain className="h-3 w-3 shrink-0" style={{ color: 'var(--accent, #60a5fa)' }} />}
          {isFallback && (
            <Zap
              className="h-3 w-3 shrink-0"
              style={{ color: 'var(--warning, #d97706)' }}
              aria-label="Fallback model"
            />
          )}
          <ChevronDown className="h-2.5 w-2.5" />
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="start" className="min-w-[220px]">
        {items.length === 0
          ? <div className="px-3 py-2 text-[11px]" style={{ color: 'var(--fg-muted)' }}>No models available</div>
          : items.map(m => (
              <DropdownMenuItem key={m.id} onClick={() => window._changeModel?.(m.id)}
                className="gap-2"
                style={m.name === model ? { backgroundColor: 'var(--hover-overlay-strong, rgba(255,255,255,0.08))' } : undefined}>
                <ProviderLogo provider={m.provider} />
                <ModelPickerRow model={m} />
                {m.thinking && <Brain className="h-3.5 w-3.5 shrink-0 ml-auto" style={{ color: 'var(--accent, #60a5fa)' }} />}
              </DropdownMenuItem>
            ))
        }
      </DropdownMenuContent>
    </DropdownMenu>
  );
});

// ── PlanChip ──

const PlanChip = memo(function PlanChip({ active }: { active: boolean }) {
  const togglePlan = useCallback(() => {
    // Toggle locally for immediate feedback, then notify Kotlin
    const store = useChatStore.getState();
    const newMode = active ? 'agent' : 'plan';
    store.setInputMode?.(newMode as any);
    window._togglePlanMode?.(!active);
  }, [active]);

  return (
    <Button
      variant="ghost"
      size="sm"
      className="h-7 gap-1 px-1.5 text-[12px] font-medium"
      style={{
        color: active ? 'var(--accent, #60a5fa)' : undefined,
        backgroundColor: active ? 'var(--hover-overlay-strong, rgba(255,255,255,0.05))' : undefined,
      }}
      onClick={togglePlan}
      title={active ? 'Disable plan mode' : 'Enable plan mode'}
    >
      <ListChecks className="h-3 w-3" />
      Plan
    </Button>
  );
});

// ── RalphChip ──

const RalphChip = memo(function RalphChip({ active }: { active: boolean }) {
  const toggleRalph = useCallback(() => {
    const store = useChatStore.getState();
    store.setRalphLoop(!active);
    window._toggleRalphLoop?.(!active);
  }, [active]);

  return (
    <Button
      variant="ghost"
      size="sm"
      className="h-7 gap-1 px-1.5 text-[12px] font-medium"
      style={{
        color: active ? 'var(--success, #4ade80)' : undefined,
        backgroundColor: active ? 'var(--hover-overlay-strong, rgba(255,255,255,0.05))' : undefined,
      }}
      onClick={toggleRalph}
      title={active ? 'Disable Ralph Loop (iterative self-improvement)' : 'Enable Ralph Loop — agent iterates until reviewer accepts'}
    >
      <svg className="h-3 w-3" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5">
        <path d="M8 2v4l3 2M8 14a6 6 0 110-12 6 6 0 010 12z" strokeLinecap="round" strokeLinejoin="round"/>
      </svg>
      Ralph
    </Button>
  );
});

// ── SkillsChip ──

const SkillsChip = memo(function SkillsChip() {
  // Read skills from chatStore (populated by jcef-bridge.ts updateSkillsList)
  // DO NOT override window.updateSkillsList here — that would break the
  // bridge function which updates chatStore.skillsList for SkillDropdown
  const items = useChatStore(s => s.skillsList);

  if (items.length === 0) return null;

  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button variant="ghost" size="sm" className="h-7 gap-1 px-1.5 text-[12px] font-medium">
          <Sparkles className="h-2.5 w-2.5" />
          Skills
          <ChevronDown className="h-2.5 w-2.5" />
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="start" className="min-w-[240px]">
        {items.map(s => (
          <DropdownMenuItem key={s.name} onClick={() => window._activateSkill?.(s.name)}>
            <div className="flex flex-col">
              <span className="text-[12px]">/{s.name}</span>
              {s.description && <span className="text-[11px]" style={{ color: 'var(--fg-muted)' }}>{s.description}</span>}
            </div>
          </DropdownMenuItem>
        ))}
      </DropdownMenuContent>
    </DropdownMenu>
  );
});

// ── MoreChip ──

const MoreChip = memo(function MoreChip() {
  const actions = [
    { label: 'New conversation', action: () => window._newChat?.() },
    { label: 'Undo last action', action: () => window._requestUndo?.() },
    { label: 'View traces', action: () => window._requestViewTrace?.() },
    { label: 'Settings', action: () => window._openSettings?.() },
  ];

  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button variant="ghost" size="sm" className="h-7 px-1.5 text-[12px] font-medium">
          <span className="text-[15px] leading-none tracking-[2px]">&middot;&middot;&middot;</span>
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="start" className="min-w-[180px]">
        {actions.map(item => (
          <DropdownMenuItem key={item.label} onClick={item.action}>
            {item.label}
          </DropdownMenuItem>
        ))}
      </DropdownMenuContent>
    </DropdownMenu>
  );
});

// ── InputBarContent (inside PromptInput context) ──

interface InputBarContentProps {
  showMentions: boolean;
  showSkills: boolean;
  showTickets: boolean;
  mentionQuery: string;
  skillQuery: string;
  ticketQuery: string;
  busy: boolean;
  steeringMode: boolean;
  locked: boolean;
  planActive: boolean;
  ralphActive: boolean;
  model: string;
  modelFallbackReason: string | null;
  richInputRef: React.RefObject<RichInputHandle>;
  onMentionSelect: (result: MentionSearchResult) => void;
  onSkillSelect: (skillName: string) => void;
  onTicketSelect: (result: MentionSearchResult) => void;
  onDismissMentions: () => void;
  onSend: () => void;
  onStop: () => void;
  onTriggerInsert: (char: '@' | '/' | '#') => void;
  onRichInputChange: (text: string, trigger: { type: '@' | '#' | '/'; query: string } | null) => void;
  onPastedTickets: (ticketKeys: string[]) => void;
  canSend: boolean;
  // Keyboard navigation props (passed down from InputBar which owns the hook)
  dropdownKeyDown: (e: React.KeyboardEvent) => boolean;
  mentionSelectedIndex: number;
  setMentionSelectedIndex: (i: number) => void;
  mentionListRef: React.RefObject<HTMLDivElement>;
  ticketSelectedIndex: number;
  setTicketSelectedIndex: (i: number) => void;
  ticketListRef: React.RefObject<HTMLDivElement>;
  onTicketResultsChange: (results: MentionSearchResult[]) => void;
  skillSelectedIndex: number;
  setSkillSelectedIndex: (i: number) => void;
  skillListRef: React.RefObject<HTMLDivElement>;
  // Phase 5: image-attachment props (only those InputBarContent itself uses)
  onPickImage: () => void;
  onPasteImage: (file: File) => Promise<boolean>;
}

function InputBarContent({
  showMentions,
  showSkills,
  showTickets,
  mentionQuery,
  skillQuery,
  ticketQuery,
  busy,
  steeringMode,
  locked,
  planActive,
  ralphActive,
  model,
  modelFallbackReason,
  richInputRef,
  onMentionSelect,
  onSkillSelect,
  onTicketSelect,
  onDismissMentions,
  onSend,
  onStop,
  onTriggerInsert,
  onRichInputChange,
  onPastedTickets,
  canSend,
  dropdownKeyDown,
  mentionSelectedIndex,
  setMentionSelectedIndex,
  mentionListRef,
  ticketSelectedIndex,
  setTicketSelectedIndex,
  ticketListRef,
  onTicketResultsChange,
  skillSelectedIndex,
  setSkillSelectedIndex,
  skillListRef,
  onPickImage,
  onPasteImage,
}: InputBarContentProps) {
  // Focus on trigger from store
  const focusTrigger = useChatStore(s => s.focusInputTrigger);
  // Manual compaction in progress — disable input + send so the user can't
  // mutate state during the LLM-summary round-trip.
  const compacting = useChatStore(s => s.compactionState.active);
  useEffect(() => {
    if (focusTrigger > 0) richInputRef.current?.focus();
  }, [focusTrigger, richInputRef]);

  return (
    <>
      {/* Mention dropdown (@ files/folders/symbols) — floats above */}
      {showMentions && (
        <MentionDropdown
          query={mentionQuery}
          onSelect={onMentionSelect}
          onDismiss={onDismissMentions}
          selectedIndex={mentionSelectedIndex}
          setSelectedIndex={setMentionSelectedIndex}
          listRef={mentionListRef}
        />
      )}

      {/* Skill dropdown (/ skills) — floats above */}
      {showSkills && (
        <SkillDropdown
          query={skillQuery}
          onSelect={onSkillSelect}
          onDismiss={onDismissMentions}
          selectedIndex={skillSelectedIndex}
          setSelectedIndex={setSkillSelectedIndex}
          listRef={skillListRef}
        />
      )}

      {/* Ticket dropdown (# tickets) — floats above */}
      {showTickets && (
        <TicketDropdown
          query={ticketQuery}
          onSelect={onTicketSelect}
          onDismiss={onDismissMentions}
          selectedIndex={ticketSelectedIndex}
          setSelectedIndex={setTicketSelectedIndex}
          listRef={ticketListRef}
          onResultsChange={onTicketResultsChange}
        />
      )}

      {/* Steering mode indicator */}
      {busy && steeringMode && (
        <div
          className="text-xs px-3 pt-2 pb-0 flex items-center gap-1.5"
          style={{ color: 'var(--fg-muted)' }}
        >
          <span style={{ color: 'var(--accent-blue, #60a5fa)', fontSize: '8px' }}>&#9650;</span>
          <span>Type to steer — message arrives after current step</span>
        </div>
      )}

      {/* Rich contenteditable input — chips render inline with text */}
      <div className="px-3 pt-2 pb-1">
        <RichInput
          ref={richInputRef}
          placeholder={
            compacting ? 'Compacting context...' :
            busy && steeringMode ? 'Steer the agent...' :
            'Ask anything... (@ context, # ticket, / skill)'
          }
          disabled={compacting || locked || (busy && !steeringMode)}
          onSubmit={onSend}
          onChange={onRichInputChange}
          onEscape={onDismissMentions}
          onDropdownKeyDown={dropdownKeyDown}
          onPastedTickets={onPastedTickets}
          onPasteImage={onPasteImage}
        />
      </div>

      {/* Action row */}
      <PromptInputActions className="justify-between px-2 pb-2 pt-0.5">
        {/* Left: + · Model · Plan · ··· */}
        <div className="flex items-center gap-0.5">
          {/* + button: picker for File, Folder, Symbol, Skill */}
          <DropdownMenu>
            <DropdownMenuTrigger asChild>
              <Button variant="ghost" size="icon" className="h-7 w-7" title="Add context or skill" aria-label="Add context or skill">
                <Plus className="h-3.5 w-3.5" />
              </Button>
            </DropdownMenuTrigger>
            <DropdownMenuContent align="start" side="top" className="min-w-[180px]">
              <DropdownMenuItem onClick={() => onTriggerInsert('@')}>
                <File className="size-3.5" style={{ color: 'var(--accent-read, #3b82f6)' }} />
                <span>File</span>
                <span className="ml-auto text-[10px]" style={{ color: 'var(--fg-muted)' }}>@</span>
              </DropdownMenuItem>
              <DropdownMenuItem onClick={() => onTriggerInsert('@')}>
                <Folder className="size-3.5" style={{ color: 'var(--accent-read, #3b82f6)' }} />
                <span>Folder</span>
                <span className="ml-auto text-[10px]" style={{ color: 'var(--fg-muted)' }}>@</span>
              </DropdownMenuItem>
              <DropdownMenuItem onClick={() => onTriggerInsert('@')}>
                <Hash className="size-3.5" style={{ color: 'var(--accent-search, #a78bfa)' }} />
                <span>Symbol</span>
                <span className="ml-auto text-[10px]" style={{ color: 'var(--fg-muted)' }}>@</span>
              </DropdownMenuItem>
              <DropdownMenuItem onClick={() => onTriggerInsert('#')}>
                <SquareKanban className="size-3.5" style={{ color: 'var(--accent-read, #3b82f6)' }} />
                <span>Ticket</span>
                <span className="ml-auto text-[10px]" style={{ color: 'var(--fg-muted)' }}>#</span>
              </DropdownMenuItem>
              <DropdownMenuItem onClick={() => onTriggerInsert('/')}>
                <Sparkles className="size-3.5" style={{ color: 'var(--accent-edit, #f59e0b)' }} />
                <span>Skill</span>
                <span className="ml-auto text-[10px]" style={{ color: 'var(--fg-muted)' }}>/</span>
              </DropdownMenuItem>
              {/* Phase 5: image attachment via file picker. Paste + drag-drop
                  are wired separately (RichInput.handlePaste + the wrapping
                  div's onDrop). */}
              <DropdownMenuItem onClick={onPickImage}>
                <ImageIcon className="size-3.5" style={{ color: 'var(--accent-edit, #f59e0b)' }} />
                <span>Image</span>
                <span className="ml-auto text-[10px]" style={{ color: 'var(--fg-muted)' }}>file</span>
              </DropdownMenuItem>
            </DropdownMenuContent>
          </DropdownMenu>

          <ModelChip model={model} fallbackReason={modelFallbackReason} />
          <PlanChip active={planActive} />
          <RalphChip active={ralphActive} />
          <SkillsChip />
          <MoreChip />
        </div>

        {/* Right: Stop · Send */}
        <div className="flex items-center gap-1.5">
          {busy && (
            <Button
              variant="destructive"
              size="icon"
              className="h-7 w-7 rounded-full"
              onClick={onStop}
              title="Stop"
              aria-label="Stop"
            >
              <Square className="h-2.5 w-2.5" fill="currentColor" />
            </Button>
          )}

          <Button
            size="icon"
            className="h-7 w-7 rounded-full"
            onClick={onSend}
            disabled={!canSend}
            title="Send (Enter)"
            aria-label="Send (Enter)"
            style={{
              backgroundColor: canSend ? 'var(--fg)' : undefined,
              color: canSend ? 'var(--bg)' : undefined,
            }}
          >
            <ArrowUp className="h-3.5 w-3.5" strokeWidth={2.5} />
          </Button>
        </div>
      </PromptInputActions>
    </>
  );
}

// ── InputBar ──

export const InputBar = memo(function InputBar() {
  const inputState = useChatStore(s => s.inputState);
  const busy = useChatStore(s => s.busy);
  const outerCompacting = useChatStore(s => s.compactionState.active);
  const richInputRef = useRef<RichInputHandle>(null);
  const fileInputRef = useRef<HTMLInputElement>(null);

  const [hasText, setHasText] = useState(false);
  const [showMentions, setShowMentions] = useState(false);
  const [showSkills, setShowSkills] = useState(false);
  const [showTickets, setShowTickets] = useState(false);
  const [mentionQuery, setMentionQuery] = useState('');
  const [skillQuery, setSkillQuery] = useState('');
  const [ticketQuery, setTicketQuery] = useState('');
  const [attachments, setAttachments] = useState<PendingAttachment[]>([]);

  // Phase 5/v1.1: oversize-image confirmation modal state. The
  // AttachmentManager calls `confirmCompress(...)` when a file exceeds the
  // configured cap; that promise resolves when the user clicks Compress (true)
  // or Cancel (false). Cancel aborts the entire attach — no upload, no chip.
  const [compressPrompt, setCompressPrompt] = useState<{
    originalKB: number;
    capKB: number;
    filename: string;
    resolve: (proceed: boolean) => void;
  } | null>(null);

  // Phase 5: AttachmentManager owns the pending image list. We construct it
  // once per InputBar mount; the manager's `onChange` flips local state so
  // ChipPreview re-renders. `toast` flows into the existing chatStore toast
  // surface so attach/upload errors share visual treatment with everything
  // else.
  const attachmentManagerRef = useRef<AttachmentManager | null>(null);
  if (attachmentManagerRef.current === null) {
    attachmentManagerRef.current = new AttachmentManager(
      IMAGE_DEFAULT_SETTINGS,
      () => setAttachments(attachmentManagerRef.current!.list()),
      (msg, type = 'info') => {
        const durationMs = type === 'error' ? 6000 : 3000;
        useChatStore.getState().showToast(msg, type, durationMs);
      },
      (originalKB, capKB, filename) =>
        new Promise<boolean>(resolve => {
          setCompressPrompt({ originalKB, capKB, filename, resolve });
        }),
    );
  }

  // Phase 7 followup F-P5-2 / F-P6-1 — wire the global hook the bridge uses
  // to push fresh PluginSettings into the AttachmentManager singleton when the
  // user clicks Apply on the Settings dialog. Mounted once per InputBar
  // (component re-mounts only on full page reload).
  useEffect(() => {
    (window as any).__applyImageSettings = (json: string) => {
      try {
        const parsed = JSON.parse(json);
        attachmentManagerRef.current?.updateSettings({
          maxBytes: typeof parsed.maxBytes === 'number' ? parsed.maxBytes : IMAGE_DEFAULT_SETTINGS.maxBytes,
          mimeWhitelist: Array.isArray(parsed.mimeWhitelist) ? parsed.mimeWhitelist : IMAGE_DEFAULT_SETTINGS.mimeWhitelist,
          maxPerTurn: typeof parsed.maxPerTurn === 'number' ? parsed.maxPerTurn : IMAGE_DEFAULT_SETTINGS.maxPerTurn,
          enabled: typeof parsed.enabled === 'boolean' ? parsed.enabled : IMAGE_DEFAULT_SETTINGS.enabled,
        });
      } catch (e) {
        console.warn('[multimodal] __applyImageSettings: malformed JSON', e);
      }
    };
    // Trigger an initial pull on mount in case Kotlin already has fresher
    // values than the static defaults the manager was constructed with.
    (window as any).workflowAgent?.refreshImageSettings?.();
    return () => { delete (window as any).__applyImageSettings; };
  }, []);

  const handleAttachFile = useCallback(async (file: File | null | undefined) => {
    if (!file || !attachmentManagerRef.current) return;
    await attachmentManagerRef.current.attachFile(file);
  }, []);

  const handleRemoveAttachment = useCallback((sha256: string) => {
    attachmentManagerRef.current?.remove(sha256);
  }, []);

  const handlePickImage = useCallback(() => {
    fileInputRef.current?.click();
  }, []);

  const handleFilePicked = useCallback(async (e: React.ChangeEvent<HTMLInputElement>) => {
    const file = e.target.files?.[0];
    await handleAttachFile(file);
    // Reset value so the same file can be picked again later.
    e.target.value = '';
  }, [handleAttachFile]);

  const handleDrop = useCallback(async (e: React.DragEvent) => {
    if (!e.dataTransfer) return;
    // Only intercept image drops — let other drops (e.g. text) fall through to
    // the contenteditable's native handler.
    const files = Array.from(e.dataTransfer.files ?? []).filter(f =>
      f.type.startsWith('image/'),
    );
    if (files.length === 0) return;
    e.preventDefault();
    for (const file of files) {
      await handleAttachFile(file);
    }
  }, [handleAttachFile]);

  const handleDragOver = useCallback((e: React.DragEvent) => {
    if (!e.dataTransfer) return;
    const hasImage = Array.from(e.dataTransfer.items ?? []).some(item =>
      item.kind === 'file' && item.type.startsWith('image/'),
    );
    if (hasImage) e.preventDefault();
  }, []);

  const handlePasteImage = useCallback(async (file: File): Promise<boolean> => {
    if (!attachmentManagerRef.current) return false;
    const result = await attachmentManagerRef.current.attachFile(file);
    return result !== null;
  }, []);

  // ── Flat item lists for keyboard navigation ──
  // MentionDropdown manages its own filtered list internally, so we keep a
  // parallel copy here for the hook. Ticket and Skill dropdowns expose items
  // via state/store respectively.
  const mentionResults = useChatStore(s => s.mentionResults);
  const skillsList = useChatStore(s => s.skillsList);

  // Flat mention items for keyboard navigation. Must mirror MentionDropdown's
  // visible list (same score/filter/sort) so arrow-key indices line up with what
  // the user sees — otherwise selection picks a different item than highlighted.
  const flatMentionItems = useMemo(() => {
    const maxPerGroup = mentionQuery ? 5 : 8;
    const scored = mentionResults
      .filter(r => r.type === 'file' || r.type === 'folder' || r.type === 'symbol')
      .map(r => ({ ...r, score: relevanceScore(r.label, r.path, mentionQuery) }))
      .filter(r => !mentionQuery || r.score > 0);
    const grouped: Record<string, typeof scored> = {};
    for (const r of scored) { (grouped[r.type] ??= []).push(r); }
    for (const type in grouped) {
      grouped[type] = grouped[type]!
        .sort((a, b) => b.score - a.score)
        .slice(0, maxPerGroup);
    }
    return (['file', 'folder', 'symbol'] as const).flatMap(t => grouped[t] ?? []);
  }, [mentionResults, mentionQuery]);

  // Flat skill items (same order as SkillDropdown renders)
  const flatSkillItems = useMemo(() => {
    if (!mentionQuery && !skillQuery) return skillsList;
    return skillQuery
      ? skillsList.filter(s =>
          s.name.toLowerCase().includes(skillQuery.toLowerCase()) ||
          s.description?.toLowerCase().includes(skillQuery.toLowerCase())
        )
      : skillsList;
  }, [skillsList, skillQuery, mentionQuery]);

  // Ticket items live inside TicketDropdown state — we sync via a ref
  const [ticketItems, setTicketItems] = useState<MentionSearchResult[]>([]);

  // ── Keyboard navigation hooks (one per dropdown) ──

  const handleDismiss = useCallback(() => {
    setShowMentions(false); setShowSkills(false); setShowTickets(false);
    setMentionQuery(''); setSkillQuery(''); setTicketQuery('');
  }, []);

  const mentionKbd = useDropdownKeyboard({
    items: flatMentionItems,
    onSelect: (result) => handleMentionSelect(result),
    onDismiss: handleDismiss,
    isOpen: showMentions,
  });

  const skillKbd = useDropdownKeyboard({
    items: flatSkillItems.map(s => s.name),
    onSelect: (skillName) => handleSkillSelect(skillName),
    onDismiss: handleDismiss,
    isOpen: showSkills,
  });

  const ticketKbd = useDropdownKeyboard({
    items: ticketItems,
    onSelect: (result) => handleTicketSelect(result),
    onDismiss: handleDismiss,
    isOpen: showTickets,
  });

  // Unified handler forwarded from RichInput keydown.
  // Delegates to whichever dropdown is open.
  const dropdownKeyDown = useCallback((e: React.KeyboardEvent): boolean => {
    if (showMentions) return mentionKbd.handleKeyDown(e);
    if (showSkills)   return skillKbd.handleKeyDown(e);
    if (showTickets)  return ticketKbd.handleKeyDown(e);
    return false;
  }, [showMentions, showSkills, showTickets, mentionKbd, skillKbd, ticketKbd]);

  const prevTicketQueryRef = useRef('');

  // RichInput change handler — detects @, #, / triggers
  const handleRichInputChange = useCallback((_text: string, trigger: { type: '@' | '#' | '/'; query: string } | null) => {
    setHasText(_text.length > 0);
    if (trigger) {
      if (trigger.type === '@') {
        setShowMentions(true); setShowSkills(false); setShowTickets(false);
        setMentionQuery(trigger.query);
      } else if (trigger.type === '#') {
        setShowTickets(true); setShowMentions(false); setShowSkills(false);
        setTicketQuery(trigger.query);
        prevTicketQueryRef.current = trigger.query;
      } else {
        setShowSkills(true); setShowMentions(false); setShowTickets(false);
        setSkillQuery(trigger.query);
      }
    } else {
      // If # trigger just ended (user pressed space) and the query looks like a ticket key,
      // auto-create a pending chip and validate it asynchronously.
      // IMPORTANT: Clear prevTicketQueryRef BEFORE insertChip because insertChip calls
      // fireChange() internally, which re-invokes this callback recursively. If we don't
      // clear first, the recursive call sees the stale query and calls validateTicket a
      // second time, creating an orphaned timeout that removes the chip after 5s.
      const prevQuery = prevTicketQueryRef.current;
      prevTicketQueryRef.current = '';
      if (prevQuery && /^[A-Za-z][A-Za-z0-9]+-\d+$/.test(prevQuery)) {
        const ticketKey = prevQuery.toUpperCase();
        const mention: Mention = { type: 'ticket', label: ticketKey, path: ticketKey };
        richInputRef.current?.insertChip(mention, '#', 'pending');

        // Async validation via Kotlin bridge
        validateTicket(ticketKey);
      }
      setShowMentions(false); setShowSkills(false); setShowTickets(false);
      setMentionQuery(''); setSkillQuery(''); setTicketQuery('');
    }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  // Validate a manually-typed ticket key asynchronously with 5s timeout.
  // On failure/timeout: strip the chip and leave raw #KEY text so LLM can self-fetch via tools.
  const validateTicket = useCallback((ticketKey: string) => {
    const callbackKey = `__validateTicket_${ticketKey}`;
    let resolved = false;
    console.log('[Mention] validateTicket: starting validation for', ticketKey);

    // Timeout: if no response in 5s, strip the chip
    const timeoutId = setTimeout(() => {
      if (resolved) return;
      resolved = true;
      delete (window as any)[callbackKey];
      console.warn('[Mention] validateTicket: timeout for', ticketKey, '— removing chip');
      richInputRef.current?.removeChipByLabel?.(ticketKey);
    }, 5000);

    (window as any)[callbackKey] = (json: string) => {
      if (resolved) return;
      resolved = true;
      clearTimeout(timeoutId);
      delete (window as any)[callbackKey];
      try {
        const result = JSON.parse(json);
        if (result.valid) {
          richInputRef.current?.updateChipStatus(ticketKey, 'valid', `${ticketKey}: ${result.summary}`);
        } else {
          // Invalid ticket — strip chip, leave raw text for LLM to handle
          richInputRef.current?.removeChipByLabel?.(ticketKey);
        }
      } catch {
        richInputRef.current?.removeChipByLabel?.(ticketKey);
      }
    };

    // Call Kotlin bridge to validate — if bridge not available, keep chip as pending
    // (the ticket key will still be sent as a mention; LLM can self-fetch via tools)
    if (window._validateTicket) {
      window._validateTicket(ticketKey, callbackKey);
    } else {
      resolved = true;
      clearTimeout(timeoutId);
      delete (window as any)[callbackKey];
      // Don't remove the chip — leave it pending so the user sees their ticket reference
    }
  }, []);

  // Handle ticket keys found in pasted text — validate each one
  const handlePastedTickets = useCallback((ticketKeys: string[]) => {
    for (const key of ticketKeys) {
      validateTicket(key);
    }
  }, [validateTicket]);

  const handleMentionSelect = useCallback((result: MentionSearchResult) => {
    const mention: Mention = { type: result.type, label: result.label, path: result.path, icon: result.icon };
    (richInputRef.current as any)?.insertChip?.(mention, '@');
    setShowMentions(false); setMentionQuery('');
  }, []);

  const handleTicketSelect = useCallback((result: MentionSearchResult) => {
    // Clear prevTicketQueryRef BEFORE insertChip — same pattern as the null-trigger path in
    // handleRichInputChange. insertChip fires fireChange() synchronously, which re-invokes
    // handleRichInputChange. If prevTicketQueryRef still holds the typed query, that recursive
    // call sees a full ticket key and launches a duplicate validateTicket that removes the
    // just-selected chip on validation failure or 5s timeout.
    prevTicketQueryRef.current = '';
    const mention: Mention = { type: 'ticket', label: result.label, path: result.path, icon: result.icon };
    console.log('[Mention] handleTicketSelect: inserting chip', result.label, 'path=', result.path);
    richInputRef.current?.insertChip(mention, '#', 'valid');
    setShowTickets(false); setTicketQuery('');
    setTicketItems([]);
  }, []);

  const handleSkillSelect = useCallback((skillName: string) => {
    const mention: Mention = { type: 'skill', label: skillName, path: skillName };
    richInputRef.current?.insertChip(mention, '/');
    setShowSkills(false); setSkillQuery('');
  }, []);

  const handleSend = useCallback(async () => {
    const ri = richInputRef.current;
    if (!ri) return;
    const text = ri.getText();
    const mentions = ri.getMentions();
    const pending = attachmentManagerRef.current?.list() ?? [];
    console.log('[Mention] handleSend: text=', JSON.stringify(text.slice(0, 80)), 'mentions=', JSON.stringify(mentions), 'attachments=', pending.length);
    if (!text.trim() && mentions.length === 0 && pending.length === 0) return;
    const state = useChatStore.getState();
    if (state.inputState.locked) return;
    if (state.busy && !state.steeringMode) return;
    // Phase 6 (F-P5-3): vision-disabled toast at Send (Decision 3 — option C).
    // If the user has attached images and the active model lacks vision
    // capability, refuse to send: chip stays in place; user can either remove
    // the chip or switch to a vision-capable model. Server-side will also
    // reject (the routing predicate strips images and the gateway returns a
    // confusing reply), but the toast is faster + clearer.
    if (pending.length > 0) {
      const activeModelName = state.inputState.model ?? '';
      const activeModel = modelByName(activeModelName);
      // `vision === undefined` (older model-list payloads or unknown model)
      // is treated as non-vision so we fail-closed; that matches the
      // catalog-not-loaded safety pattern Phase 6 uses on the Kotlin side.
      const hasVision = activeModel?.vision === true;
      if (!hasVision) {
        const displayName = activeModelName || 'this model';
        useChatStore.getState().showToast(
          `${displayName} doesn't support image input. Switch to a vision-capable model.`,
          'warning',
          6000,
        );
        return;
      }
    }
    // Phase 5: kick off the upload(s) before we clear UI state. uploadAll is
    // best-effort on errors — toasts surface failures and pending bytes stay
    // in the chip until explicitly removed. Phase 6 wires the resulting
    // sha256s into the message body via parts; Phase 5 keeps the surface UI
    // working without changing the wire shape, so chips clear after upload
    // and the existing send path runs unchanged.
    if (pending.length > 0 && attachmentManagerRef.current) {
      try {
        await attachmentManagerRef.current.uploadAll();
      } catch (e) {
        console.warn('[multimodal] uploadAll threw', e);
      }
    }
    useChatStore.getState().sendMessage(text.trim(), mentions);
    ri.clear();
    attachmentManagerRef.current?.clear();
    setHasText(false);
    setShowMentions(false); setShowSkills(false); setShowTickets(false);
  }, []);

  const handleStop = useCallback(() => { window._cancelTask?.(); }, []);

  const triggerInsert = useCallback((char: '@' | '/' | '#') => {
    richInputRef.current?.insertTrigger(char);
  }, []);

  // Expose a ticket results setter so TicketDropdown can sync items for keyboard nav
  // We pass this as a prop so TicketDropdown can call it after each search result
  const handleTicketResultsChange = useCallback((results: MentionSearchResult[]) => {
    setTicketItems(results);
  }, []);

  const steeringMode = useChatStore(s => s.steeringMode);

  // Restore text when a queued steering message is cancelled
  const restoredInputText = useChatStore(s => s.restoredInputText);
  useEffect(() => {
    if (restoredInputText) {
      richInputRef.current?.setText(restoredInputText);
      setHasText(true);
      useChatStore.getState().clearRestoredInputText();
      richInputRef.current?.focus();
    }
  }, [restoredInputText]);

  const canSend = hasText && !inputState.locked && !outerCompacting && (!busy || steeringMode);
  const planActive = inputState.mode === 'plan';
  const ralphActive = inputState.ralph ?? false;

  return (
    <div className="px-3 pb-3 pt-2">
      <div
        className="relative rounded-xl border p-2 cursor-text"
        style={{ backgroundColor: 'var(--input-bg)', borderColor: 'var(--input-border)' }}
        onClick={() => richInputRef.current?.focus()}
        onDrop={handleDrop}
        onDragOver={handleDragOver}
      >
        <ChipPreview attachments={attachments} onRemove={handleRemoveAttachment} />
        {/* Hidden picker — opened by the "Image" dropdown item */}
        <input
          ref={fileInputRef}
          type="file"
          accept="image/png,image/jpeg,image/webp,image/heic,image/heif"
          onChange={handleFilePicked}
          style={{ display: 'none' }}
          aria-hidden="true"
        />
        <InputBarContent
          showMentions={showMentions}
          showSkills={showSkills}
          showTickets={showTickets}
          mentionQuery={mentionQuery}
          skillQuery={skillQuery}
          ticketQuery={ticketQuery}
          busy={busy}
          steeringMode={steeringMode}
          locked={inputState.locked}
          planActive={planActive}
          ralphActive={ralphActive}
          model={inputState.model ?? ''}
          modelFallbackReason={inputState.modelFallbackReason ?? null}
          richInputRef={richInputRef}
          onMentionSelect={handleMentionSelect}
          onSkillSelect={handleSkillSelect}
          onTicketSelect={handleTicketSelect}
          onDismissMentions={handleDismiss}
          onSend={handleSend}
          onStop={handleStop}
          onTriggerInsert={triggerInsert}
          onRichInputChange={handleRichInputChange}
          onPastedTickets={handlePastedTickets}
          canSend={canSend}
          dropdownKeyDown={dropdownKeyDown}
          mentionSelectedIndex={mentionKbd.selectedIndex}
          setMentionSelectedIndex={mentionKbd.setSelectedIndex}
          mentionListRef={mentionKbd.listRef}
          ticketSelectedIndex={ticketKbd.selectedIndex}
          setTicketSelectedIndex={ticketKbd.setSelectedIndex}
          ticketListRef={ticketKbd.listRef}
          onTicketResultsChange={handleTicketResultsChange}
          skillSelectedIndex={skillKbd.selectedIndex}
          setSkillSelectedIndex={skillKbd.setSelectedIndex}
          skillListRef={skillKbd.listRef}
          onPickImage={handlePickImage}
          onPasteImage={handlePasteImage}
        />
      </div>
      {/* Phase 7 Task 7.2 — live token usage strip below the input */}
      <UsageIndicator />
      {/* Phase 5/v1.1 — oversize-image compression confirmation modal */}
      {compressPrompt && (
        <CompressConfirmModal
          originalKB={compressPrompt.originalKB}
          capKB={compressPrompt.capKB}
          filename={compressPrompt.filename}
          onChoose={proceed => {
            compressPrompt.resolve(proceed);
            setCompressPrompt(null);
          }}
        />
      )}
    </div>
  );
});

/**
 * Compression confirmation dialog. Asks the user to either compress an
 * oversize image to JPEG (lossy) or skip the attach entirely. There is no
 * "X to dismiss" — both buttons are explicit so the AttachmentManager's
 * promise always resolves with a definitive choice. Esc / outside-click =
 * Cancel (skip).
 */
function CompressConfirmModal({
  originalKB,
  capKB,
  filename,
  onChoose,
}: {
  originalKB: number;
  capKB: number;
  filename: string;
  onChoose: (proceed: boolean) => void;
}) {
  useEffect(() => {
    const handler = (e: KeyboardEvent) => {
      if (e.key === 'Escape') onChoose(false);
      if (e.key === 'Enter') onChoose(true);
    };
    document.addEventListener('keydown', handler);
    return () => document.removeEventListener('keydown', handler);
  }, [onChoose]);

  return (
    <div
      role="dialog"
      aria-modal="true"
      aria-labelledby="compress-confirm-title"
      data-testid="compress-confirm-modal"
      className="fixed inset-0 z-50 flex items-center justify-center"
      style={{ background: 'rgba(0,0,0,0.5)' }}
      onClick={() => onChoose(false)}
    >
      <div
        className="rounded-md p-4 max-w-sm w-full mx-4 shadow-lg"
        style={{
          background: 'var(--bg, #1e1e1e)',
          color: 'var(--fg, #cccccc)',
          border: '1px solid var(--border, #2c2f33)',
        }}
        onClick={e => e.stopPropagation()}
      >
        <h3 id="compress-confirm-title" className="text-sm font-semibold mb-2">
          Image exceeds size cap
        </h3>
        <p className="text-xs mb-2" style={{ color: 'var(--fg-muted, #9ca3af)' }}>
          <strong style={{ color: 'var(--fg)' }}>{filename}</strong> is{' '}
          <strong style={{ color: 'var(--fg)' }}>{originalKB.toLocaleString()} KB</strong>, which exceeds the{' '}
          <strong style={{ color: 'var(--fg)' }}>{capKB.toLocaleString()} KB</strong> cap.
        </p>
        <p className="text-xs mb-3" style={{ color: 'var(--fg-muted, #9ca3af)' }}>
          Compress it to JPEG so it fits? Compression is <em>lossy</em> — fine details may be reduced.
        </p>
        <div className="flex justify-end gap-2 mt-3">
          <button
            type="button"
            data-testid="compress-cancel"
            onClick={() => onChoose(false)}
            className="px-3 py-1.5 text-xs rounded border"
            style={{ borderColor: 'var(--border, #2c2f33)' }}
          >
            Cancel (skip image)
          </button>
          <button
            type="button"
            data-testid="compress-confirm"
            onClick={() => onChoose(true)}
            className="px-3 py-1.5 text-xs rounded font-medium"
            style={{
              background: 'var(--accent, #60a5fa)',
              color: 'var(--bg, #1e1e1e)',
              border: '1px solid var(--accent, #60a5fa)',
            }}
            autoFocus
          >
            Compress &amp; attach
          </button>
        </div>
      </div>
    </div>
  );
}
