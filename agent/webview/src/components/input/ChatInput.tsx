import { useCallback, useEffect, useRef, useState } from 'react';
import { useChatStore } from '@/stores/chatStore';
import { ContextChip } from './ContextChip';
import { MentionAutocomplete } from './MentionAutocomplete';
import { ActionToolbar } from './ActionToolbar';
import type { Mention, MentionSearchResult } from '@/bridge/types';

export function ChatInput() {
  const inputState = useChatStore(s => s.inputState);
  const busy = useChatStore(s => s.busy);
  const focusInputTrigger = useChatStore(s => s.focusInputTrigger);

  const [text, setText] = useState('');
  const [mentions, setMentions] = useState<Mention[]>([]);
  const [showMentions, setShowMentions] = useState(false);
  const [mentionQuery, setMentionQuery] = useState('');
  const [isHovered, setIsHovered] = useState(false);
  const [isFocused, setIsFocused] = useState(false);
  const textareaRef = useRef<HTMLTextAreaElement>(null);

  useEffect(() => {
    if (focusInputTrigger > 0) textareaRef.current?.focus();
  }, [focusInputTrigger]);

  const autoResize = useCallback(() => {
    const el = textareaRef.current;
    if (!el) return;
    el.style.height = 'auto';
    el.style.height = `${Math.min(el.scrollHeight, 200)}px`;
  }, []);

  useEffect(() => { autoResize(); }, [text, autoResize]);

  const handleChange = (e: React.ChangeEvent<HTMLTextAreaElement>) => {
    const value = e.target.value;
    setText(value);
    const cursorPos = e.target.selectionStart;
    const textBeforeCursor = value.slice(0, cursorPos);
    const atMatch = textBeforeCursor.match(/@(\S*)$/);
    if (atMatch) {
      setShowMentions(true);
      setMentionQuery(atMatch[1] ?? '');
    } else {
      setShowMentions(false);
      setMentionQuery('');
    }
  };

  const handleMentionSelect = (result: MentionSearchResult) => {
    const mention: Mention = { type: result.type, label: result.label, path: result.path, icon: result.icon };
    setMentions(prev => [...prev, mention]);
    const cursorPos = textareaRef.current?.selectionStart ?? text.length;
    const textBeforeCursor = text.slice(0, cursorPos);
    const atIndex = textBeforeCursor.lastIndexOf('@');
    if (atIndex >= 0) {
      const newText = text.slice(0, atIndex) + text.slice(cursorPos);
      setText(newText);
    }
    setShowMentions(false);
    setMentionQuery('');
    textareaRef.current?.focus();
  };

  const removeMention = (index: number) => {
    setMentions(prev => prev.filter((_, i) => i !== index));
  };

  const sendMessage = useCallback(() => {
    const trimmed = text.trim();
    if (!trimmed || inputState.locked || busy) return;
    useChatStore.getState().sendMessage(trimmed, mentions);
    setText('');
    setMentions([]);
    setShowMentions(false);
    if (textareaRef.current) textareaRef.current.style.height = 'auto';
  }, [text, mentions, inputState.locked, busy]);

  const handleKeyDown = (e: React.KeyboardEvent<HTMLTextAreaElement>) => {
    if (e.key === 'Enter' && !e.shiftKey && !showMentions) {
      e.preventDefault();
      sendMessage();
    }
  };

  const toggleMode = () => {
    const newMode = inputState.mode === 'agent' ? 'plan' : 'agent';
    (window as any)._togglePlanMode?.(newMode === 'plan');
  };

  return (
    <div className="border-t border-[var(--border,#333)]" onMouseEnter={() => setIsHovered(true)} onMouseLeave={() => setIsHovered(false)}>
      <ActionToolbar isHovered={isHovered || isFocused} />
      <div className="relative px-3 pb-3 pt-1">
        {showMentions && (
          <MentionAutocomplete query={mentionQuery} onSelect={handleMentionSelect} onDismiss={() => { setShowMentions(false); setMentionQuery(''); }} />
        )}
        {mentions.length > 0 && (
          <div className="mb-1.5 flex flex-wrap gap-1">
            {mentions.map((mention, i) => (
              <ContextChip key={`${mention.type}-${mention.label}-${i}`} mention={mention} onRemove={() => removeMention(i)} />
            ))}
          </div>
        )}
        <div className={`flex items-end gap-2 rounded-lg border bg-[var(--input-bg,#2a2a2a)] transition-colors duration-150 ${isFocused ? 'border-[var(--accent,#6366f1)]' : 'border-[var(--input-border,#444)]'} ${inputState.locked ? 'opacity-50' : ''}`}>
          <div className={`flex items-center gap-1 pl-2 pb-2 transition-opacity duration-200 ${isHovered || isFocused ? 'opacity-100' : 'opacity-0'}`}>
            <button onClick={toggleMode} title={`Mode: ${inputState.mode}`} className="flex h-5 items-center rounded px-1.5 text-[10px] font-medium text-[var(--fg-muted)] transition-colors duration-150 hover:bg-[var(--hover-overlay-strong)] hover:text-[var(--fg)]">
              {inputState.mode === 'agent' ? (
                <svg width="12" height="12" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5"><circle cx="8" cy="8" r="5" /><path d="M8 5v3l2 2" strokeLinecap="round" /></svg>
              ) : (
                <svg width="12" height="12" viewBox="0 0 16 16" fill="none" stroke="currentColor" strokeWidth="1.5"><path d="M3 3h10v2H3zM3 7h7v2H3zM3 11h5v2H3z" /></svg>
              )}
            </button>
            {inputState.model && (
              <button onClick={() => (window as any)._changeModel?.('')} title={`Model: ${inputState.model}`} className="flex h-5 items-center rounded px-1.5 text-[10px] text-[var(--fg-muted)] transition-colors duration-150 hover:bg-[var(--hover-overlay-strong)] hover:text-[var(--fg)]">
                {inputState.model}
              </button>
            )}
          </div>
          <textarea ref={textareaRef} data-chat-input value={text} onChange={handleChange} onKeyDown={handleKeyDown} onFocus={() => setIsFocused(true)} onBlur={() => setIsFocused(false)} disabled={inputState.locked} placeholder="Ask anything..." rows={1} aria-label="Chat message input" className="flex-1 resize-none bg-transparent px-1 py-2.5 text-[13px] text-[var(--fg)] outline-none placeholder:text-[var(--fg-muted)] disabled:cursor-not-allowed" />
          <button onClick={sendMessage} disabled={!text.trim() || inputState.locked || busy} className="mb-2 mr-2 flex h-7 w-7 flex-shrink-0 items-center justify-center rounded-md transition-all duration-150 active:scale-[0.97] disabled:opacity-30 disabled:cursor-not-allowed enabled:bg-[var(--accent,#6366f1)] enabled:text-white enabled:hover:brightness-110" title="Send (Enter)">
            <svg width="14" height="14" viewBox="0 0 16 16" fill="none"><path d="M2 8l12-5-5 12-2-5-5-2z" fill="currentColor" /></svg>
          </button>
        </div>
        {isFocused && text.length === 0 && (
          <div className="mt-1 text-[10px] text-[var(--fg-muted)] opacity-60">Enter = send, Shift+Enter = newline, @ = mention</div>
        )}
      </div>
    </div>
  );
}
