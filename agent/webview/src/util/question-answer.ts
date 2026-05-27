/**
 * Single source of truth for turning an ask_questions answer (option ids and/or
 * free-text) into human-readable labels. Used by the QuestionView summary page
 * and by `chatStore.finalizeQuestionsAsMessage` so the answered-question receipt
 * never drifts between the two render paths (bug #18).
 *
 * An option is matched by `id` (falling back to `label` when it has no id). An
 * answer token that matches no option — a free-text "Other" answer — passes
 * through verbatim.
 */

interface AnswerOption {
  id?: string;
  label: string;
}

/** Resolve an answer (id, list of ids, or free text) to display labels. */
export function answerToLabels(
  options: AnswerOption[],
  answer: string | string[] | undefined,
): string[] {
  if (answer === undefined) return [];
  const ids = Array.isArray(answer) ? answer : [answer];
  return ids.map(id => options.find(o => (o.id ?? o.label) === id)?.label ?? id);
}

/** Resolve an answer to a single comma-joined display string. */
export function answerToDisplay(
  options: AnswerOption[],
  answer: string | string[] | undefined,
): string {
  return answerToLabels(options, answer).join(', ');
}
