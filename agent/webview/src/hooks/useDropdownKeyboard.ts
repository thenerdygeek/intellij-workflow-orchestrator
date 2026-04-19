import { useState, useCallback, useEffect, useRef } from 'react';

interface UseDropdownKeyboardOptions<T> {
  items: T[];
  onSelect: (item: T) => void;
  onDismiss: () => void;
  isOpen: boolean;
}

/**
 * Hook for keyboard navigation in autocomplete dropdown lists.
 * Manages selectedIndex and a keyDown handler to wire into the parent input.
 *
 * Features:
 * - ArrowUp/Down navigates with wrap-around
 * - Enter selects highlighted item
 * - Tab also selects (like VS Code autocomplete)
 * - Escape dismisses without selecting
 * - Auto-scrolls highlighted item into view
 * - Mouse hover also updates highlight (onMouseEnter)
 * - Resets to 0 when items change or dropdown opens
 *
 * Usage:
 *   const { selectedIndex, setSelectedIndex, listRef, handleKeyDown } =
 *     useDropdownKeyboard({ items, onSelect, onDismiss, isOpen });
 *
 *   // Wire into RichInput:
 *   <RichInput onDropdownKeyDown={handleKeyDown} ... />
 *
 *   // Wire into dropdown list container:
 *   <div ref={listRef}>
 *     {items.map((item, i) => (
 *       <div
 *         data-highlighted={i === selectedIndex ? 'true' : undefined}
 *         onMouseEnter={() => setSelectedIndex(i)}
 *         onClick={() => onSelect(item)}
 *       />
 *     ))}
 *   </div>
 */
export function useDropdownKeyboard<T>({
  items,
  onSelect,
  onDismiss,
  isOpen,
}: UseDropdownKeyboardOptions<T>) {
  const [selectedIndex, setSelectedIndex] = useState(0);
  const listRef = useRef<HTMLDivElement>(null);

  // Reset selection when items change or dropdown opens/closes
  useEffect(() => {
    setSelectedIndex(0);
  }, [items, isOpen]);

  // Auto-scroll selected item into view whenever selectedIndex changes.
  // Uses getBoundingClientRect (viewport coords) instead of scrollIntoView because
  // the list container is position:static — items' offsetParent skips past it to the
  // outer absolute wrapper, causing scrollIntoView to miscalculate scroll deltas.
  useEffect(() => {
    if (!listRef.current) return;
    const list = listRef.current;
    const selected = list.querySelector<HTMLElement>('[data-highlighted="true"]');
    if (!selected) return;
    const listRect = list.getBoundingClientRect();
    const itemRect = selected.getBoundingClientRect();
    if (itemRect.top < listRect.top) {
      list.scrollTop -= listRect.top - itemRect.top;
    } else if (itemRect.bottom > listRect.bottom) {
      list.scrollTop += itemRect.bottom - listRect.bottom;
    }
  }, [selectedIndex]);

  const handleKeyDown = useCallback(
    (e: React.KeyboardEvent | KeyboardEvent): boolean => {
      if (!isOpen) return false;

      switch (e.key) {
        case 'ArrowDown':
          if (items.length === 0) return false;
          e.preventDefault();
          e.stopPropagation();
          setSelectedIndex(prev => (prev + 1) % items.length);
          return true;

        case 'ArrowUp':
          if (items.length === 0) return false;
          e.preventDefault();
          e.stopPropagation();
          setSelectedIndex(prev => (prev - 1 + items.length) % items.length);
          return true;

        case 'Enter':
        case 'Tab':
          // Consume Enter/Tab whenever the dropdown is open — even if items haven't
          // loaded yet. This prevents Enter from falling through to form-submit while
          // the debounced search is still in-flight. If items exist, select; if not,
          // dismiss (user can press Enter again to send the message).
          e.preventDefault();
          e.stopPropagation();
          if (items.length > 0 && items[selectedIndex] !== undefined) {
            onSelect(items[selectedIndex]!);
          } else {
            onDismiss();
          }
          return true;

        case 'Escape':
          e.preventDefault();
          e.stopPropagation();
          onDismiss();
          return true;

        default:
          return false;
      }
    },
    [isOpen, items, selectedIndex, onSelect, onDismiss]
  );

  return { selectedIndex, setSelectedIndex, listRef, handleKeyDown };
}
