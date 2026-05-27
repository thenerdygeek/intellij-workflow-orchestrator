/**
 * Interaction coverage for the ask_questions wizard (QuestionView), beyond the
 * specific bug fixes. Scenarios:
 *   1. Skip wiring
 *   2. Cancel wiring (clears the wizard)
 *   3. Submit All from the review summary
 *   4. Text-question answering
 *   5. Editing an answered question shows its prior answer (defaultValue prefill)
 *   6. The review summary renders each answer correctly
 *   7. "Edit answer" from the summary actually navigates back to that question
 *      (was a dead button — summary was sticky because the guard OR'd allAnswered)
 *   8. Re-answering during an edit returns to the summary
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, within, act } from '@testing-library/react';
import type { Question } from '@/bridge/types';
import { QuestionView } from '../components/agent/QuestionView';
import { useChatStore } from '@/stores/chatStore';

const bridges = ['_questionAnswered', '_questionSkipped', '_questionsCancelled', '_questionsSubmitted', '_editQuestion'] as const;

beforeEach(() => {
  for (const b of bridges) (window as any)[b] = vi.fn();
  act(() => useChatStore.getState().clearChat?.());
});
afterEach(() => {
  document.body.innerHTML = '';
  for (const b of bridges) delete (window as any)[b];
});

const opts = [{ id: 'a', label: 'Apple' }, { id: 'b', label: 'Banana' }];
const q = (id: string, extra: Partial<Question> = {}): Question => ({
  id, text: `Question ${id}`, type: 'single-select', options: opts, ...extra,
});

describe('QuestionView interactions', () => {
  it('1. Skip notifies the bridge with the question id', () => {
    render(<QuestionView questions={[q('q0'), q('q1')]} activeIndex={0} />);
    fireEvent.click(screen.getByRole('button', { name: /skip/i }));
    expect((window as any)._questionSkipped).toHaveBeenCalledWith('q0');
  });

  it('2. Cancel clears the wizard and notifies the bridge', () => {
    act(() => useChatStore.getState().showQuestions([q('q0')]));
    render(<QuestionView questions={[q('q0')]} activeIndex={0} />);
    fireEvent.click(screen.getByRole('button', { name: /cancel/i }));
    expect((window as any)._questionsCancelled).toHaveBeenCalledTimes(1);
    expect(useChatStore.getState().questions).toBeNull();
  });

  it('3. Submit All from the review summary notifies the bridge', () => {
    // All answered → the review summary renders.
    render(<QuestionView questions={[q('q0', { answer: ['a'] })]} activeIndex={0} />);
    expect(screen.getByText('Review Your Answers')).toBeInTheDocument();
    fireEvent.click(screen.getByRole('button', { name: /submit all/i }));
    expect((window as any)._questionsSubmitted).toHaveBeenCalledTimes(1);
  });

  it('4. Text question records the typed answer', () => {
    render(<QuestionView questions={[q('qt', { type: 'text', options: [] }), q('q1')]} activeIndex={0} />);
    const textarea = screen.getByPlaceholderText(/type your answer/i);
    fireEvent.change(textarea, { target: { value: 'my free text' } });
    fireEvent.click(screen.getByRole('button', { name: /submit/i }));
    expect((window as any)._questionAnswered).toHaveBeenCalledWith('qt', JSON.stringify(['my free text']));
  });

  it('5. Editing an answered question shows its prior selection (defaultValue prefill)', () => {
    // Q0 answered, Q1 not → Q0 card is shown with its answer pre-selected.
    render(<QuestionView questions={[q('q0', { answer: ['b'] }), q('q1')]} activeIndex={0} />);
    expect(screen.getByRole('option', { name: /Banana/ })).toHaveAttribute('aria-selected', 'true');
    expect(screen.getByRole('option', { name: /Apple/ })).toHaveAttribute('aria-selected', 'false');
  });

  it('6. The review summary renders each answer as labels', () => {
    render(<QuestionView questions={[q('q0', { answer: ['a'] }), q('q1', { skipped: true })]} activeIndex={1} />);
    expect(screen.getByText('Apple')).toBeInTheDocument();
    expect(screen.getByText('Skipped')).toBeInTheDocument();
  });

  it('7. "Edit answer" from the summary navigates back to that question', () => {
    render(<QuestionView questions={[q('q0', { answer: ['a'] }), q('q1', { answer: ['b'] })]} activeIndex={1} />);
    expect(screen.getByText('Review Your Answers')).toBeInTheDocument();

    // Click the pencil for the FIRST question.
    const editButtons = screen.getAllByRole('button', { name: /edit answer/i });
    fireEvent.click(editButtons[0]!);

    // We must leave the summary and land on Q0's editable card.
    expect(screen.queryByText('Review Your Answers')).toBeNull();
    expect(screen.getByText('Question q0')).toBeInTheDocument();
    // Its prior answer is still selected.
    expect(screen.getByRole('option', { name: /Apple/ })).toHaveAttribute('aria-selected', 'true');
  });

  it('8. re-answering during an edit returns to the summary', () => {
    render(<QuestionView questions={[q('q0', { answer: ['a'] }), q('q1', { answer: ['b'] })]} activeIndex={1} />);
    fireEvent.click(screen.getAllByRole('button', { name: /edit answer/i })[0]!);

    // Change Q0's answer and submit via the question's Next button.
    fireEvent.click(screen.getByRole('option', { name: /Banana/ }));
    fireEvent.click(screen.getByRole('button', { name: /next|complete/i }));

    // Back on the review summary.
    expect(screen.getByText('Review Your Answers')).toBeInTheDocument();
  });
});
