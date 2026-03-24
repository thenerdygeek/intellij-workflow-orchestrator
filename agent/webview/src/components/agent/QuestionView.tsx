/**
 * QuestionView — wraps tool-ui QuestionFlow in progressive mode,
 * mapping our Question types and bridging selections back to Kotlin.
 */

import { useCallback, useMemo } from 'react';
import type { Question } from '@/bridge/types';
import {
  QuestionFlow,
  type QuestionFlowOption,
} from '@/components/ui/tool-ui/question-flow';

// ── Step indicator dots ──

function StepDots({
  total,
  current,
  answeredIndices,
}: {
  total: number;
  current: number;
  answeredIndices: Set<number>;
}) {
  return (
    <div className="flex items-center justify-center gap-1.5 py-2">
      {Array.from({ length: total }, (_, i) => {
        const isCurrent = i === current;
        const isAnswered = answeredIndices.has(i);
        return (
          <span
            key={i}
            className="rounded-full transition-all duration-200"
            style={{
              width: isCurrent ? 20 : 8,
              height: 8,
              backgroundColor: isCurrent
                ? 'var(--accent, #6366f1)'
                : isAnswered
                  ? 'var(--success, #22c55e)'
                  : 'var(--fg-muted, #888)',
              opacity: isCurrent ? 1 : isAnswered ? 0.8 : 0.4,
            }}
          />
        );
      })}
    </div>
  );
}

// ── Props ──

interface QuestionViewProps {
  questions: Question[];
  activeIndex: number;
}

// ── QuestionView ──

export function QuestionView({ questions, activeIndex }: QuestionViewProps) {
  const question = questions[activeIndex];

  // Build the set of already-answered question indices
  const answeredIndices = useMemo(() => {
    const set = new Set<number>();
    questions.forEach((q, i) => {
      if (q.answer !== undefined || q.skipped) set.add(i);
    });
    return set;
  }, [questions]);

  // Map our QuestionOption[] to QuestionFlowOption[]
  const flowOptions: QuestionFlowOption[] = useMemo(() => {
    if (!question) return [];
    return question.options.map((opt) => ({
      id: opt.label,
      label: opt.label,
      description: opt.description,
    }));
  }, [question]);

  // Map question.type to selectionMode
  const selectionMode: 'single' | 'multi' = useMemo(() => {
    if (!question) return 'single';
    return question.type === 'multi-select' ? 'multi' : 'single';
  }, [question]);

  // Compute defaultValue from pre-existing answer
  const defaultValue: string[] | undefined = useMemo(() => {
    if (!question?.answer) return undefined;
    return Array.isArray(question.answer)
      ? question.answer
      : [question.answer];
  }, [question]);

  // On selection: notify Kotlin bridge
  const handleSelect = useCallback(
    (optionIds: string[]) => {
      if (!question) return;
      window._questionAnswered?.(question.id, JSON.stringify(optionIds));
    },
    [question],
  );

  // On back: notify Kotlin bridge to edit previous question
  const handleBack = useCallback(() => {
    if (activeIndex <= 0) return;
    const prevQuestion = questions[activeIndex - 1];
    if (prevQuestion) {
      window._editQuestion?.(prevQuestion.id);
    }
  }, [activeIndex, questions]);

  if (!question) return null;

  return (
    <div className="my-3 animate-[fade-in_220ms_ease-out]">
      <QuestionFlow
        id={`question-${question.id}`}
        step={activeIndex + 1}
        title={question.text}
        options={flowOptions}
        selectionMode={selectionMode}
        defaultValue={defaultValue}
        onSelect={handleSelect}
        onBack={activeIndex > 0 ? handleBack : undefined}
      />
      <StepDots
        total={questions.length}
        current={activeIndex}
        answeredIndices={answeredIndices}
      />
    </div>
  );
}
