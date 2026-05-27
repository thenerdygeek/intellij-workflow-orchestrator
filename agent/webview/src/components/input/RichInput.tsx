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

/** Matches #PROJ-123 style ticket references in pasted text (alphanumeric project keys like TICKET8IN-12345) */
const PASTED_TICKET_PATTERN = /#([A-Za-z][A-Za-z0-9]+-\d+)/g;

/** Consecutive typing changes within this window collapse into one undo step. */
const UNDO_COALESCE_MS = 400;
/** Cap on retained undo snapshots (oldest dropped beyond this). */
const UNDO_MAX_HISTORY = 100;

/** A point-in-time snapshot of the editor used by the undo/redo stack. */
interface InputSnapshot {
  html: string;
  mentions: Mention[];
}

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
  /**
   * Phase 5: invoked when an image file is pasted (e.g. "paste error
   * screenshot from Snipping Tool"). Returning true means the handler
   * accepted the image — RichInput then suppresses the existing text path
   * for this paste event. Returning false (or absent) lets the regular
   * text/plain path run unchanged.
   */
  onPasteImage?: (file: File) => Promise<boolean>;
  /**
   * Optional ghost-text suggestion. When the input is empty, this string is
   * shown in place of `placeholder` (using the same faded `:empty:before`
   * styling — cursor stays at offset 0 because the pseudo-element is
   * non-interactive). Pressing Right Arrow on an empty input invokes
   * `onAcceptHint`; the parent is expected to call `setText(hint)` to
   * promote the hint into real input.
   */
  hint?: string | null;
  /** Called when the user presses Right Arrow on an empty input that has a hint. */
  onAcceptHint?: () => void;
}

/**
 * Contenteditable input that supports inline mention chips.
 * Chips are rendered as non-editable <span> elements within the editable div.
 * Text and chips flow naturally together on the same line.
 */
export const RichInput = forwardRef<RichInputHandle, RichInputProps>(function RichInput(
  { placeholder, disabled, className, onSubmit, onChange, onEscape, onDropdownKeyDown, onPastedTickets, onPasteImage, hint, onAcceptHint },
  ref
) {
  const editorRef = useRef<HTMLDivElement>(null);
  const mentionsRef = useRef<Mention[]>([]);

  // ── Undo/redo history ──
  // Native contentEditable undo is unreliable once we mutate the DOM directly (chips, paste,
  // setText), so RichInput owns an explicit stack — the same approach Lexical/ProseMirror take.
  const undoStackRef = useRef<InputSnapshot[]>([]);
  const redoStackRef = useRef<InputSnapshot[]>([]);
  const lastRecordRef = useRef<{ time: number; coalescible: boolean }>({ time: 0, coalescible: false });

  const captureSnapshot = useCallback((): InputSnapshot => ({
    html: editorRef.current?.innerHTML ?? '',
    mentions: [...mentionsRef.current],
  }), []);

  /**
   * Push the current editor state onto the undo stack. `coalesce` (set for plain typing) folds
   * consecutive changes within [UNDO_COALESCE_MS] into one step by replacing the top entry; any
   * non-coalescing change (chip, paste, setText) always starts a fresh step. Recording clears the
   * redo stack — a new edit invalidates the redo future.
   */
  const recordHistory = useCallback((coalesce: boolean) => {
    if (!editorRef.current) return;
    const now = Date.now();
    const snap = captureSnapshot();
    const stack = undoStackRef.current;
    const canCoalesce =
      coalesce &&
      lastRecordRef.current.coalescible &&
      now - lastRecordRef.current.time < UNDO_COALESCE_MS &&
      stack.length > 0;
    if (canCoalesce) {
      stack[stack.length - 1] = snap;
    } else {
      stack.push(snap);
      if (stack.length > UNDO_MAX_HISTORY) stack.shift();
    }
    redoStackRef.current = [];
    lastRecordRef.current = { time: now, coalescible: coalesce };
  }, [captureSnapshot]);

  /** Reset history to a single baseline snapshot (used on mount and after clear()). */
  const resetHistory = useCallback(() => {
    undoStackRef.current = [captureSnapshot()];
    redoStackRef.current = [];
    lastRecordRef.current = { time: 0, coalescible: false };
  }, [captureSnapshot]);

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
    recordHistory(false);
  }, [recordHistory]);

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
        // A cleared input (post-submit) is a fresh draft — drop history so Ctrl+Z can't
        // resurrect the sent message.
        resetHistory();
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
      recordHistory(false);
    },
    getText: () => extractText(),
    getMentions: () => {
      // Only return valid/pending mentions. Invalid (red) chips are excluded —
      // they get rendered as plain text by getText() instead.
      const el = editorRef.current;
      if (!el) return [...mentionsRef.current];
      const validMentions: Mention[] = [];
      // Dedup by label: two chips can share a label (e.g. a pasted pending chip
      // plus a dropdown-selected valid chip for the same ticket), which would
      // otherwise emit the same mention twice into the send payload.
      const seen = new Set<string>();
      const chipEls = el.querySelectorAll<HTMLElement>('[data-mention-label]');
      chipEls.forEach(chip => {
        const status = chip.dataset.chipStatus;
        const label = chip.dataset.mentionLabel;
        if (status !== 'invalid' && label && !seen.has(label)) {
          const mention = mentionsRef.current.find(m => m.label === label);
          if (mention) {
            validMentions.push(mention);
            seen.add(label);
          }
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
    const labelSpan = document.createElement('span');
    labelSpan.textContent = mention.label;
    chip.appendChild(labelSpan);
    const removeBtn = document.createElement('button');
    removeBtn.setAttribute('data-remove', '');
    removeBtn.setAttribute('aria-label', `Remove ${mention.label}`);
    removeBtn.style.cssText = 'opacity:0.6;cursor:pointer;margin-left:2px;font-size:9px;line-height:1;';
    removeBtn.textContent = '×';
    chip.appendChild(removeBtn);

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

    // When a chip is inserted directly in the valid state (e.g. from the ticket dropdown,
    // where the ticket was already validated by the backend), fire the same success glow
    // that updateChipStatus uses. Otherwise the dropdown path gives no visual confirmation.
    if (mention.type === 'ticket' && status === 'valid') {
      ensureGlowStyle();
      chip.style.animation = 'chip-success-glow 0.8s ease-out';
      chip.addEventListener('animationend', () => {
        chip.style.animation = '';
      }, { once: true });
    }

    // Notify parent
    fireChange();
    recordHistory(false);
    // NOTE: fireChange is declared below this callback, so it must not appear in the dep array
    // (the array is evaluated eagerly at render → TDZ). It's referenced as a stable closure.
  }, [recordHistory]);

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

  // ── Undo/redo apply + commands ──

  /** Restore a snapshot: content, mentions, and a caret parked at the end (predictable). */
  const applySnapshot = useCallback((snap: InputSnapshot) => {
    const el = editorRef.current;
    if (!el) return;
    el.innerHTML = snap.html;
    mentionsRef.current = [...snap.mentions];
    const range = document.createRange();
    range.selectNodeContents(el);
    range.collapse(false);
    const sel = window.getSelection();
    sel?.removeAllRanges();
    sel?.addRange(range);
    updateEmptyState();
    fireChange();
  }, [updateEmptyState, fireChange]);

  const undo = useCallback((): boolean => {
    const stack = undoStackRef.current;
    if (stack.length <= 1) return false; // only the baseline remains — nothing to undo
    const current = stack.pop()!;
    redoStackRef.current.push(current);
    applySnapshot(stack[stack.length - 1]!);
    lastRecordRef.current = { time: 0, coalescible: false }; // break the coalesce chain
    return true;
  }, [applySnapshot]);

  const redo = useCallback((): boolean => {
    const redoStack = redoStackRef.current;
    if (redoStack.length === 0) return false;
    const snap = redoStack.pop()!;
    undoStackRef.current.push(snap);
    applySnapshot(snap);
    lastRecordRef.current = { time: 0, coalescible: false };
    return true;
  }, [applySnapshot]);

  // ── Event handlers ──

  const handleInput = useCallback(() => {
    fireChange();
    updateEmptyState();
    recordHistory(true);
  }, [fireChange, updateEmptyState, recordHistory]);

  const handleKeyDown = useCallback((e: React.KeyboardEvent<HTMLDivElement>) => {
    // Give the active dropdown first refusal on navigation keys.
    // If it returns true the event is fully consumed — don't submit or escape.
    if (onDropdownKeyDown?.(e)) return;

    // Undo / redo. We own the history because direct DOM mutation (chips, paste, setText)
    // desyncs the browser's native contentEditable undo. Always preventDefault so the keystroke
    // never escapes to JCEF / the IDE — even when there is nothing left to (re)do.
    const isMod = e.ctrlKey || e.metaKey;
    if (isMod && (e.key === 'z' || e.key === 'Z')) {
      e.preventDefault();
      if (e.shiftKey) redo(); else undo();
      return;
    }
    if (isMod && !e.shiftKey && (e.key === 'y' || e.key === 'Y')) {
      e.preventDefault();
      redo();
      return;
    }

    // Right-arrow accept for the ghost-text hint. Only fires when the input
    // is empty (no text, no chips) and a hint is present; otherwise this is
    // a real cursor-move and we leave it alone.
    if (e.key === 'ArrowRight' && hint && onAcceptHint) {
      const el = editorRef.current;
      const isEmpty = !!el
        && (el.textContent?.length ?? 0) === 0
        && el.querySelectorAll('[data-mention-label]').length === 0;
      if (isEmpty) {
        e.preventDefault();
        onAcceptHint();
        return;
      }
    }

    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      onSubmit?.();
    }
    if (e.key === 'Escape') {
      onEscape?.();
    }
  }, [onSubmit, onEscape, onDropdownKeyDown, hint, onAcceptHint, undo, redo]);

  // Seed history with the initial (empty) state so the first edit has a baseline to undo to.
  useEffect(() => {
    resetHistory();
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, []);

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
        recordHistory(false);
      }
    };
    el.addEventListener('click', handleClick);
    return () => el.removeEventListener('click', handleClick);
  }, [fireChange, recordHistory]);

  // Prevent pasting rich HTML — paste as plain text, auto-chip any #TICKET-123 patterns
  const handlePaste = useCallback((e: React.ClipboardEvent) => {
    e.preventDefault();

    // Phase 5: image paste detection — runs BEFORE the text/plain path so
    // a clipboard with both text and image (some screenshot tools emit
    // both) prefers the image. The handler must run inside this same
    // handlePaste — preventDefault has already fired above so a sibling
    // listener would never see the event.
    if (onPasteImage) {
      const items = Array.from(e.clipboardData.items ?? []);
      const imageItem = items.find(i => i.kind === 'file' && i.type.startsWith('image/'));
      if (imageItem) {
        const file = imageItem.getAsFile();
        if (file) {
          // Fire-and-forget — onPasteImage manages its own toast/error path.
          // We intentionally do NOT fall through to the text path, even if
          // attachFile rejects, because falling through would paste image
          // metadata or empty text in confusing ways.
          void onPasteImage(file);
          return;
        }
      }
    }

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

    // If the caret sits right after an in-progress "#…" ticket trigger the user
    // was typing (e.g. they typed "#" or "#PROJ-12" then pasted a ticket), consume
    // that partial trigger — the pasted chip replaces it. Without this the typed
    // "#" is orphaned in front of the chip and extractText emits "##PROJ-123".
    const caretNode = range.startContainer;
    if (caretNode.nodeType === Node.TEXT_NODE && range.collapsed) {
      const beforeCaret = (caretNode.textContent ?? '').slice(0, range.startOffset);
      const trigMatch = beforeCaret.match(/#\S*$/);
      if (trigMatch) {
        range.setStart(caretNode, range.startOffset - trigMatch[0].length);
      }
    }
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
      const ticketLabelSpan = document.createElement('span');
      ticketLabelSpan.textContent = ticketKey;
      chip.appendChild(ticketLabelSpan);
      const ticketRemoveBtn = document.createElement('button');
      ticketRemoveBtn.setAttribute('data-remove', '');
      ticketRemoveBtn.setAttribute('aria-label', `Remove ${ticketKey}`);
      ticketRemoveBtn.style.cssText = 'opacity:0.6;cursor:pointer;margin-left:2px;font-size:9px;line-height:1;';
      ticketRemoveBtn.textContent = '×';
      chip.appendChild(ticketRemoveBtn);
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
    // Pasting chips mutates the DOM directly (no input event), so record a discrete step.
    recordHistory(false);

    // Notify parent to validate each ticket
    if (ticketKeys.length > 0) {
      onPastedTickets?.(ticketKeys);
    }
  }, [fireChange, onPastedTickets, onPasteImage, recordHistory]);

  // When a hint is present, surface it as the empty-state placeholder. The
  // underlying CSS uses `:empty:before:content-[attr(data-placeholder)]`,
  // which is non-interactive — the cursor stays at offset 0 (no actual
  // children), so the hint reads exactly like ghost-text. `data-hint-active`
  // lets parents distinguish via `[data-hint-active="true"]` if they want to
  // restyle (e.g., add a ▶ glyph).
  const hasHint = !!(hint && hint.length > 0);
  const effectivePlaceholder = hasHint ? hint! : placeholder;

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
      data-placeholder={effectivePlaceholder}
      data-hint-active={hasHint ? 'true' : 'false'}
      aria-label={hasHint ? `Suggested next message: ${hint}. Press Right Arrow to accept.` : undefined}
      className={cn(
        'rich-input text-[13px] leading-relaxed outline-none min-h-[28px] w-full',
        'empty:before:content-[attr(data-placeholder)] empty:before:text-[var(--fg-muted,#6b7280)] empty:before:pointer-events-none',
        hasHint && 'empty:before:italic',
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
