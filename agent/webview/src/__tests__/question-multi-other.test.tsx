/**
 * Multi-select + "Other" coexistence.
 *
 * For a multi-select question the user may legitimately want to keep checked
 * options AND add a free-text "Other" value. The two submit paths each called
 * storeAnswer with only their own slice — `handleSelect` stored just the option
 * ids (dropping Other) and `handleCustomSubmit` stored just the custom text
 * (dropping the checkboxes). They must UNION for multi-select.
 *
 * Single-select keeps its mutually-exclusive (replace) semantics.
 */
import { describe, it, expect, vi, afterEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import type { Question } from '@/bridge/types';
import { QuestionView } from '../components/agent/QuestionView';

afterEach(() => {
  document.body.innerHTML = '';
  delete (window as any)._questionAnswered;
});

const multiQ: Question = {
  id: 'q-m',
  text: 'Pick toppings',
  type: 'multi-select',
  options: [
    { id: 'a', label: 'Cheese' },
    { id: 'b', label: 'Mushroom' },
  ],
};

const singleQ: Question = {
  id: 'q-s',
  text: 'Pick one',
  type: 'single-select',
  options: [{ id: 'a', label: 'Cheese' }],
};

function lastAnswer(answered: ReturnType<typeof vi.fn>): string[] {
  const calls = answered.mock.calls;
  return JSON.parse(calls[calls.length - 1]![1]);
}

describe('multi-select + Other coexistence', () => {
  it('unions checked options with the Other value when submitting via Next', () => {
    const answered = vi.fn();
    (window as any)._questionAnswered = answered;
    render(<QuestionView questions={[multiQ]} activeIndex={0} />);

    fireEvent.click(screen.getByRole('option', { name: /Cheese/ }));
    fireEvent.click(screen.getByRole('option', { name: /Mushroom/ }));
    fireEvent.click(screen.getByText('Other'));
    fireEvent.change(screen.getByPlaceholderText(/type a custom answer/i), { target: { value: 'Olives' } });
    fireEvent.click(screen.getByRole('button', { name: /next|complete/i }));

    expect(lastAnswer(answered)).toEqual(['a', 'b', 'Olives']);
  });

  it('unions checked options with the Other value when submitting via the Other Submit button', () => {
    const answered = vi.fn();
    (window as any)._questionAnswered = answered;
    render(<QuestionView questions={[multiQ]} activeIndex={0} />);

    fireEvent.click(screen.getByRole('option', { name: /Cheese/ }));
    fireEvent.click(screen.getByText('Other'));
    fireEvent.change(screen.getByPlaceholderText(/type a custom answer/i), { target: { value: 'Olives' } });
    fireEvent.click(screen.getByRole('button', { name: /^submit$/i }));

    expect(lastAnswer(answered)).toEqual(['a', 'Olives']);
  });

  it('single-select: Other replaces the option selection (unchanged)', () => {
    const answered = vi.fn();
    (window as any)._questionAnswered = answered;
    render(<QuestionView questions={[singleQ]} activeIndex={0} />);

    fireEvent.click(screen.getByText('Other'));
    fireEvent.change(screen.getByPlaceholderText(/type a custom answer/i), { target: { value: 'Custom' } });
    fireEvent.click(screen.getByRole('button', { name: /^submit$/i }));

    expect(lastAnswer(answered)).toEqual(['Custom']);
  });
});
