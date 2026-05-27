/**
 * QuestionView cluster:
 *   #17 — "Chat about this" is a per-QUESTION button but sent `options[0]` as the
 *         subject. It must reference the QUESTION, not an arbitrary first option.
 *   #5  — the 300ms "show summary" timer scheduled on answering the last question
 *         was never cleared. Navigating away (Back) before it fires flashes the
 *         summary against a question that isn't fully answered.
 */
import { describe, it, expect, vi, afterEach, beforeEach } from 'vitest';
import { render, act, fireEvent } from '@testing-library/react';
import type { Question } from '@/bridge/types';
import { QuestionView } from '../components/agent/QuestionView';

afterEach(() => {
  document.body.innerHTML = '';
  delete (window as any)._chatAboutOption;
  delete (window as any)._editQuestion;
  delete (window as any)._questionAnswered;
});

const fruitQuestion: Question = {
  id: 'q-fruit',
  text: 'Which deployment target?',
  type: 'single-select',
  options: [
    { id: 'a', label: 'Staging' },
    { id: 'b', label: 'Production' },
  ],
};

describe('Bug #17 — "Chat about this" references the question, not options[0]', () => {
  it('sends the question id + question text, not the first option label', () => {
    const chatAbout = vi.fn();
    (window as any)._chatAboutOption = chatAbout;

    const { getByText, getByPlaceholderText, getByRole } = render(
      <QuestionView questions={[fruitQuestion]} activeIndex={0} />
    );

    act(() => fireEvent.click(getByText('Chat about this')));
    const input = getByPlaceholderText(/what would you like to know/i);
    act(() => fireEvent.change(input, { target: { value: 'why these two?' } }));
    act(() => fireEvent.click(getByRole('button', { name: /^ask$/i })));

    expect(chatAbout).toHaveBeenCalledTimes(1);
    const [qid, subject, msg] = chatAbout.mock.calls[0]!;
    expect(qid).toBe('q-fruit');
    expect(msg).toBe('why these two?');
    // The subject must be the QUESTION, never the arbitrary first option.
    expect(subject).not.toBe('Staging');
    expect(subject).toBe('Which deployment target?');
  });
});

describe('Bug #5 — the show-summary timer is cancelled on navigation', () => {
  beforeEach(() => vi.useFakeTimers());
  afterEach(() => vi.useRealTimers());

  it('does not flash the summary after Back is pressed within the 300ms window', () => {
    (window as any)._editQuestion = vi.fn();
    const questions: Question[] = [
      { id: 'q0', text: 'First?', type: 'single-select', options: [{ id: 'x', label: 'X' }], answer: ['x'] },
      { id: 'q1', text: 'Last?', type: 'single-select', options: [{ id: 'y', label: 'Y' }, { id: 'z', label: 'Z' }] },
    ];

    const { getByRole, queryByText } = render(
      <QuestionView questions={questions} activeIndex={1} />
    );

    // Answer the last question → schedules the 300ms summary timer.
    act(() => fireEvent.click(getByRole('option', { name: /^Y$/ })));
    act(() => fireEvent.click(getByRole('button', { name: /next|complete/i })));

    // Navigate Back before the timer fires.
    act(() => fireEvent.click(getByRole('button', { name: /back/i })));

    // Fire the pending timer.
    act(() => vi.advanceTimersByTime(400));

    // The summary must NOT appear — q1 is not recorded as answered in the props.
    expect(queryByText('Review Your Answers')).toBeNull();
  });
});
