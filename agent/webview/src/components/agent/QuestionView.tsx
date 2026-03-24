/**
 * QuestionView — wraps tool-ui QuestionFlow with additional features:
 * - Text input questions (not just select)
 * - Skip button per question
 * - "Chat about this" option per selection
 * - Summary page before final submit
 * - Submit all / Cancel buttons
 */

import { useState, useCallback, useMemo } from 'react';
import type { Question } from '@/bridge/types';
import {
  QuestionFlow,
  type QuestionFlowOption,
} from '@/components/ui/tool-ui/question-flow';
import { Button } from '@/components/ui/button';
import { Badge } from '@/components/ui/badge';
import { MessageSquare, SkipForward, Send, X, Pencil } from 'lucide-react';

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
                ? 'var(--accent)'
                : isAnswered
                  ? 'var(--success)'
                  : 'var(--fg-muted)',
              opacity: isCurrent ? 1 : isAnswered ? 0.8 : 0.4,
            }}
          />
        );
      })}
    </div>
  );
}

// ── Text Input Question ──

function TextQuestion({
  question,
  onAnswer,
}: {
  question: Question;
  onAnswer: (text: string) => void;
}) {
  const [text, setText] = useState(
    typeof question.answer === 'string' ? question.answer : ''
  );

  return (
    <div
      className="rounded-xl border p-5"
      style={{ borderColor: 'var(--border)', backgroundColor: 'var(--card)' }}
    >
      <h2 className="text-base font-semibold mb-3" style={{ color: 'var(--fg)' }}>
        {question.text}
      </h2>
      <textarea
        value={text}
        onChange={e => setText(e.target.value)}
        placeholder="Type your answer..."
        rows={3}
        className="w-full rounded-lg border px-3 py-2 text-sm bg-transparent resize-none outline-none focus:ring-1"
        style={{
          borderColor: 'var(--input-border)',
          color: 'var(--fg)',
        }}
        onKeyDown={e => {
          if (e.key === 'Enter' && !e.shiftKey && text.trim()) {
            e.preventDefault();
            onAnswer(text.trim());
          }
        }}
      />
      <div className="flex justify-end mt-2">
        <Button
          size="sm"
          onClick={() => text.trim() && onAnswer(text.trim())}
          disabled={!text.trim()}
        >
          <Send className="h-3 w-3 mr-1" />
          Submit
        </Button>
      </div>
    </div>
  );
}

// ── Summary Page ──

function SummaryPage({
  questions,
  onEdit,
  onSubmit,
  onCancel,
}: {
  questions: Question[];
  onEdit: (index: number) => void;
  onSubmit: () => void;
  onCancel: () => void;
}) {
  return (
    <div
      className="rounded-xl border p-5"
      style={{ borderColor: 'var(--border)', backgroundColor: 'var(--card)' }}
    >
      <h2 className="text-base font-semibold mb-4" style={{ color: 'var(--fg)' }}>
        Review Your Answers
      </h2>

      <div className="space-y-3 mb-4">
        {questions.map((q, i) => {
          const answer = q.skipped
            ? 'Skipped'
            : Array.isArray(q.answer)
              ? q.answer.join(', ')
              : q.answer ?? 'No answer';

          return (
            <div
              key={q.id}
              className="flex items-start gap-3 rounded-lg px-3 py-2"
              style={{ backgroundColor: 'var(--tool-bg)' }}
            >
              <div className="flex-1 min-w-0">
                <div className="text-[12px] font-medium truncate" style={{ color: 'var(--fg)' }}>
                  {q.text}
                </div>
                <div className="text-[11px] mt-0.5" style={{ color: q.skipped ? 'var(--fg-muted)' : 'var(--accent)' }}>
                  {answer}
                </div>
              </div>
              <Button
                variant="ghost"
                size="sm"
                className="shrink-0 h-6 w-6 p-0"
                onClick={() => onEdit(i)}
                title="Edit answer"
              >
                <Pencil className="h-3 w-3" />
              </Button>
            </div>
          );
        })}
      </div>

      <div className="flex justify-end gap-2">
        <Button variant="outline" size="sm" onClick={onCancel}>
          <X className="h-3 w-3 mr-1" />
          Cancel
        </Button>
        <Button size="sm" onClick={onSubmit}>
          <Send className="h-3 w-3 mr-1" />
          Submit All
        </Button>
      </div>
    </div>
  );
}

// ── Chat About Dialog ──

function ChatAboutInput({
  questionId,
  optionLabel,
  onClose,
}: {
  questionId: string;
  optionLabel: string;
  onClose: () => void;
}) {
  const [msg, setMsg] = useState('');

  const handleSend = () => {
    if (msg.trim()) {
      window._chatAboutOption?.(questionId, optionLabel, msg.trim());
      onClose();
    }
  };

  return (
    <div
      className="mt-2 rounded-lg border p-3 animate-[fade-in_150ms_ease-out]"
      style={{ borderColor: 'var(--border)', backgroundColor: 'var(--tool-bg)' }}
    >
      <div className="text-[11px] mb-2" style={{ color: 'var(--fg-muted)' }}>
        Ask about: <Badge variant="secondary" className="text-[10px] ml-1">{optionLabel}</Badge>
      </div>
      <div className="flex gap-1.5">
        <input
          autoFocus
          value={msg}
          onChange={e => setMsg(e.target.value)}
          onKeyDown={e => { if (e.key === 'Enter') handleSend(); if (e.key === 'Escape') onClose(); }}
          placeholder="What would you like to know about this option?"
          className="flex-1 text-xs px-2 py-1.5 rounded-md border bg-transparent outline-none"
          style={{ borderColor: 'var(--input-border)', color: 'var(--fg)' }}
        />
        <Button size="sm" className="h-7" onClick={handleSend} disabled={!msg.trim()}>
          Ask
        </Button>
      </div>
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
  const [showSummary, setShowSummary] = useState(false);
  const [chatAbout, setChatAbout] = useState<{ qid: string; label: string } | null>(null);

  const question = questions[activeIndex];
  const isLastQuestion = activeIndex === questions.length - 1;

  // Track answered questions
  const answeredIndices = useMemo(() => {
    const set = new Set<number>();
    questions.forEach((q, i) => {
      if (q.answer !== undefined || q.skipped) set.add(i);
    });
    return set;
  }, [questions]);

  // Check if all questions answered → show summary
  const allAnswered = answeredIndices.size === questions.length;

  // Map options to QuestionFlow format
  const flowOptions: QuestionFlowOption[] = useMemo(() => {
    if (!question) return [];
    return question.options.map(opt => ({
      id: opt.label,
      label: opt.label,
      description: opt.description,
    }));
  }, [question]);

  const selectionMode = question?.type === 'multi-select' ? 'multi' as const : 'single' as const;

  const defaultValue = useMemo(() => {
    if (!question?.answer) return undefined;
    return Array.isArray(question.answer) ? question.answer : [question.answer];
  }, [question]);

  // Handlers
  const handleSelect = useCallback((optionIds: string[]) => {
    if (!question) return;
    window._questionAnswered?.(question.id, JSON.stringify(optionIds));
    // If last question, show summary after a brief delay
    if (isLastQuestion) {
      setTimeout(() => setShowSummary(true), 300);
    }
  }, [question, isLastQuestion]);

  const handleTextAnswer = useCallback((text: string) => {
    if (!question) return;
    window._questionAnswered?.(question.id, JSON.stringify([text]));
    if (isLastQuestion) {
      setTimeout(() => setShowSummary(true), 300);
    }
  }, [question, isLastQuestion]);

  const handleBack = useCallback(() => {
    if (activeIndex <= 0) return;
    setShowSummary(false);
    window._editQuestion?.(questions[activeIndex - 1]!.id);
  }, [activeIndex, questions]);

  const handleSkip = useCallback(() => {
    if (!question) return;
    window._questionSkipped?.(question.id);
  }, [question]);

  const handleEditFromSummary = useCallback((index: number) => {
    setShowSummary(false);
    window._editQuestion?.(questions[index]!.id);
  }, [questions]);

  const handleSubmitAll = useCallback(() => {
    window._questionsSubmitted?.();
  }, []);

  const handleCancel = useCallback(() => {
    window._questionsCancelled?.();
  }, []);

  // Summary page when all questions are answered
  if (showSummary || allAnswered) {
    return (
      <div className="my-3 animate-[fade-in_220ms_ease-out]">
        <SummaryPage
          questions={questions}
          onEdit={handleEditFromSummary}
          onSubmit={handleSubmitAll}
          onCancel={handleCancel}
        />
      </div>
    );
  }

  if (!question) return null;

  return (
    <div className="my-3 animate-[fade-in_220ms_ease-out]">
      {/* Text input question */}
      {question.type === 'text' ? (
        <TextQuestion question={question} onAnswer={handleTextAnswer} />
      ) : (
        /* Select question via tool-ui QuestionFlow */
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
      )}

      {/* Action row: Skip + Chat about + Cancel */}
      <div className="flex items-center justify-between mt-2 px-1">
        <Button
          variant="ghost"
          size="sm"
          className="text-[11px] h-7"
          onClick={handleSkip}
        >
          <SkipForward className="h-3 w-3 mr-1" />
          Skip
        </Button>

        {question.type !== 'text' && question.options.length > 0 && (
          <Button
            variant="ghost"
            size="sm"
            className="text-[11px] h-7"
            onClick={() => setChatAbout({
              qid: question.id,
              label: question.options[0]?.label ?? '',
            })}
          >
            <MessageSquare className="h-3 w-3 mr-1" />
            Chat about this
          </Button>
        )}

        <Button
          variant="ghost"
          size="sm"
          className="text-[11px] h-7"
          onClick={handleCancel}
        >
          <X className="h-3 w-3 mr-1" />
          Cancel
        </Button>
      </div>

      {/* Chat about input */}
      {chatAbout && (
        <ChatAboutInput
          questionId={chatAbout.qid}
          optionLabel={chatAbout.label}
          onClose={() => setChatAbout(null)}
        />
      )}

      <StepDots
        total={questions.length}
        current={activeIndex}
        answeredIndices={answeredIndices}
      />
    </div>
  );
}
