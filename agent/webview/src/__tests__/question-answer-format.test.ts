/**
 * Bug #18 — answer→label serialization was duplicated in
 * `finalizeQuestionsAsMessage` (chatStore) and the QuestionView summary page,
 * with byte-identical logic that could drift. Unified into one helper so the
 * answered-question receipt has a single source of truth. Covers single-select,
 * multi-select, free-text ("Other"), and absent answers.
 */
import { describe, it, expect } from 'vitest';
import { answerToLabels, answerToDisplay } from '../util/question-answer';

const OPTS = [
  { id: 'a', label: 'Apple' },
  { id: 'b', label: 'Banana' },
  { label: 'NoId' }, // option without an explicit id → keyed by label
];

describe('answerToLabels / answerToDisplay', () => {
  it('maps a single-select id to its label', () => {
    expect(answerToLabels(OPTS, ['a'])).toEqual(['Apple']);
    expect(answerToDisplay(OPTS, ['a'])).toBe('Apple');
  });

  it('maps multi-select ids to a joined label string', () => {
    expect(answerToDisplay(OPTS, ['a', 'b'])).toBe('Apple, Banana');
  });

  it('keys an option lacking an id by its label', () => {
    expect(answerToDisplay(OPTS, ['NoId'])).toBe('NoId');
  });

  it('passes free-text / "Other" answers through verbatim', () => {
    expect(answerToDisplay(OPTS, ['custom typed answer'])).toBe('custom typed answer');
    expect(answerToDisplay(OPTS, ['a', 'custom'])).toBe('Apple, custom');
  });

  it('accepts a bare string answer (not just an array)', () => {
    expect(answerToDisplay(OPTS, 'a')).toBe('Apple');
  });

  it('returns empty for an absent answer', () => {
    expect(answerToLabels(OPTS, undefined)).toEqual([]);
    expect(answerToDisplay(OPTS, undefined)).toBe('');
  });
});
