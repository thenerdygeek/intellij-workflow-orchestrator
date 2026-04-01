/**
 * Tests for useDropdownKeyboard hook.
 *
 * Covers:
 * - ArrowDown increments selectedIndex with wrap-around
 * - ArrowUp decrements with wrap-around
 * - Enter calls onSelect with correct item
 * - Tab calls onSelect with correct item (VS Code style)
 * - Escape calls onDismiss
 * - Items change resets selectedIndex to 0
 * - isOpen=false causes all keys to return false (no-op)
 * - Empty items list causes navigation keys to return false
 * - Returns false for unhandled keys (letter keys, etc.)
 */
import { describe, it, expect, vi, beforeEach } from 'vitest';
import { renderHook, act } from '@testing-library/react';
import { useDropdownKeyboard } from '@/hooks/useDropdownKeyboard';

// Helper: build a synthetic React.KeyboardEvent-like object
function makeKeyEvent(key: string): React.KeyboardEvent {
  return {
    key,
    preventDefault: vi.fn(),
    stopPropagation: vi.fn(),
  } as unknown as React.KeyboardEvent;
}

describe('useDropdownKeyboard', () => {
  const items = ['apple', 'banana', 'cherry'];
  let onSelect: ReturnType<typeof vi.fn>;
  let onDismiss: ReturnType<typeof vi.fn>;

  beforeEach(() => {
    onSelect = vi.fn();
    onDismiss = vi.fn();
  });

  // ── Basic navigation ──

  it('ArrowDown increments selectedIndex', () => {
    const { result } = renderHook(() =>
      useDropdownKeyboard({ items, onSelect, onDismiss, isOpen: true })
    );

    expect(result.current.selectedIndex).toBe(0);

    act(() => {
      result.current.handleKeyDown(makeKeyEvent('ArrowDown'));
    });

    expect(result.current.selectedIndex).toBe(1);
  });

  it('ArrowDown wraps from last to first', () => {
    const { result } = renderHook(() =>
      useDropdownKeyboard({ items, onSelect, onDismiss, isOpen: true })
    );

    // Move to last item (index 2)
    act(() => { result.current.handleKeyDown(makeKeyEvent('ArrowDown')); });
    act(() => { result.current.handleKeyDown(makeKeyEvent('ArrowDown')); });
    expect(result.current.selectedIndex).toBe(2);

    // One more wraps to 0
    act(() => { result.current.handleKeyDown(makeKeyEvent('ArrowDown')); });
    expect(result.current.selectedIndex).toBe(0);
  });

  it('ArrowUp decrements selectedIndex', () => {
    const { result } = renderHook(() =>
      useDropdownKeyboard({ items, onSelect, onDismiss, isOpen: true })
    );

    // Move to index 1
    act(() => { result.current.handleKeyDown(makeKeyEvent('ArrowDown')); });
    expect(result.current.selectedIndex).toBe(1);

    // ArrowUp brings back to 0
    act(() => { result.current.handleKeyDown(makeKeyEvent('ArrowUp')); });
    expect(result.current.selectedIndex).toBe(0);
  });

  it('ArrowUp wraps from first to last', () => {
    const { result } = renderHook(() =>
      useDropdownKeyboard({ items, onSelect, onDismiss, isOpen: true })
    );

    expect(result.current.selectedIndex).toBe(0);
    act(() => { result.current.handleKeyDown(makeKeyEvent('ArrowUp')); });
    expect(result.current.selectedIndex).toBe(items.length - 1); // 2
  });

  // ── Selection ──

  it('Enter calls onSelect with the highlighted item', () => {
    const { result } = renderHook(() =>
      useDropdownKeyboard({ items, onSelect, onDismiss, isOpen: true })
    );

    // Move to 'banana' (index 1)
    act(() => { result.current.handleKeyDown(makeKeyEvent('ArrowDown')); });
    act(() => { result.current.handleKeyDown(makeKeyEvent('Enter')); });

    expect(onSelect).toHaveBeenCalledTimes(1);
    expect(onSelect).toHaveBeenCalledWith('banana');
  });

  it('Enter calls onSelect with first item when at default index 0', () => {
    const { result } = renderHook(() =>
      useDropdownKeyboard({ items, onSelect, onDismiss, isOpen: true })
    );

    act(() => { result.current.handleKeyDown(makeKeyEvent('Enter')); });
    expect(onSelect).toHaveBeenCalledWith('apple');
  });

  it('Tab calls onSelect like Enter (VS Code autocomplete behavior)', () => {
    const { result } = renderHook(() =>
      useDropdownKeyboard({ items, onSelect, onDismiss, isOpen: true })
    );

    // Move to 'cherry' (index 2)
    act(() => { result.current.handleKeyDown(makeKeyEvent('ArrowDown')); });
    act(() => { result.current.handleKeyDown(makeKeyEvent('ArrowDown')); });
    act(() => { result.current.handleKeyDown(makeKeyEvent('Tab')); });

    expect(onSelect).toHaveBeenCalledWith('cherry');
  });

  // ── Dismiss ──

  it('Escape calls onDismiss', () => {
    const { result } = renderHook(() =>
      useDropdownKeyboard({ items, onSelect, onDismiss, isOpen: true })
    );

    const e = makeKeyEvent('Escape');
    act(() => { result.current.handleKeyDown(e); });

    expect(onDismiss).toHaveBeenCalledTimes(1);
    expect(onSelect).not.toHaveBeenCalled();
  });

  // ── Return values ──

  it('handled keys return true', () => {
    const { result } = renderHook(() =>
      useDropdownKeyboard({ items, onSelect, onDismiss, isOpen: true })
    );

    let ret: boolean;
    act(() => { ret = result.current.handleKeyDown(makeKeyEvent('ArrowDown')); });
    expect(ret!).toBe(true);

    act(() => { ret = result.current.handleKeyDown(makeKeyEvent('Enter')); });
    expect(ret!).toBe(true);

    act(() => { ret = result.current.handleKeyDown(makeKeyEvent('Escape')); });
    expect(ret!).toBe(true);
  });

  it('unhandled keys return false', () => {
    const { result } = renderHook(() =>
      useDropdownKeyboard({ items, onSelect, onDismiss, isOpen: true })
    );

    let ret: boolean;
    act(() => { ret = result.current.handleKeyDown(makeKeyEvent('a')); });
    expect(ret!).toBe(false);

    act(() => { ret = result.current.handleKeyDown(makeKeyEvent('Backspace')); });
    expect(ret!).toBe(false);

    act(() => { ret = result.current.handleKeyDown(makeKeyEvent('Space')); });
    expect(ret!).toBe(false);
  });

  // ── isOpen guard ──

  it('returns false for all keys when isOpen is false', () => {
    const { result } = renderHook(() =>
      useDropdownKeyboard({ items, onSelect, onDismiss, isOpen: false })
    );

    let ret: boolean;
    act(() => { ret = result.current.handleKeyDown(makeKeyEvent('ArrowDown')); });
    expect(ret!).toBe(false);

    act(() => { ret = result.current.handleKeyDown(makeKeyEvent('Enter')); });
    expect(ret!).toBe(false);

    act(() => { ret = result.current.handleKeyDown(makeKeyEvent('Escape')); });
    expect(ret!).toBe(false);

    expect(onSelect).not.toHaveBeenCalled();
    expect(onDismiss).not.toHaveBeenCalled();
  });

  // ── Empty items guard ──

  it('returns false for navigation keys when items is empty', () => {
    const { result } = renderHook(() =>
      useDropdownKeyboard({ items: [], onSelect, onDismiss, isOpen: true })
    );

    let ret: boolean;
    act(() => { ret = result.current.handleKeyDown(makeKeyEvent('ArrowDown')); });
    expect(ret!).toBe(false);

    act(() => { ret = result.current.handleKeyDown(makeKeyEvent('Enter')); });
    expect(ret!).toBe(false);
  });

  // ── Event prevention ──

  it('calls preventDefault and stopPropagation on handled keys', () => {
    const { result } = renderHook(() =>
      useDropdownKeyboard({ items, onSelect, onDismiss, isOpen: true })
    );

    const e = makeKeyEvent('ArrowDown');
    act(() => { result.current.handleKeyDown(e); });

    expect(e.preventDefault).toHaveBeenCalled();
    expect(e.stopPropagation).toHaveBeenCalled();
  });

  it('does NOT call preventDefault for unhandled keys', () => {
    const { result } = renderHook(() =>
      useDropdownKeyboard({ items, onSelect, onDismiss, isOpen: true })
    );

    const e = makeKeyEvent('a');
    act(() => { result.current.handleKeyDown(e); });

    expect(e.preventDefault).not.toHaveBeenCalled();
  });

  // ── selectedIndex reset ──

  it('resets selectedIndex to 0 when items change', () => {
    const initialItems = ['x', 'y', 'z'];
    const { result, rerender } = renderHook(
      ({ items: i }: { items: string[] }) =>
        useDropdownKeyboard({ items: i, onSelect, onDismiss, isOpen: true }),
      { initialProps: { items: initialItems } }
    );

    // Move to index 2
    act(() => { result.current.handleKeyDown(makeKeyEvent('ArrowDown')); });
    act(() => { result.current.handleKeyDown(makeKeyEvent('ArrowDown')); });
    expect(result.current.selectedIndex).toBe(2);

    // New items arrive → selectedIndex resets
    rerender({ items: ['a', 'b'] });
    expect(result.current.selectedIndex).toBe(0);
  });

  it('resets selectedIndex to 0 when isOpen changes to true', () => {
    const { result, rerender } = renderHook(
      ({ open }: { open: boolean }) =>
        useDropdownKeyboard({ items, onSelect, onDismiss, isOpen: open }),
      { initialProps: { open: true } }
    );

    act(() => { result.current.handleKeyDown(makeKeyEvent('ArrowDown')); });
    expect(result.current.selectedIndex).toBe(1);

    // Close then reopen
    rerender({ open: false });
    rerender({ open: true });
    expect(result.current.selectedIndex).toBe(0);
  });

  // ── setSelectedIndex (mouse hover) ──

  it('exposes setSelectedIndex for mouse hover integration', () => {
    const { result } = renderHook(() =>
      useDropdownKeyboard({ items, onSelect, onDismiss, isOpen: true })
    );

    act(() => { result.current.setSelectedIndex(2); });
    expect(result.current.selectedIndex).toBe(2);

    // Enter should then select 'cherry'
    act(() => { result.current.handleKeyDown(makeKeyEvent('Enter')); });
    expect(onSelect).toHaveBeenCalledWith('cherry');
  });
});
