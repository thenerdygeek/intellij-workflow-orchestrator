import { memo, useState, useCallback, useEffect, useRef } from 'react';
import { Plus, ArrowUp, Square, ChevronDown, Sparkles, ListChecks } from 'lucide-react';
import { useChatStore } from '@/stores/chatStore';
import type { Mention, MentionSearchResult } from '@/bridge/types';
import {
  PromptInput,
  PromptInputTextarea,
  PromptInputActions,
  PromptInputAction,
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
  return (
    <Button
      variant="ghost"
      size="sm"
      className="h-7 gap-1 px-1.5 text-[12px] font-medium"
      style={{ color: active ? 'var(--accent, #60a5fa)' : undefined }}
      onClick={() => window._togglePlanMode?.(!active)}
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
  mentionQuery: string;
  busy: boolean;
  locked: boolean;
  planActive: boolean;
  model: string;
  onTextChange: (value: string) => void;
  onMentionSelect: (result: MentionSearchResult) => void;
  onDismissMentions: () => void;
  onRemoveMention: (index: number) => void;
  onSend: () => void;
  onStop: () => void;
  onTriggerMention: () => void;
  canSend: boolean;
}

function InputBarContent({
  mentions,
  showMentions,
  mentionQuery,
  busy,
  planActive,
  model,
  onMentionSelect,
  onDismissMentions,
  onRemoveMention,
  onSend,
  onStop,
  onTriggerMention,
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
      {/* Mention dropdown — floats above */}
      {showMentions && (
        <MentionDropdown
          query={mentionQuery}
          onSelect={onMentionSelect}
          onDismiss={onDismissMentions}
        />
      )}

      {/* Context chips */}
      {mentions.length > 0 && (
        <div className="flex flex-wrap gap-1 px-3 pt-2.5">
          {mentions.map((mention, i) => (
            <ContextChip
              key={`${mention.type}-${mention.label}-${i}`}
              mention={mention}
              onRemove={() => onRemoveMention(i)}
            />
          ))}
        </div>
      )}

      {/* Textarea */}
      <PromptInputTextarea
        placeholder="Ask anything... (@ for files, / for commands, $ for skills)"
        className="text-[13px] leading-relaxed px-3"
        onKeyDown={(e) => {
          if (e.key === 'Escape') onDismissMentions();
        }}
      />

      {/* Action row */}
      <PromptInputActions className="justify-between px-2 pb-2 pt-0.5">
        {/* Left: + · Model · Plan · Skills · ··· */}
        <div className="flex items-center gap-0.5">
          <PromptInputAction tooltip="Add context (@file, @folder, @tool, @skill)">
            <Button
              variant="ghost"
              size="icon"
              className="h-7 w-7"
              onClick={onTriggerMention}
            >
              <Plus className="h-3.5 w-3.5" />
            </Button>
          </PromptInputAction>

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
  const [mentionQuery, setMentionQuery] = useState('');

  // We store a ref to the PromptInput's internal textareaRef via a callback
  // that InputBarContent will call. Since InputBarContent is inside the
  // PromptInput provider, it can access usePromptInput().textareaRef.
  // We use a mutable ref holder at this level for callbacks that need the textarea.
  const textareaRefHolder = useRef<HTMLTextAreaElement | null>(null);

  // Detect @ mention trigger
  const handleValueChange = useCallback((value: string) => {
    setText(value);
    const el = textareaRefHolder.current;
    const cursorPos = el?.selectionStart ?? value.length;
    const textBeforeCursor = value.slice(0, cursorPos);
    const atMatch = textBeforeCursor.match(/@(\S*)$/);
    if (atMatch) {
      setShowMentions(true);
      setMentionQuery(atMatch[1] ?? '');
    } else {
      setShowMentions(false);
      setMentionQuery('');
    }
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
  }, [text, mentions]);

  const handleStop = useCallback(() => {
    window._cancelTask?.();
  }, []);

  const triggerMention = useCallback(() => {
    setText(prev => {
      const el = textareaRefHolder.current;
      if (!el) return prev + '@';
      const pos = el.selectionStart;
      const newText = prev.slice(0, pos) + '@' + prev.slice(pos);
      setTimeout(() => { el.focus(); el.setSelectionRange(pos + 1, pos + 1); }, 0);
      return newText;
    });
    setShowMentions(true);
    setMentionQuery('');
  // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

  const handleDismissMentions = useCallback(() => {
    setShowMentions(false);
    setMentionQuery('');
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
          mentionQuery={mentionQuery}
          busy={busy}
          locked={inputState.locked}
          planActive={planActive}
          model={inputState.model ?? ''}
          onTextChange={handleValueChange}
          onMentionSelect={handleMentionSelect}
          onDismissMentions={handleDismissMentions}
          onRemoveMention={handleRemoveMention}
          onSend={handleSend}
          onStop={handleStop}
          onTriggerMention={triggerMention}
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
