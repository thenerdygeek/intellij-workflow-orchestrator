import { memo, useState, useCallback, useEffect, useRef, useMemo } from 'react';
import { Plus, ArrowUp, Square, ChevronDown, Sparkles, Brain, ListChecks, File, Folder, Hash, SquareKanban } from 'lucide-react';
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
import { MentionDropdown } from './MentionDropdown';
import { SkillDropdown } from './SkillDropdown';
import { TicketDropdown } from './TicketDropdown';
import { useDropdownKeyboard } from '@/hooks/useDropdownKeyboard';

// ── Types ──

interface DropdownItem { id: string; name: string; provider?: string; thinking?: boolean; description?: string }

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

const ModelChip = memo(function ModelChip({ model }: { model: string }) {
  const [items, setItems] = useState<DropdownItem[]>([]);
  const activeItem = useMemo(() => items.find(m => m.name === model), [items, model]);

  useEffect(() => {
    (window as any).updateModelList = (json: string) => {
      try { setItems(JSON.parse(json)); } catch { /* ignore */ }
    };
  }, []);

  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button variant="ghost" size="sm" className="h-7 gap-1 px-1.5 text-[12px] font-medium whitespace-nowrap">
          <ProviderLogo provider={activeItem?.provider} size={12} />
          <span>{model || 'Model'}</span>
          {activeItem?.thinking && <Brain className="h-3 w-3 shrink-0" style={{ color: 'var(--accent, #60a5fa)' }} />}
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
                <div className="flex flex-col flex-1 min-w-0">
                  <span className="text-[12px]">{m.name}</span>
                  {m.provider && <span className="text-[10px] capitalize" style={{ color: 'var(--fg-muted)' }}>{m.provider}</span>}
                </div>
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
}: InputBarContentProps) {
  // Focus on trigger from store
  const focusTrigger = useChatStore(s => s.focusInputTrigger);
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
          placeholder={busy && steeringMode ? 'Steer the agent...' : 'Ask anything... (@ context, # ticket, / skill)'}
          disabled={locked || (busy && !steeringMode)}
          onSubmit={onSend}
          onChange={onRichInputChange}
          onEscape={onDismissMentions}
          onDropdownKeyDown={dropdownKeyDown}
          onPastedTickets={onPastedTickets}
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
            </DropdownMenuContent>
          </DropdownMenu>

          <ModelChip model={model} />
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
  const richInputRef = useRef<RichInputHandle>(null);

  const [hasText, setHasText] = useState(false);
  const [showMentions, setShowMentions] = useState(false);
  const [showSkills, setShowSkills] = useState(false);
  const [showTickets, setShowTickets] = useState(false);
  const [mentionQuery, setMentionQuery] = useState('');
  const [skillQuery, setSkillQuery] = useState('');
  const [ticketQuery, setTicketQuery] = useState('');

  // ── Flat item lists for keyboard navigation ──
  // MentionDropdown manages its own filtered list internally, so we keep a
  // parallel copy here for the hook. Ticket and Skill dropdowns expose items
  // via state/store respectively.
  const mentionResults = useChatStore(s => s.mentionResults);
  const skillsList = useChatStore(s => s.skillsList);

  // Flat mention items in display order (file → folder → symbol, up to 5 per group)
  const flatMentionItems = useMemo(() => {
    const maxPerGroup = mentionQuery ? 5 : 8;
    const scored = mentionResults
      .filter(r => r.type === 'file' || r.type === 'folder' || r.type === 'symbol')
      .map(r => ({ ...r }));
    const grouped: Record<string, typeof scored> = {};
    for (const r of scored) { (grouped[r.type] ??= []).push(r); }
    return (['file', 'folder', 'symbol'] as const).flatMap(t =>
      (grouped[t] ?? []).slice(0, maxPerGroup)
    );
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
      // auto-create a pending chip and validate it asynchronously
      const prevQuery = prevTicketQueryRef.current;
      if (prevQuery && /^[A-Za-z]+-\d+$/.test(prevQuery)) {
        const ticketKey = prevQuery.toUpperCase();
        const mention: Mention = { type: 'ticket', label: ticketKey, path: ticketKey };
        richInputRef.current?.insertChip(mention, '#', 'pending');

        // Async validation via Kotlin bridge
        validateTicket(ticketKey);
      }
      prevTicketQueryRef.current = '';
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

    // Timeout: if no response in 5s, strip the chip
    const timeoutId = setTimeout(() => {
      if (resolved) return;
      resolved = true;
      delete (window as any)[callbackKey];
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

    // Call Kotlin bridge to validate — if bridge not available, strip chip immediately
    if (window._validateTicket) {
      window._validateTicket(ticketKey, callbackKey);
    } else {
      resolved = true;
      clearTimeout(timeoutId);
      delete (window as any)[callbackKey];
      richInputRef.current?.removeChipByLabel?.(ticketKey);
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
    const mention: Mention = { type: 'ticket', label: result.label, path: result.path, icon: result.icon };
    richInputRef.current?.insertChip(mention, '#', 'valid');
    setShowTickets(false); setTicketQuery('');
    setTicketItems([]);
  }, []);

  const handleSkillSelect = useCallback((skillName: string) => {
    const mention: Mention = { type: 'skill', label: skillName, path: skillName };
    richInputRef.current?.insertChip(mention, '/');
    setShowSkills(false); setSkillQuery('');
  }, []);

  const handleSend = useCallback(() => {
    const ri = richInputRef.current;
    if (!ri) return;
    const text = ri.getText();
    const mentions = ri.getMentions();
    if (!text.trim() && mentions.length === 0) return;
    const state = useChatStore.getState();
    if (state.inputState.locked) return;
    if (state.busy && !state.steeringMode) return;
    useChatStore.getState().sendMessage(text.trim(), mentions);
    ri.clear();
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

  const canSend = hasText && !inputState.locked && (!busy || steeringMode);
  const planActive = inputState.mode === 'plan';
  const ralphActive = inputState.ralph ?? false;

  return (
    <div className="px-3 pb-3 pt-2">
      <div
        className="relative rounded-xl border p-2 cursor-text"
        style={{ backgroundColor: 'var(--input-bg)', borderColor: 'var(--input-border)' }}
        onClick={() => richInputRef.current?.focus()}
      >
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
        />
      </div>
    </div>
  );
});
