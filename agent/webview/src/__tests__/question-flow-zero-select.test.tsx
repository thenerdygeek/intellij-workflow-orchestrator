/**
 * Bug #13 — a multi-select question with ZERO selections is a dead-end.
 *
 * `StepContent.canProceed = selectedIds.size > 0` and `handleNext` early-returns
 * when nothing is selected, with the Next/Complete button `disabled={!canProceed}`.
 * For a MULTI-select, "none of these" is a legitimate answer — but the only way to
 * advance with an empty set was "Skip", which has different semantics (skipped, not
 * answered). Root: conflating "no selection" with "can't proceed".
 *
 * Single-select keeps the old behaviour (you must pick exactly one).
 */
import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, act, fireEvent } from '@testing-library/react';
import { QuestionFlow } from '../components/ui/tool-ui/question-flow';

afterEach(() => { document.body.innerHTML = ''; });

const OPTS = [
  { id: 'a', label: 'Apple' },
  { id: 'b', label: 'Banana' },
];

describe('Bug #13 — empty multi-select is a valid answer', () => {
  it('multi-select Next is enabled with zero selections and submits an empty array', () => {
    const onSelect = vi.fn();
    const { getByRole } = render(
      <QuestionFlow
        id="q1"
        step={1}
        title="Pick fruits"
        options={OPTS}
        selectionMode="multi"
        onSelect={onSelect}
      />
    );

    const next = getByRole('button', { name: /next|complete/i });
    expect(next).not.toBeDisabled();

    act(() => fireEvent.click(next));
    expect(onSelect).toHaveBeenCalledWith([]);
  });

  it('single-select Next stays disabled with zero selections', () => {
    const onSelect = vi.fn();
    const { getByRole } = render(
      <QuestionFlow
        id="q2"
        step={1}
        title="Pick one"
        options={OPTS}
        selectionMode="single"
        onSelect={onSelect}
      />
    );

    const next = getByRole('button', { name: /next|complete/i });
    expect(next).toBeDisabled();

    act(() => fireEvent.click(next));
    expect(onSelect).not.toHaveBeenCalled();
  });
});
