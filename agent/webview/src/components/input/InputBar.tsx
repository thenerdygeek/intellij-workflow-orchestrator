import { memo, useState, useCallback, useEffect, useRef } from 'react';
import { Plus, ArrowUp, Square, ChevronDown, Sparkles, ListChecks, File, Folder, Hash, SquareKanban } from 'lucide-react';
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

// ── Types ──

interface DropdownItem { id: string; name: string; description?: string }

// ── ModelChip ──

function ModelChip({ model }: { model: string }) {
  const [items, setItems] = useState<DropdownItem[]>([]);

  useEffect(() => {
    (window as any).updateModelList = (json: string) => {
      try { setItems(JSON.parse(json)); } catch { /* ignore */ }
    };
  }, []);

  return (
    <DropdownMenu>
      <DropdownMenuTrigger asChild>
        <Button variant="ghost" size="sm" className="h-7 gap-1 px-1.5 text-[12px] font-medium">
          <span className="h-[5px] w-[5px] rounded-full shrink-0" style={{ backgroundColor: 'var(--accent)' }} />
          <span className="max-w-[110px] truncate">{model || 'Model'}</span>
          <ChevronDown className="h-2.5 w-2.5" />
        </Button>
      </DropdownMenuTrigger>
      <DropdownMenuContent align="start">
        {items.length === 0
          ? <div className="px-3 py-2 text-[11px]" style={{ color: 'var(--fg-muted)' }}>No models available</div>
          : items.map(m => (
              <DropdownMenuItem key={m.id} onClick={() => window._changeModel?.(m.id)}>
                <div className="flex flex-col">
                  <span className="text-[12px]">{m.name}</span>
                  {m.description && <span className="text-[11px]" style={{ color: 'var(--fg-muted)' }}>{m.description}</span>}
                </div>
              </DropdownMenuItem>
            ))
        }
      </DropdownMenuContent>
    </DropdownMenu>
  );
}

// ── PlanChip ──

function PlanChip({ active }: { active: boolean }) {
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
}

// ── SkillsChip ──

function SkillsChip() {
  const [items, setItems] = useState<DropdownItem[]>([]);

  useEffect(() => {
    (window as any).updateSkillsList = (json: string) => {
      try { setItems(JSON.parse(json)); } catch { /* ignore */ }
    };
  }, []);

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
}

// ── MoreChip ──

function MoreChip() {
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
}

// ── InputBarContent (inside PromptInput context) ──

interface InputBarContentProps {
  showMentions: boolean;
  showSkills: boolean;
  showTickets: boolean;
  mentionQuery: string;
  skillQuery: string;
  ticketQuery: string;
  busy: boolean;
  locked: boolean;
  planActive: boolean;
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
  canSend: boolean;
}

function InputBarContent({
  showMentions,
  showSkills,
  showTickets,
  mentionQuery,
  skillQuery,
  ticketQuery,
  busy,
  planActive,
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
  canSend,
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
        />
      )}

      {/* Skill dropdown (/ skills) — floats above */}
      {showSkills && (
        <SkillDropdown
          query={skillQuery}
          onSelect={onSkillSelect}
          onDismiss={onDismissMentions}
        />
      )}

      {/* Ticket dropdown (# tickets) — floats above */}
      {showTickets && (
        <TicketDropdown
          query={ticketQuery}
          onSelect={onTicketSelect}
          onDismiss={onDismissMentions}
        />
      )}

      {/* Rich contenteditable input — chips render inline with text */}
      <div className="px-3 pt-2 pb-1">
        <RichInput
          ref={richInputRef}
          placeholder="Ask anything... (@ context, # ticket, / skill)"
          disabled={busy}
          onSubmit={onSend}
          onChange={onRichInputChange}
          onEscape={onDismissMentions}
        />
      </div>

      {/* Action row */}
      <PromptInputActions className="justify-between px-2 pb-2 pt-0.5">
        {/* Left: + · Model · Plan · ··· */}
        <div className="flex items-center gap-0.5">
          {/* + button: picker for File, Folder, Symbol, Skill */}
          <DropdownMenu>
            <DropdownMenuTrigger asChild>
              <Button variant="ghost" size="icon" className="h-7 w-7" title="Add context or skill">
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
                <Hash className="size-3.5" style={{ color: '#a78bfa' }} />
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
      } else {
        setShowSkills(true); setShowMentions(false); setShowTickets(false);
        setSkillQuery(trigger.query);
      }
    } else {
      setShowMentions(false); setShowSkills(false); setShowTickets(false);
      setMentionQuery(''); setSkillQuery(''); setTicketQuery('');
    }
  }, []);

  const handleMentionSelect = useCallback((result: MentionSearchResult) => {
    const mention: Mention = { type: result.type, label: result.label, path: result.path, icon: result.icon };
    (richInputRef.current as any)?.insertChip?.(mention, '@');
    setShowMentions(false); setMentionQuery('');
  }, []);

  const handleTicketSelect = useCallback((result: MentionSearchResult) => {
    const mention: Mention = { type: 'ticket', label: result.label, path: result.path, icon: result.icon };
    (richInputRef.current as any)?.insertChip?.(mention, '#');
    setShowTickets(false); setTicketQuery('');
  }, []);

  const handleSkillSelect = useCallback((_skillName: string) => {
    setShowSkills(false); setSkillQuery('');
  }, []);

  const handleSend = useCallback(() => {
    const ri = richInputRef.current;
    if (!ri) return;
    const text = ri.getText();
    const mentions = ri.getMentions();
    if (!text.trim() && mentions.length === 0) return;
    if (useChatStore.getState().inputState.locked || useChatStore.getState().busy) return;
    useChatStore.getState().sendMessage(text.trim(), mentions);
    ri.clear();
    setHasText(false);
    setShowMentions(false); setShowSkills(false); setShowTickets(false);
  }, []);

  const handleStop = useCallback(() => { window._cancelTask?.(); }, []);

  const triggerInsert = useCallback((char: '@' | '/' | '#') => {
    richInputRef.current?.insertTrigger(char);
  }, []);

  const handleDismiss = useCallback(() => {
    setShowMentions(false); setShowSkills(false); setShowTickets(false);
    setMentionQuery(''); setSkillQuery(''); setTicketQuery('');
  }, []);

  const canSend = hasText && !inputState.locked && !busy;
  const planActive = inputState.mode === 'plan';

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
          locked={inputState.locked}
          planActive={planActive}
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
          canSend={canSend}
        />
      </div>
    </div>
  );
});
