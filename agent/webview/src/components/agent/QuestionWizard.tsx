import { useState, useCallback, useMemo } from 'react';
import type { Question, QuestionOption } from '@/bridge/types';

// ── StepDots ──

function StepDots({
  total,
  current,
  answered,
  onDotClick,
}: {
  total: number;
  current: number;
  answered: Set<number>;
  onDotClick: (index: number) => void;
}) {
  return (
    <div className="flex items-center justify-center gap-1.5 py-2">
      {Array.from({ length: total }, (_, i) => {
        const isCurrent = i === current;
        const isAnswered = answered.has(i);
        return (
          <button
            key={i}
            onClick={() => onDotClick(i)}
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
            title={`Question ${i + 1}`}
          />
        );
      })}
    </div>
  );
}

// ── RadioOption ──

function RadioOption({
  option,
  selected,
  onSelect,
  onChatAbout,
}: {
  option: QuestionOption;
  selected: boolean;
  onSelect: () => void;
  onChatAbout: () => void;
}) {
  return (
    <button
      onClick={onSelect}
      className="group flex w-full items-start gap-3 rounded-lg border p-3 text-left transition-all duration-150 hover:brightness-110"
      style={{
        borderColor: selected ? 'var(--accent, #6366f1)' : 'var(--border, #333)',
        backgroundColor: selected
          ? 'color-mix(in srgb, var(--accent, #6366f1) 10%, var(--bg, #1e1e1e))'
          : 'var(--card-bg, #252525)',
      }}
    >
      {/* Radio circle */}
      <div
        className="mt-0.5 flex h-[18px] w-[18px] shrink-0 items-center justify-center rounded-full border-2 transition-colors duration-150"
        style={{
          borderColor: selected ? 'var(--accent, #6366f1)' : 'var(--fg-muted, #888)',
        }}
      >
        {selected && (
          <div
            className="h-[10px] w-[10px] rounded-full"
            style={{ backgroundColor: 'var(--accent, #6366f1)' }}
          />
        )}
      </div>

      {/* Label + description */}
      <div className="flex-1 min-w-0">
        <div className="text-[12px] font-medium" style={{ color: 'var(--fg, #ccc)' }}>
          {option.label}
        </div>
        {option.description && (
          <div className="mt-0.5 text-[11px]" style={{ color: 'var(--fg-secondary, #aaa)' }}>
            {option.description}
          </div>
        )}
      </div>

      {/* Chat about icon */}
      <button
        onClick={e => {
          e.stopPropagation();
          onChatAbout();
        }}
        className="mt-0.5 shrink-0 rounded p-1 opacity-0 transition-opacity duration-150 group-hover:opacity-100 hover:brightness-125"
        style={{ color: 'var(--fg-muted, #888)' }}
        title="Chat about this option"
      >
        <svg width="14" height="14" viewBox="0 0 16 16" fill="none">
          <path
            d="M2 2.5h12a1 1 0 011 1v7a1 1 0 01-1 1H5l-3 3v-3a1 1 0 01-1-1v-7a1 1 0 011-1z"
            stroke="currentColor"
            strokeWidth="1.2"
            fill="none"
          />
          <path d="M5 6h6M5 8.5h4" stroke="currentColor" strokeWidth="1" strokeLinecap="round" />
        </svg>
      </button>
    </button>
  );
}

// ── CheckboxOption ──

function CheckboxOption({
  option,
  checked,
  onToggle,
  onChatAbout,
}: {
  option: QuestionOption;
  checked: boolean;
  onToggle: () => void;
  onChatAbout: () => void;
}) {
  return (
    <button
      onClick={onToggle}
      className="group flex w-full items-start gap-3 rounded-lg border p-3 text-left transition-all duration-150 hover:brightness-110"
      style={{
        borderColor: checked ? 'var(--accent, #6366f1)' : 'var(--border, #333)',
        backgroundColor: checked
          ? 'color-mix(in srgb, var(--accent, #6366f1) 10%, var(--bg, #1e1e1e))'
          : 'var(--card-bg, #252525)',
      }}
    >
      {/* Checkbox */}
      <div
        className="mt-0.5 flex h-[18px] w-[18px] shrink-0 items-center justify-center rounded border-2 transition-colors duration-150"
        style={{
          borderColor: checked ? 'var(--accent, #6366f1)' : 'var(--fg-muted, #888)',
          backgroundColor: checked ? 'var(--accent, #6366f1)' : 'transparent',
        }}
      >
        {checked && (
          <svg width="12" height="12" viewBox="0 0 16 16" fill="none">
            <path
              d="M4 8l3 3 5-5"
              stroke="white"
              strokeWidth="2"
              strokeLinecap="round"
              strokeLinejoin="round"
            />
          </svg>
        )}
      </div>

      {/* Label + description */}
      <div className="flex-1 min-w-0">
        <div className="text-[12px] font-medium" style={{ color: 'var(--fg, #ccc)' }}>
          {option.label}
        </div>
        {option.description && (
          <div className="mt-0.5 text-[11px]" style={{ color: 'var(--fg-secondary, #aaa)' }}>
            {option.description}
          </div>
        )}
      </div>

      {/* Chat about icon */}
      <button
        onClick={e => {
          e.stopPropagation();
          onChatAbout();
        }}
        className="mt-0.5 shrink-0 rounded p-1 opacity-0 transition-opacity duration-150 group-hover:opacity-100 hover:brightness-125"
        style={{ color: 'var(--fg-muted, #888)' }}
        title="Chat about this option"
      >
        <svg width="14" height="14" viewBox="0 0 16 16" fill="none">
          <path
            d="M2 2.5h12a1 1 0 011 1v7a1 1 0 01-1 1H5l-3 3v-3a1 1 0 01-1-1v-7a1 1 0 011-1z"
            stroke="currentColor"
            strokeWidth="1.2"
            fill="none"
          />
          <path d="M5 6h6M5 8.5h4" stroke="currentColor" strokeWidth="1" strokeLinecap="round" />
        </svg>
      </button>
    </button>
  );
}

// ── SummaryPage ──

function SummaryPage({
  questions,
  answers,
  onEdit,
  onSubmit,
  onCancel,
}: {
  questions: Question[];
  answers: Map<string, string | string[]>;
  onEdit: (qid: string) => void;
  onSubmit: () => void;
  onCancel: () => void;
}) {
  return (
    <div className="flex flex-col gap-3">
      <div className="text-[13px] font-semibold" style={{ color: 'var(--fg, #ccc)' }}>
        Review Your Answers
      </div>

      {questions.map(q => {
        const answer = answers.get(q.id);
        const skipped = !answer;
        const displayValue = skipped
          ? 'Skipped'
          : Array.isArray(answer)
            ? answer.join(', ')
            : answer;

        return (
          <div
            key={q.id}
            className="flex items-start gap-3 rounded-lg border p-3"
            style={{
              borderColor: 'var(--border, #333)',
              backgroundColor: 'var(--card-bg, #252525)',
            }}
          >
            <div className="flex-1 min-w-0">
              <div className="text-[11px] font-medium" style={{ color: 'var(--fg-muted, #888)' }}>
                {q.text}
              </div>
              <div
                className="mt-1 text-[12px]"
                style={{
                  color: skipped ? 'var(--fg-muted, #888)' : 'var(--fg, #ccc)',
                  fontStyle: skipped ? 'italic' : 'normal',
                }}
              >
                {displayValue}
              </div>
            </div>
            <button
              onClick={() => onEdit(q.id)}
              className="shrink-0 rounded px-2 py-1 text-[11px] font-medium transition-colors duration-150 hover:brightness-125"
              style={{
                color: 'var(--link, #6ba3f7)',
                backgroundColor: 'transparent',
              }}
            >
              Edit
            </button>
          </div>
        );
      })}

      {/* Submit / Cancel */}
      <div className="flex items-center gap-2 pt-1">
        <button
          onClick={onSubmit}
          className="flex-1 rounded-md px-3 py-2 text-[12px] font-medium transition-all duration-150 hover:brightness-110"
          style={{
            backgroundColor: 'var(--accent, #6366f1)',
            color: 'white',
          }}
        >
          Submit Answers
        </button>
        <button
          onClick={onCancel}
          className="rounded-md px-3 py-2 text-[12px] font-medium transition-all duration-150 hover:brightness-110"
          style={{
            border: '1px solid var(--border, #333)',
            color: 'var(--fg-secondary, #aaa)',
            backgroundColor: 'transparent',
          }}
        >
          Cancel
        </button>
      </div>
    </div>
  );
}

// ── QuestionWizard (main) ──

interface QuestionWizardProps {
  questions: Question[];
  activeIndex: number;
}

export function QuestionWizard({ questions, activeIndex }: QuestionWizardProps) {
  const [currentIndex, setCurrentIndex] = useState(activeIndex);
  const [answers, setAnswers] = useState<Map<string, string | string[]>>(() => {
    const initial = new Map<string, string | string[]>();
    for (const q of questions) {
      if (q.answer !== undefined) {
        initial.set(q.id, q.answer);
      }
    }
    return initial;
  });
  const [showSummary, setShowSummary] = useState(false);
  const [textValue, setTextValue] = useState('');

  const question = questions[currentIndex];
  const total = questions.length;
  const isFirst = currentIndex === 0;
  const isLast = currentIndex === total - 1;

  const answeredSet = useMemo(() => {
    const set = new Set<number>();
    questions.forEach((q, i) => {
      if (answers.has(q.id) || q.skipped) set.add(i);
    });
    return set;
  }, [questions, answers]);

  // ── Selection state for current question ──

  const currentAnswer = question ? answers.get(question.id) : undefined;

  const selectedRadio = useMemo(() => {
    if (!question || question.type !== 'single-select') return '';
    return typeof currentAnswer === 'string' ? currentAnswer : '';
  }, [question, currentAnswer]);

  const selectedCheckboxes = useMemo(() => {
    if (!question || question.type !== 'multi-select') return new Set<string>();
    return new Set(Array.isArray(currentAnswer) ? currentAnswer : []);
  }, [question, currentAnswer]);

  // Sync text value when navigating to a text question
  const currentTextValue = useMemo(() => {
    if (!question || question.type !== 'text') return '';
    return typeof currentAnswer === 'string' ? currentAnswer : '';
  }, [question, currentAnswer]);

  // ── Handlers ──

  const handleSelectRadio = useCallback(
    (label: string) => {
      if (!question) return;
      setAnswers(prev => {
        const next = new Map(prev);
        next.set(question.id, label);
        return next;
      });
    },
    [question]
  );

  const handleToggleCheckbox = useCallback(
    (label: string) => {
      if (!question) return;
      setAnswers(prev => {
        const next = new Map(prev);
        const existing = new Set(Array.isArray(prev.get(question.id)) ? (prev.get(question.id) as string[]) : []);
        if (existing.has(label)) {
          existing.delete(label);
        } else {
          existing.add(label);
        }
        next.set(question.id, Array.from(existing));
        return next;
      });
    },
    [question]
  );

  const handleTextChange = useCallback(
    (value: string) => {
      setTextValue(value);
      if (!question) return;
      setAnswers(prev => {
        const next = new Map(prev);
        if (value.trim()) {
          next.set(question.id, value);
        } else {
          next.delete(question.id);
        }
        return next;
      });
    },
    [question]
  );

  const handleChatAbout = useCallback(
    (option: QuestionOption) => {
      if (!question) return;
      const msg = `Tell me more about "${option.label}"${option.description ? `: ${option.description}` : ''}`;
      window._chatAboutOption?.(question.id, option.label, msg);
    },
    [question]
  );

  const handleNext = useCallback(() => {
    if (!question) return;
    // Send answer to bridge
    const answer = answers.get(question.id);
    if (answer !== undefined) {
      const optionsJson = JSON.stringify(Array.isArray(answer) ? answer : [answer]);
      window._questionAnswered?.(question.id, optionsJson);
    }

    if (isLast) {
      setShowSummary(true);
    } else {
      setCurrentIndex(i => i + 1);
      setTextValue('');
    }
  }, [question, answers, isLast]);

  const handleSkip = useCallback(() => {
    if (!question) return;
    window._questionSkipped?.(question.id);
    // Remove any answer for this question
    setAnswers(prev => {
      const next = new Map(prev);
      next.delete(question.id);
      return next;
    });

    if (isLast) {
      setShowSummary(true);
    } else {
      setCurrentIndex(i => i + 1);
      setTextValue('');
    }
  }, [question, isLast]);

  const handleBack = useCallback(() => {
    if (!isFirst) {
      setCurrentIndex(i => i - 1);
      setTextValue('');
    }
  }, [isFirst]);

  const handleDotClick = useCallback((index: number) => {
    setShowSummary(false);
    setCurrentIndex(index);
    setTextValue('');
  }, []);

  const handleEditFromSummary = useCallback(
    (qid: string) => {
      window._editQuestion?.(qid);
      const idx = questions.findIndex(q => q.id === qid);
      if (idx >= 0) {
        setCurrentIndex(idx);
        setShowSummary(false);
        setTextValue('');
      }
    },
    [questions]
  );

  const handleSubmit = useCallback(() => {
    window._questionsSubmitted?.();
  }, []);

  const handleCancel = useCallback(() => {
    window._questionsCancelled?.();
  }, []);

  // ── Determine if Next is enabled ──
  const canProceed = useMemo(() => {
    if (!question) return false;
    if (question.type === 'single-select') return selectedRadio !== '';
    if (question.type === 'multi-select') return selectedCheckboxes.size > 0;
    if (question.type === 'text') return (textValue || currentTextValue).trim() !== '';
    return false;
  }, [question, selectedRadio, selectedCheckboxes, textValue, currentTextValue]);

  if (!question) return null;

  return (
    <div
      className="my-3 overflow-hidden rounded-lg border animate-[fade-in_220ms_ease-out]"
      style={{
        borderColor: 'var(--accent, #6366f1)',
        backgroundColor: 'var(--card-bg, #252525)',
      }}
    >
      {/* Header */}
      <div
        className="flex items-center gap-2 px-3 py-2.5"
        style={{ borderBottom: '1px solid var(--border, #333)' }}
      >
        <svg width="18" height="18" viewBox="0 0 24 24" fill="none" className="shrink-0">
          <circle cx="12" cy="12" r="10" stroke="var(--accent, #6366f1)" strokeWidth="1.5" fill="none" />
          <path
            d="M9 9a3 3 0 115.12 2.12c-.6.6-1.12 1.08-1.12 1.88v1"
            stroke="var(--accent, #6366f1)"
            strokeWidth="1.5"
            strokeLinecap="round"
          />
          <circle cx="12" cy="17" r="0.75" fill="var(--accent, #6366f1)" />
        </svg>
        <span className="flex-1 text-[13px] font-semibold" style={{ color: 'var(--fg, #ccc)' }}>
          {showSummary ? 'Review Answers' : `Question ${currentIndex + 1} of ${total}`}
        </span>
        <button
          onClick={handleCancel}
          className="shrink-0 rounded p-1 transition-colors duration-150 hover:brightness-125"
          style={{ color: 'var(--fg-muted, #888)' }}
          title="Cancel questions"
        >
          <svg width="14" height="14" viewBox="0 0 16 16" fill="none">
            <path
              d="M4 4l8 8M12 4l-8 8"
              stroke="currentColor"
              strokeWidth="1.5"
              strokeLinecap="round"
            />
          </svg>
        </button>
      </div>

      {/* Step dots */}
      {!showSummary && (
        <StepDots
          total={total}
          current={currentIndex}
          answered={answeredSet}
          onDotClick={handleDotClick}
        />
      )}

      {/* Content area */}
      <div className="px-3 py-2">
        {showSummary ? (
          <SummaryPage
            questions={questions}
            answers={answers}
            onEdit={handleEditFromSummary}
            onSubmit={handleSubmit}
            onCancel={handleCancel}
          />
        ) : (
          <>
            {/* Question text */}
            <div
              className="mb-3 text-[12px] font-medium"
              style={{ color: 'var(--fg, #ccc)' }}
            >
              {question.text}
            </div>

            {/* Options or text input */}
            {question.type === 'single-select' && (
              <div className="flex flex-col gap-2">
                {question.options.map(opt => (
                  <RadioOption
                    key={opt.label}
                    option={opt}
                    selected={selectedRadio === opt.label}
                    onSelect={() => handleSelectRadio(opt.label)}
                    onChatAbout={() => handleChatAbout(opt)}
                  />
                ))}
              </div>
            )}

            {question.type === 'multi-select' && (
              <div className="flex flex-col gap-2">
                {question.options.map(opt => (
                  <CheckboxOption
                    key={opt.label}
                    option={opt}
                    checked={selectedCheckboxes.has(opt.label)}
                    onToggle={() => handleToggleCheckbox(opt.label)}
                    onChatAbout={() => handleChatAbout(opt)}
                  />
                ))}
              </div>
            )}

            {question.type === 'text' && (
              <textarea
                value={textValue || currentTextValue}
                onChange={e => handleTextChange(e.target.value)}
                placeholder="Type your answer..."
                rows={4}
                className="w-full resize-none rounded-lg p-3 text-[12px] outline-none transition-colors duration-150"
                style={{
                  backgroundColor: 'var(--input-bg, #1e1e1e)',
                  border: '1px solid var(--input-border, #444)',
                  color: 'var(--fg, #ccc)',
                  fontFamily: 'var(--font-body)',
                }}
              />
            )}
          </>
        )}
      </div>

      {/* Footer navigation — hidden on summary (summary has its own buttons) */}
      {!showSummary && (
        <div
          className="flex items-center gap-2 px-3 py-2.5"
          style={{ borderTop: '1px solid var(--border, #333)' }}
        >
          <button
            onClick={handleBack}
            disabled={isFirst}
            className="rounded-md px-3 py-1.5 text-[12px] font-medium transition-all duration-150 hover:brightness-110 disabled:opacity-30 disabled:cursor-not-allowed"
            style={{
              border: '1px solid var(--border, #333)',
              color: 'var(--fg-secondary, #aaa)',
              backgroundColor: 'transparent',
            }}
          >
            Back
          </button>

          <div className="flex-1" />

          <button
            onClick={handleSkip}
            className="rounded-md px-3 py-1.5 text-[12px] font-medium transition-all duration-150 hover:brightness-110"
            style={{
              color: 'var(--fg-muted, #888)',
              backgroundColor: 'transparent',
            }}
          >
            Skip
          </button>

          <button
            onClick={handleNext}
            disabled={!canProceed}
            className="rounded-md px-3 py-1.5 text-[12px] font-medium transition-all duration-150 hover:brightness-110 disabled:opacity-40 disabled:cursor-not-allowed"
            style={{
              backgroundColor: canProceed ? 'var(--accent, #6366f1)' : 'var(--fg-muted, #888)',
              color: 'white',
            }}
          >
            {isLast ? 'Review' : 'Next'}
          </button>
        </div>
      )}
    </div>
  );
}
