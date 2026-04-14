import { useRef, useCallback, useEffect, forwardRef, useImperativeHandle } from 'react';
import type { Mention } from '@/bridge/types';
import { cn } from '@/lib/utils';

// ── Chip color config ──

type ChipStatus = 'default' | 'pending' | 'valid' | 'invalid';

const chipColors: Record<string, { color: string; bg: string; border: string }> = {
  file:   { color: 'var(--accent-read, #3b82f6)', bg: 'rgba(59,130,246,0.1)',  border: 'rgba(59,130,246,0.25)' },
  folder: { color: 'var(--accent-read, #3b82f6)', bg: 'rgba(59,130,246,0.08)', border: 'rgba(59,130,246,0.2)' },
  symbol: { color: 'var(--accent-search, #a78bfa)',  bg: 'color-mix(in srgb, var(--accent-search, #a78bfa) 10%, transparent)', border: 'color-mix(in srgb, var(--accent-search, #a78bfa) 25%, transparent)' },
  ticket: { color: 'var(--accent-read, #3b82f6)', bg: 'rgba(59,130,246,0.1)',  border: 'rgba(59,130,246,0.25)' },
};

// Status-based colors for ticket validation states
const statusColors: Record<ChipStatus, { color: string; bg: string; border: string }> = {
  default: { color: 'var(--accent-read, #3b82f6)', bg: 'rgba(59,130,246,0.1)',  border: 'rgba(59,130,246,0.25)' },
  pending: { color: 'var(--accent-edit, #ca8a04)',    bg: 'color-mix(in srgb, var(--accent-edit, #ca8a04) 12%, transparent)', border: 'color-mix(in srgb, var(--accent-edit, #ca8a04) 30%, transparent)' },
  valid:   { color: 'var(--success, #22c55e)',      bg: 'rgba(34,197,94,0.1)',   border: 'rgba(34,197,94,0.25)' },
  invalid: { color: 'var(--error, #ef4444)',        bg: 'rgba(239,68,68,0.1)',   border: 'rgba(239,68,68,0.25)' },
};

const defaultChipColor = { color: 'var(--fg-secondary)', bg: 'var(--chip-bg)', border: 'var(--chip-border)' };

/** Single glow pulse for successful ticket validation */
const GLOW_KEYFRAMES = `
@keyframes chip-success-glow {
  0%   { box-shadow: 0 0 0 0 rgba(34,197,94,0.4); }
  50%  { box-shadow: 0 0 8px 2px rgba(34,197,94,0.3); }
  100% { box-shadow: 0 0 0 0 rgba(34,197,94,0); }
}`;

// Inject keyframes once into the document
let glowStyleInjected = false;
function ensureGlowStyle() {
  if (glowStyleInjected) return;
  const style = document.createElement('style');
  style.textContent = GLOW_KEYFRAMES;
  document.head.appendChild(style);
  glowStyleInjected = true;
}

/** Matches #PROJ-123 style ticket references in pasted text */
const PASTED_TICKET_PATTERN = /#([A-Za-z]+-\d+)/g;

export interface RichInputHandle {
  focus: () => void;
  insertTrigger: (char: string) => void;
  insertChip: (mention: Mention, triggerChar: string, status?: ChipStatus) => void;
  /** Update the visual status of a chip by label (for async ticket validation) */
  updateChipStatus: (label: string, status: ChipStatus, tooltip?: string) => void;
  /** Remove a chip by label, replacing it with raw #label text (for failed validation) */
  removeChipByLabel: (label: string) => void;
  clear: () => void;
  /** Set the input text content (replaces existing text, clears mentions) */
  setText: (text: string) => void;
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
  /**
   * Called first on every keydown event. If it returns true, the event was
   * handled by the dropdown (navigation, selection, or dismiss) and RichInput
   * should NOT process it further (e.g. Enter should not submit the message).
   */
  onDropdownKeyDown?: (e: React.KeyboardEvent) => boolean;
  /** Called when pasted text contains ticket keys (e.g. #PROJ-123) that need async validation */
  onPastedTickets?: (ticketKeys: string[]) => void;
}

/**
 * Contenteditable input that supports inline mention chips.
 * Chips are rendered as non-editable <span> elements within the editable div.
 * Text and chips flow naturally together on the same line.
 */
export const RichInput = forwardRef<RichInputHandle, RichInputProps>(function RichInput(
  { placeholder, disabled, className, onSubmit, onChange, onEscape, onDropdownKeyDown, onPastedTickets },
  ref
) {
  const editorRef = useRef<HTMLDivElement>(null);
  const mentionsRef = useRef<Mention[]>([]);

  // ── Public API ──

  // ── Update chip visual status (for async validation) ──

  const updateChipStatus = useCallback((label: string, status: ChipStatus, tooltip?: string) => {
    const el = editorRef.current;
    if (!el) return;
    const chips = el.querySelectorAll<HTMLElement>(`[data-mention-label="${label}"]`);
    const colors = statusColors[status];
    chips.forEach(chip => {
      chip.dataset.chipStatus = status;
      chip.style.color = colors.color;
      chip.style.background = colors.bg;
      chip.style.borderColor = colors.border;
      if (tooltip) {
        chip.title = tooltip;
      }
      if (status === 'invalid') {
        chip.style.textDecoration = 'line-through';
        chip.style.opacity = '0.7';
      }
      if (status === 'valid') {
        ensureGlowStyle();
        chip.style.animation = 'chip-success-glow 0.8s ease-out';
        chip.addEventListener('animationend', () => {
          chip.style.animation = '';
        }, { once: true });
      }
    });
  }, []);

  // ── Remove chip by label, replacing with raw #label text ──

  const removeChipByLabel = useCallback((label: string) => {
    const el = editorRef.current;
    if (!el) return;
    const chips = el.querySelectorAll<HTMLElement>(`[data-mention-label="${label}"]`);
    chips.forEach(chip => {
      const textNode = document.createTextNode(`#${label} `);
      chip.parentNode?.replaceChild(textNode, chip);
    });
    // Remove from mentions ref
    mentionsRef.current = mentionsRef.current.filter(m => m.label !== label);
  }, []);

  useImperativeHandle(ref, () => ({
    focus: () => editorRef.current?.focus(),
    insertTrigger: (char: string) => {
      const el = editorRef.current;
      if (!el) return;
      el.focus();
      document.execCommand('insertText', false, char);
    },
    insertChip: (mention: Mention, triggerChar: string, status?: ChipStatus) => insertChip(mention, triggerChar, status),
    updateChipStatus,
    removeChipByLabel,
    clear: () => {
      if (editorRef.current) {
        editorRef.current.innerHTML = '';
        editorRef.current.dataset.empty = 'true';
        mentionsRef.current = [];
      }
    },
    setText: (text: string) => {
      const el = editorRef.current;
      if (!el) return;
      el.textContent = text;
      el.dataset.empty = text ? 'false' : 'true';
      mentionsRef.current = [];
      // Move cursor to end
      const range = document.createRange();
      const sel = window.getSelection();
      range.selectNodeContents(el);
      range.collapse(false);
      sel?.removeAllRanges();
      sel?.addRange(range);
    },
    getText: () => extractText(),
    getMentions: () => {
      // Only return valid/pending mentions. Invalid (red) chips are excluded —
      // they get rendered as plain text by getText() instead.
      const el = editorRef.current;
      if (!el) return [...mentionsRef.current];
      const validMentions: Mention[] = [];
      const chipEls = el.querySelectorAll<HTMLElement>('[data-mention-label]');
      chipEls.forEach(chip => {
        const status = chip.dataset.chipStatus;
        if (status !== 'invalid') {
          const mention = mentionsRef.current.find(m => m.label === chip.dataset.mentionLabel);
          if (mention) validMentions.push(mention);
        }
      });
      return validMentions;
    },
  }));

  // ── Extract plain text ──
  // Valid/pending chips are skipped (they're sent as mentions separately).
  // Invalid (red) chips are converted back to "#LABEL" plain text so
  // the user's intended hashtag reaches the LLM.

  const extractText = useCallback(() => {
    const el = editorRef.current;
    if (!el) return '';
    let text = '';
    for (const node of el.childNodes) {
      if (node.nodeType === Node.TEXT_NODE) {
        text += node.textContent ?? '';
      } else if (node instanceof HTMLElement && node.dataset.mentionLabel) {
        const status = node.dataset.chipStatus;
        const mentionType = node.dataset.mentionType;
        if (status === 'invalid') {
          // Red chip → convert back to plain text with # prefix
          text += `#${node.dataset.mentionLabel}`;
        } else {
          // Valid/pending chips → include as @label or /label in text
          // so the displayed user message is readable
          const prefix = mentionType === 'skill' ? '/' : mentionType === 'ticket' ? '#' : '@';
          text += `${prefix}${node.dataset.mentionLabel}`;
        }
      } else if (node instanceof HTMLElement) {
        const tag = node.tagName;
        if (tag === 'BR') {
          // <br> = explicit line break
          text += '\n';
        } else {
          // Block-level elements (<div>, <p>) created by contentEditable for each line
          // Prepend \n unless this is the very first content
          if (text.length > 0) text += '\n';
          text += node.textContent ?? '';
        }
      } else {
        text += node.textContent ?? '';
      }
    }
    return text.trim();
  }, []);

  // ── Insert a chip at the current cursor position, replacing trigger text ──

  const insertChip = useCallback((mention: Mention, triggerChar: string, status: ChipStatus = 'default') => {
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

    // Use status-based colors for tickets, type-based for everything else
    const colors = mention.type === 'ticket'
      ? statusColors[status]
      : (chipColors[mention.type] ?? defaultChipColor);

    // Create chip element
    const chip = document.createElement('span');
    chip.contentEditable = 'false';
    chip.dataset.mentionType = mention.type;
    chip.dataset.mentionLabel = mention.label;
    chip.dataset.mentionPath = mention.path ?? '';
    chip.dataset.chipStatus = status;
    chip.className = 'inline-flex items-center gap-0.5 rounded px-1 py-0 text-[11px] font-medium mx-0.5 align-baseline transition-colors duration-300';
    chip.style.cssText = `color:${colors.color};background:${colors.bg};border:1px solid ${colors.border};user-select:none;cursor:default;line-height:1.6;`;
    chip.innerHTML = `<span>${mention.label}</span><button data-remove aria-label="Remove ${mention.label}" style="opacity:0.6;cursor:pointer;margin-left:2px;font-size:9px;line-height:1;">&times;</button>`;

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

  // ── Placeholder visibility ──

  const updateEmptyState = useCallback(() => {
    const el = editorRef.current;
    if (!el) return;
    const isEmpty = el.textContent?.trim() === '' && el.querySelectorAll('[data-mention-label]').length === 0;
    const isFocused = document.activeElement === el;
    if (isEmpty && !isFocused) {
      el.dataset.empty = 'true';
    } else {
      delete el.dataset.empty;
    }
  }, []);

  const handleFocusBlur = updateEmptyState;

  // ── Event handlers ──

  const handleInput = useCallback(() => {
    fireChange();
    updateEmptyState();
  }, [fireChange, updateEmptyState]);

  const handleKeyDown = useCallback((e: React.KeyboardEvent<HTMLDivElement>) => {
    // Give the active dropdown first refusal on navigation keys.
    // If it returns true the event is fully consumed — don't submit or escape.
    if (onDropdownKeyDown?.(e)) return;

    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      onSubmit?.();
    }
    if (e.key === 'Escape') {
      onEscape?.();
    }
  }, [onSubmit, onEscape, onDropdownKeyDown]);

  useEffect(() => {
    handleFocusBlur();
  }, [handleFocusBlur]);

  // Single editor-level MutationObserver: sync mentionsRef when chips are removed from DOM
  useEffect(() => {
    const el = editorRef.current;
    if (!el) return;

    const chipObserver = new MutationObserver(() => {
      const existingLabels = new Set<string>();
      el.querySelectorAll<HTMLElement>('[data-mention-label]').forEach(chip => {
        existingLabels.add(chip.dataset.mentionLabel!);
      });
      mentionsRef.current = mentionsRef.current.filter(m => existingLabels.has(m.label));
    });
    chipObserver.observe(el, { childList: true, subtree: true });

    return () => chipObserver.disconnect();
  }, []);

  // Event delegation: handle chip remove button clicks on the editor div
  useEffect(() => {
    const el = editorRef.current;
    if (!el) return;

    const handleClick = (e: MouseEvent) => {
      const target = e.target as HTMLElement;
      if (target.hasAttribute('data-remove')) {
        target.parentElement?.remove();
        fireChange();
      }
    };
    el.addEventListener('click', handleClick);
    return () => el.removeEventListener('click', handleClick);
  }, [fireChange]);

  // Prevent pasting rich HTML — paste as plain text, auto-chip any #TICKET-123 patterns
  const handlePaste = useCallback((e: React.ClipboardEvent) => {
    e.preventDefault();
    const text = e.clipboardData.getData('text/plain');

    // Fast path: no ticket patterns, just insert plain text
    const matches = [...text.matchAll(PASTED_TICKET_PATTERN)];
    if (matches.length === 0) {
      document.execCommand('insertText', false, text);
      return;
    }

    // Split text around ticket patterns and insert chips inline
    const el = editorRef.current;
    const sel = window.getSelection();
    if (!el || !sel || sel.rangeCount === 0) {
      document.execCommand('insertText', false, text);
      return;
    }

    const range = sel.getRangeAt(0);
    range.deleteContents();

    const fragment = document.createDocumentFragment();
    const ticketKeys: string[] = [];
    let lastIndex = 0;

    for (const match of matches) {
      const fullMatch = match[0];       // "#PROJ-123"
      const ticketKey = match[1]!.toUpperCase(); // "PROJ-123"
      const matchStart = match.index!;

      // Text before this match
      if (matchStart > lastIndex) {
        fragment.appendChild(document.createTextNode(text.slice(lastIndex, matchStart)));
      }

      // Create pending chip
      const colors = statusColors['pending'];
      const chip = document.createElement('span');
      chip.contentEditable = 'false';
      chip.dataset.mentionType = 'ticket';
      chip.dataset.mentionLabel = ticketKey;
      chip.dataset.mentionPath = ticketKey;
      chip.dataset.chipStatus = 'pending';
      chip.className = 'inline-flex items-center gap-0.5 rounded px-1 py-0 text-[11px] font-medium mx-0.5 align-baseline transition-colors duration-300';
      chip.style.cssText = `color:${colors.color};background:${colors.bg};border:1px solid ${colors.border};user-select:none;cursor:default;line-height:1.6;`;
      chip.innerHTML = `<span>${ticketKey}</span><button data-remove aria-label="Remove ${ticketKey}" style="opacity:0.6;cursor:pointer;margin-left:2px;font-size:9px;line-height:1;">&times;</button>`;
      fragment.appendChild(chip);

      // Track the mention
      mentionsRef.current.push({ type: 'ticket', label: ticketKey, path: ticketKey });
      ticketKeys.push(ticketKey);

      lastIndex = matchStart + fullMatch.length;
    }

    // Remaining text after last match
    const trailing = lastIndex < text.length ? text.slice(lastIndex) : '\u200B';
    const trailingNode = document.createTextNode(trailing);
    fragment.appendChild(trailingNode);

    range.insertNode(fragment);

    // Move cursor to end of pasted content
    const newRange = document.createRange();
    newRange.setStartAfter(trailingNode);
    newRange.collapse(true);
    sel.removeAllRanges();
    sel.addRange(newRange);

    fireChange();

    // Notify parent to validate each ticket
    if (ticketKeys.length > 0) {
      onPastedTickets?.(ticketKeys);
    }
  }, [fireChange, onPastedTickets]);

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
        disabled && 'opacity-60 cursor-not-allowed bg-[rgba(0,0,0,0.1)]',
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
