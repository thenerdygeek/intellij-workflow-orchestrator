/**
 * Forward/back navigation of the ask_questions wizard.
 *
 * Bug: nothing advanced `activeQuestionIndex`. answerQuestion/skipQuestion don't
 * touch it, no component called store.showQuestion, and the Kotlin side only
 * collects answers (the _editQuestion bridge is a no-op). So a multi-question
 * wizard was stuck on Q0 — you could answer the first question but never reach
 * the rest. Back was broken for the same reason.
 *
 * These render a store-connected harness (like ChatFooter) so navigation that
 * flows through the store is observable.
 */
import { describe, it, expect, vi, beforeEach, afterEach } from 'vitest';
import { render, screen, fireEvent, act } from '@testing-library/react';
import type { Question } from '@/bridge/types';
import { QuestionView } from '../components/agent/QuestionView';
import { useChatStore } from '@/stores/chatStore';

const bridges = ['_questionAnswered', '_questionSkipped', '_editQuestion'] as const;

beforeEach(() => {
  for (const b of bridges) (window as any)[b] = vi.fn();
  act(() => useChatStore.getState().clearChat?.());
});
afterEach(() => {
  document.body.innerHTML = '';
  for (const b of bridges) delete (window as any)[b];
});

const opts = [{ id: 'a', label: 'Apple' }, { id: 'b', label: 'Banana' }];
const q = (id: string): Question => ({ id, text: `Question ${id}`, type: 'single-select', options: opts });

// Store-connected wrapper mirroring ChatFooter's wiring.
function Harness() {
  const questions = useChatStore(s => s.questions);
  const activeIndex = useChatStore(s => s.activeQuestionIndex);
  if (!questions) return null;
  return <QuestionView questions={questions} activeIndex={activeIndex} />;
}

describe('question wizard navigation', () => {
  it('answering a non-last question advances to the next', () => {
    act(() => useChatStore.getState().showQuestions([q('q0'), q('q1')]));
    render(<Harness />);
    expect(screen.getByText('Question q0')).toBeInTheDocument();

    fireEvent.click(screen.getByRole('option', { name: /Apple/ }));
    fireEvent.click(screen.getByRole('button', { name: /next|complete/i }));

    expect(useChatStore.getState().activeQuestionIndex).toBe(1);
    expect(screen.getByText('Question q1')).toBeInTheDocument();
  });

  it('skipping a non-last question advances to the next', () => {
    act(() => useChatStore.getState().showQuestions([q('q0'), q('q1')]));
    render(<Harness />);

    fireEvent.click(screen.getByRole('button', { name: /skip/i }));

    expect(useChatStore.getState().activeQuestionIndex).toBe(1);
    expect(screen.getByText('Question q1')).toBeInTheDocument();
  });

  it('Back from a later question returns to the previous one', () => {
    act(() => useChatStore.getState().showQuestions([q('q0'), q('q1')]));
    render(<Harness />);
    // Advance to q1.
    fireEvent.click(screen.getByRole('option', { name: /Apple/ }));
    fireEvent.click(screen.getByRole('button', { name: /next|complete/i }));
    expect(screen.getByText('Question q1')).toBeInTheDocument();

    // Go back.
    fireEvent.click(screen.getByRole('button', { name: /back/i }));
    expect(useChatStore.getState().activeQuestionIndex).toBe(0);
    expect(screen.getByText('Question q0')).toBeInTheDocument();
  });

  it('answering the last question shows the review summary (no overflow)', () => {
    act(() => useChatStore.getState().showQuestions([q('q0'), q('q1')]));
    render(<Harness />);
    // q0 → q1
    fireEvent.click(screen.getByRole('option', { name: /Apple/ }));
    fireEvent.click(screen.getByRole('button', { name: /next|complete/i }));
    // q1 (last) → summary
    fireEvent.click(screen.getByRole('option', { name: /Banana/ }));
    fireEvent.click(screen.getByRole('button', { name: /next|complete/i }));

    expect(screen.getByText('Review Your Answers')).toBeInTheDocument();
    expect(useChatStore.getState().activeQuestionIndex).toBe(1); // did not advance past the end
  });
});
