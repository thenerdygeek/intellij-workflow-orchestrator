import { useRef, useCallback, useEffect, forwardRef, useImperativeHandle } from 'react';
import type { Mention } from '@/bridge/types';
import { cn } from '@/lib/utils';

// ── Chip color config (matches ContextChip) ──

const chipColors: Record<string, { color: string; bg: string; border: string }> = {
  file:   { color: 'var(--accent-read, #3b82f6)', bg: 'rgba(59,130,246,0.1)',  border: 'rgba(59,130,246,0.25)' },
  folder: { color: 'var(--accent-read, #3b82f6)', bg: 'rgba(59,130,246,0.08)', border: 'rgba(59,130,246,0.2)' },
  symbol: { color: '#a78bfa',                      bg: 'rgba(139,92,246,0.1)',  border: 'rgba(139,92,246,0.25)' },
  ticket: { color: 'var(--accent-read, #3b82f6)', bg: 'rgba(59,130,246,0.1)',  border: 'rgba(59,130,246,0.25)' },
};

const defaultChipColor = { color: 'var(--fg-secondary)', bg: 'var(--chip-bg)', border: 'var(--chip-border)' };

export interface RichInputHandle {
  focus: () => void;
  insertTrigger: (char: string) => void;
  insertChip: (mention: Mention, triggerChar: string) => void;
  clear: () => void;
  getText: () => string;
  getMentions: () => Mention[];
}

interface RichInputProps {
  placeholder?: string;
  disabled?: boolean;
  className?: string;
  onSubmit?: () => void;
  onChange?: (text: string, trigger: { type: '@' | '#' | '/'; query: string } | null) => void;
  onEscape?: () => void;
}

/**
 * Contenteditable input that supports inline mention chips.
 * Chips are rendered as non-editable <span> elements within the editable div.
 * Text and chips flow naturally together on the same line.
 */
export const RichInput = forwardRef<RichInputHandle, RichInputProps>(function RichInput(
  { placeholder, disabled, className, onSubmit, onChange, onEscape },
  ref
) {
  const editorRef = useRef<HTMLDivElement>(null);
  const mentionsRef = useRef<Mention[]>([]);

  // ── Public API ──

  useImperativeHandle(ref, () => ({
    focus: () => editorRef.current?.focus(),
    insertTrigger: (char: string) => {
      const el = editorRef.current;
      if (!el) return;
      el.focus();
      document.execCommand('insertText', false, char);
    },
    insertChip: (mention: Mention, triggerChar: string) => insertChip(mention, triggerChar),
    clear: () => {
      if (editorRef.current) {
        editorRef.current.innerHTML = '';
        editorRef.current.dataset.empty = 'true';
        mentionsRef.current = [];
      }
    },
    getText: () => extractText(),
    getMentions: () => [...mentionsRef.current],
  }));

  // ── Extract plain text (chips become their label) ──

  const extractText = useCallback(() => {
    const el = editorRef.current;
    if (!el) return '';
    let text = '';
    for (const node of el.childNodes) {
      if (node.nodeType === Node.TEXT_NODE) {
        text += node.textContent ?? '';
      } else if (node instanceof HTMLElement && node.dataset.mentionLabel) {
        // Skip chips in text extraction — they're tracked in mentionsRef
      } else {
        text += node.textContent ?? '';
      }
    }
    return text.trim();
  }, []);

  // ── Insert a chip at the current cursor position, replacing trigger text ──

  const insertChip = useCallback((mention: Mention, triggerChar: string) => {
    const el = editorRef.current;
    if (!el) return;

    const sel = window.getSelection();
    if (!sel || sel.rangeCount === 0) return;

    const range = sel.getRangeAt(0);

    // Walk backward from cursor to find the trigger character
    const textNode = range.startContainer;
    if (textNode.nodeType !== Node.TEXT_NODE) return;
    const text = textNode.textContent ?? '';
    const cursorOffset = range.startOffset;
    const triggerIdx = text.lastIndexOf(triggerChar, cursorOffset - 1);
    if (triggerIdx < 0) return;

    // Split the text node: [before trigger] [chip] [after cursor]
    const before = text.slice(0, triggerIdx);
    const after = text.slice(cursorOffset);

    // Create chip element
    const colors = chipColors[mention.type] ?? defaultChipColor;
    const chip = document.createElement('span');
    chip.contentEditable = 'false';
    chip.dataset.mentionType = mention.type;
    chip.dataset.mentionLabel = mention.label;
    chip.dataset.mentionPath = mention.path ?? '';
    chip.className = 'inline-flex items-center gap-0.5 rounded px-1 py-0 text-[11px] font-medium mx-0.5 align-baseline';
    chip.style.cssText = `color:${colors.color};background:${colors.bg};border:1px solid ${colors.border};user-select:none;cursor:default;line-height:1.6;`;
    chip.innerHTML = `<span>${mention.label}</span><button style="opacity:0.6;cursor:pointer;margin-left:2px;font-size:9px;line-height:1;" onclick="this.parentElement.remove()">&times;</button>`;

    // Remove the chip from mentionsRef when it's removed from DOM
    const observer = new MutationObserver(() => {
      if (!el.contains(chip)) {
        mentionsRef.current = mentionsRef.current.filter(m => m !== mention);
        observer.disconnect();
      }
    });
    observer.observe(el, { childList: true, subtree: true });

    // Replace the text node content
    textNode.textContent = before;

    // Insert chip after the text node
    const afterNode = document.createTextNode(after || '\u200B'); // zero-width space if empty
    const parent = textNode.parentNode!;
    if (textNode.nextSibling) {
      parent.insertBefore(afterNode, textNode.nextSibling);
      parent.insertBefore(chip, afterNode);
    } else {
      parent.appendChild(chip);
      parent.appendChild(afterNode);
    }

    // Move cursor after the chip
    const newRange = document.createRange();
    newRange.setStart(afterNode, after ? 0 : 1);
    newRange.collapse(true);
    sel.removeAllRanges();
    sel.addRange(newRange);

    // Track the mention
    mentionsRef.current.push(mention);

    // Notify parent
    fireChange();
  }, []);

  // ── Detect triggers and notify parent ──

  const fireChange = useCallback(() => {
    const el = editorRef.current;
    if (!el) return;

    const text = extractText();
    const sel = window.getSelection();
    let trigger: { type: '@' | '#' | '/'; query: string } | null = null;

    if (sel && sel.rangeCount > 0) {
      const range = sel.getRangeAt(0);
      const textNode = range.startContainer;
      if (textNode.nodeType === Node.TEXT_NODE) {
        const content = textNode.textContent ?? '';
        const pos = range.startOffset;
        const before = content.slice(0, pos);

        // Check for @ trigger
        const atMatch = before.match(/@(\S*)$/);
        if (atMatch) {
          trigger = { type: '@', query: atMatch[1] ?? '' };
        } else {
          // Check for # trigger
          const hashMatch = before.match(/#(\S*)$/);
          if (hashMatch) {
            trigger = { type: '#', query: hashMatch[1] ?? '' };
          } else {
            // Check for / trigger
            const slashMatch = before.match(/(?:^|\s)\/(\S*)$/);
            if (slashMatch) {
              trigger = { type: '/', query: slashMatch[1] ?? '' };
            }
          }
        }
      }
    }

    onChange?.(text, trigger);
  }, [extractText, onChange]);

  // ── Event handlers ──

  const handleInput = useCallback(() => {
    fireChange();
  }, [fireChange]);

  const handleKeyDown = useCallback((e: React.KeyboardEvent<HTMLDivElement>) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      onSubmit?.();
    }
    if (e.key === 'Escape') {
      onEscape?.();
    }
  }, [onSubmit, onEscape]);

  // Placeholder visibility
  const handleFocusBlur = useCallback(() => {
    const el = editorRef.current;
    if (!el) return;
    if (el.textContent?.trim() === '' && el.querySelectorAll('[data-mention-label]').length === 0) {
      el.dataset.empty = 'true';
    } else {
      delete el.dataset.empty;
    }
  }, []);

  useEffect(() => {
    handleFocusBlur();
  }, [handleFocusBlur]);

  // Prevent pasting rich HTML — paste as plain text only
  const handlePaste = useCallback((e: React.ClipboardEvent) => {
    e.preventDefault();
    const text = e.clipboardData.getData('text/plain');
    document.execCommand('insertText', false, text);
  }, []);

  return (
    <div
      ref={editorRef}
      contentEditable={!disabled}
      suppressContentEditableWarning
      onInput={handleInput}
      onKeyDown={handleKeyDown}
      onFocus={handleFocusBlur}
      onBlur={handleFocusBlur}
      onPaste={handlePaste}
      data-empty="true"
      data-placeholder={placeholder}
      className={cn(
        'rich-input text-[13px] leading-relaxed outline-none min-h-[28px] w-full',
        'empty:before:content-[attr(data-placeholder)] empty:before:text-[var(--fg-muted,#6b7280)] empty:before:pointer-events-none',
        disabled && 'opacity-50 cursor-not-allowed',
        className
      )}
      style={{
        wordBreak: 'break-word',
        whiteSpace: 'pre-wrap',
      }}
    />
  );
});

// Re-export for InputBar
export { type Mention };
