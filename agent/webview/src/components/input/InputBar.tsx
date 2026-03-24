import { memo, useState, useCallback, useEffect, useRef } from 'react';
import { Plus, ArrowUp, Square, ChevronDown, Sparkles, ListChecks, File, Folder, Hash, SquareKanban } from 'lucide-react';
import { useChatStore } from '@/stores/chatStore';
import type { Mention, MentionSearchResult } from '@/bridge/types';
import {
  PromptInput,
  PromptInputTextarea,
  PromptInputActions,
  usePromptInput,
} from '@/components/ui/prompt-kit/prompt-input';
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
import { ContextChip } from './ContextChip';

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
  text: string;
  mentions: Mention[];
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
  onTextChange: (value: string) => void;
  onMentionSelect: (result: MentionSearchResult) => void;
  onSkillSelect: (skillName: string) => void;
  onTicketSelect: (result: MentionSearchResult) => void;
  onDismissMentions: () => void;
  onRemoveMention: (index: number) => void;
  onSend: () => void;
  onStop: () => void;
  onTriggerInsert: (char: '@' | '/' | '#') => void;
  canSend: boolean;
}

function InputBarContent({
  mentions,
  showMentions,
  showSkills,
  showTickets,
  mentionQuery,
  skillQuery,
  ticketQuery,
  busy,
  planActive,
  model,
  onMentionSelect,
  onSkillSelect,
  onTicketSelect,
  onDismissMentions,
  onRemoveMention,
  onSend,
  onStop,
  onTriggerInsert,
  canSend,
}: InputBarContentProps) {
  const { textareaRef } = usePromptInput();

  // Focus on trigger from store
  const focusTrigger = useChatStore(s => s.focusInputTrigger);
  useEffect(() => {
    if (focusTrigger > 0) textareaRef.current?.focus();
  }, [focusTrigger, textareaRef]);

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

      {/* Inline chips + textarea — chips flow on the same line as the text input */}
      <div className="flex flex-wrap items-center gap-1 px-3 pt-1.5 pb-0.5">
        {mentions.map((mention, i) => (
          <ContextChip
            key={`${mention.type}-${mention.label}-${i}`}
            mention={mention}
            onRemove={() => onRemoveMention(i)}
          />
        ))}
        <div className="flex-1 min-w-[120px]">
          <PromptInputTextarea
            placeholder={mentions.length > 0 ? 'Type your message...' : 'Ask anything... (@ context, # ticket, / skill)'}
            className="text-[13px] leading-relaxed !min-h-[28px] !px-0 !py-0"
            onKeyDown={(e) => {
              if (e.key === 'Escape') onDismissMentions();
            }}
          />
        </div>
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

  const [text, setText] = useState('');
  const [mentions, setMentions] = useState<Mention[]>([]);
  const [showMentions, setShowMentions] = useState(false);
  const [showSkills, setShowSkills] = useState(false);
  const [showTickets, setShowTickets] = useState(false);
  const [mentionQuery, setMentionQuery] = useState('');
  const [skillQuery, setSkillQuery] = useState('');
  const [ticketQuery, setTicketQuery] = useState('');

  const textareaRefHolder = useRef<HTMLTextAreaElement | null>(null);

  // Detect @ (context mention) and / (skill) triggers
  const handleValueChange = useCallback((value: string) => {
    setText(value);
    const el = textareaRefHolder.current;
    const cursorPos = el?.selectionStart ?? value.length;
    const textBeforeCursor = value.slice(0, cursorPos);

    // Check for @ mention trigger (files/folders/symbols)
    const atMatch = textBeforeCursor.match(/@(\S*)$/);
    if (atMatch) {
      setShowMentions(true);
      setShowSkills(false);
      setMentionQuery(atMatch[1] ?? '');
      return;
    }

    // Check for # ticket trigger
    const hashMatch = textBeforeCursor.match(/#(\S*)$/);
    if (hashMatch) {
      setShowTickets(true);
      setShowMentions(false);
      setShowSkills(false);
      setTicketQuery(hashMatch[1] ?? '');
      return;
    }

    // Check for / skill trigger (only at start of line or after space)
    const slashMatch = textBeforeCursor.match(/(?:^|\s)\/(\S*)$/);
    if (slashMatch) {
      setShowSkills(true);
      setShowMentions(false);
      setShowTickets(false);
      setSkillQuery(slashMatch[1] ?? '');
      return;
    }

    setShowMentions(false);
    setShowSkills(false);
    setShowTickets(false);
    setMentionQuery('');
    setSkillQuery('');
    setTicketQuery('');
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const handleMentionSelect = useCallback((result: MentionSearchResult) => {
    const mention: Mention = { type: result.type, label: result.label, path: result.path, icon: result.icon };
    setMentions(prev => [...prev, mention]);
    setText(prev => {
      const el = textareaRefHolder.current;
      const cursorPos = el?.selectionStart ?? prev.length;
      const textBeforeCursor = prev.slice(0, cursorPos);
      const atIndex = textBeforeCursor.lastIndexOf('@');
      if (atIndex >= 0) return prev.slice(0, atIndex) + prev.slice(cursorPos);
      return prev;
    });
    setShowMentions(false);
    setMentionQuery('');
    textareaRefHolder.current?.focus();
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const handleTicketSelect = useCallback((result: MentionSearchResult) => {
    const mention: Mention = { type: 'ticket', label: result.label, path: result.path, icon: result.icon };
    setMentions(prev => [...prev, mention]);
    setText(prev => {
      const el = textareaRefHolder.current;
      const cursorPos = el?.selectionStart ?? prev.length;
      const textBeforeCursor = prev.slice(0, cursorPos);
      const hashIndex = textBeforeCursor.lastIndexOf('#');
      if (hashIndex >= 0) return prev.slice(0, hashIndex) + prev.slice(cursorPos);
      return prev;
    });
    setShowTickets(false);
    setTicketQuery('');
    textareaRefHolder.current?.focus();
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const handleSkillSelect = useCallback((skillName: string) => {
    // Replace /query with the full skill command
    setText(prev => {
      const el = textareaRefHolder.current;
      const cursorPos = el?.selectionStart ?? prev.length;
      const textBeforeCursor = prev.slice(0, cursorPos);
      const slashIndex = textBeforeCursor.lastIndexOf('/');
      if (slashIndex >= 0) return prev.slice(0, slashIndex) + `/${skillName} ` + prev.slice(cursorPos);
      return `/${skillName} `;
    });
    setShowSkills(false);
    setSkillQuery('');
    textareaRefHolder.current?.focus();
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const handleSend = useCallback(() => {
    const trimmed = text.trim();
    if (!trimmed) return;
    const currentInputState = useChatStore.getState().inputState;
    const currentBusy = useChatStore.getState().busy;
    if (currentInputState.locked || currentBusy) return;
    useChatStore.getState().sendMessage(trimmed, mentions);
    setText('');
    setMentions([]);
    setShowMentions(false);
    setShowSkills(false);
    setShowTickets(false);
  }, [text, mentions]);

  const handleStop = useCallback(() => {
    window._cancelTask?.();
  }, []);

  // Insert @ or / into the textarea (called from + button picker)
  const triggerInsert = useCallback((char: '@' | '/' | '#') => {
    setText(prev => {
      const el = textareaRefHolder.current;
      if (!el) return prev + char;
      const pos = el.selectionStart;
      const newText = prev.slice(0, pos) + char + prev.slice(pos);
      setTimeout(() => { el.focus(); el.setSelectionRange(pos + 1, pos + 1); }, 0);
      return newText;
    });
    if (char === '@') {
      setShowMentions(true);
      setShowSkills(false);
      setShowTickets(false);
      setMentionQuery('');
    } else if (char === '#') {
      setShowTickets(true);
      setShowMentions(false);
      setShowSkills(false);
      setTicketQuery('');
    } else {
      setShowSkills(true);
      setShowMentions(false);
      setShowTickets(false);
      setSkillQuery('');
    }
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const handleDismissMentions = useCallback(() => {
    setShowMentions(false);
    setShowSkills(false);
    setShowTickets(false);
    setMentionQuery('');
    setSkillQuery('');
    setTicketQuery('');
  }, []);

  const handleRemoveMention = useCallback((index: number) => {
    setMentions(prev => prev.filter((_, j) => j !== index));
  }, []);

  const canSend = !!text.trim() && !inputState.locked && !busy;
  const planActive = inputState.mode === 'plan';

  return (
    <div className="px-3 pb-3 pt-2">
      <PromptInput
        value={text}
        onValueChange={handleValueChange}
        onSubmit={handleSend}
        isLoading={busy}
        disabled={inputState.locked}
        maxHeight={200}
        className="relative rounded-xl"
        style={{
          backgroundColor: 'var(--input-bg)',
          borderColor: 'var(--input-border)',
        }}
      >
        <InputBarContentWithRef
          textareaRefHolder={textareaRefHolder}
          text={text}
          mentions={mentions}
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
          onTextChange={handleValueChange}
          onMentionSelect={handleMentionSelect}
          onSkillSelect={handleSkillSelect}
          onTicketSelect={handleTicketSelect}
          onDismissMentions={handleDismissMentions}
          onRemoveMention={handleRemoveMention}
          onSend={handleSend}
          onStop={handleStop}
          onTriggerInsert={triggerInsert}
          canSend={canSend}
        />
      </PromptInput>
    </div>
  );
});

// Bridge component that syncs the PromptInput context's textareaRef to the parent's holder
function InputBarContentWithRef({
  textareaRefHolder,
  ...props
}: InputBarContentProps & { textareaRefHolder: { current: HTMLTextAreaElement | null } }) {
  const { textareaRef } = usePromptInput();

  // Sync the context's textareaRef to the parent's holder on every render
  useEffect(() => {
    const sync = () => { textareaRefHolder.current = textareaRef.current; };
    sync();
    // Re-sync periodically until textarea is mounted
    if (!textareaRef.current) {
      const timer = setTimeout(sync, 50);
      return () => clearTimeout(timer);
    }
  }, [textareaRef, textareaRefHolder]);

  return <InputBarContent {...props} />;
}
