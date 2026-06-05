/**
 * #2 — optional per-question `header` short tag.
 * The wizard renders a scannable chip (mirrors AskUserQuestion's `header`) above the
 * question text so multi-step wizards are easier to skim. Absent header → no chip.
 */
import { describe, it, expect, afterEach } from 'vitest';
import { render } from '@testing-library/react';
import type { Question } from '@/bridge/types';
import { QuestionView } from '../components/agent/QuestionView';

afterEach(() => {
  document.body.innerHTML = '';
});

describe('#2 — per-question header tag', () => {
  it('renders the question header as a scannable tag', () => {
    const q: Question = {
      id: 'q1',
      header: 'Database',
      text: 'Which database?',
      type: 'single-select',
      options: [
        { id: 'pg', label: 'Postgres' },
        { id: 'my', label: 'MySQL' },
      ],
    };
    const { getByTestId } = render(<QuestionView questions={[q]} activeIndex={0} />);
    expect(getByTestId('question-header').textContent).toBe('Database');
  });

  it('renders no header tag when header is absent', () => {
    const q: Question = {
      id: 'q1',
      text: 'Which database?',
      type: 'single-select',
      options: [{ id: 'pg', label: 'Postgres' }],
    };
    const { queryByTestId } = render(<QuestionView questions={[q]} activeIndex={0} />);
    expect(queryByTestId('question-header')).toBeNull();
  });
});
