/**
 * QuestionView — wraps tool-ui QuestionFlow with additional features:
 * - Text input questions (not just select)
 * - Skip button per question
 * - "Chat about this" option per selection
 * - Summary page before final submit
 * - Submit all / Cancel buttons
 *
 * All content is wrapped in a single unified card boundary.
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
import { useChatStore } from '@/stores/chatStore';
import { MarkdownRenderer } from '@/components/markdown/MarkdownRenderer';

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
      className="rounded-lg p-3 animate-[fade-in_150ms_ease-out]"
      style={{ backgroundColor: 'var(--tool-bg)' }}
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

// ── Action Row ──

function ActionRow({
  question,
  onSkip,
  onChatAbout,
  onCancel,
}: {
  question: Question;
  onSkip: () => void;
  onChatAbout?: () => void;
  onCancel: () => void;
}) {
  return (
    <div className="flex items-center justify-between px-1">
      <Button variant="ghost" size="sm" className="text-[11px] h-7" onClick={onSkip}>
        <SkipForward className="h-3 w-3 mr-1" />
        Skip
      </Button>

      {question.type !== 'text' && question.options.length > 0 && onChatAbout && (
        <Button variant="ghost" size="sm" className="text-[11px] h-7" onClick={onChatAbout}>
          <MessageSquare className="h-3 w-3 mr-1" />
          Chat about this
        </Button>
      )}

      <Button variant="ghost" size="sm" className="text-[11px] h-7" onClick={onCancel}>
        <X className="h-3 w-3 mr-1" />
        Cancel
      </Button>
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
  const [customText, setCustomText] = useState('');
  const [customSelected, setCustomSelected] = useState(false);
  const storeAnswer = useChatStore(s => s.answerQuestion);
  const storeSkip = useChatStore(s => s.skipQuestion);
  const storeClear = useChatStore(s => s.clearQuestions);

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

  const allAnswered = answeredIndices.size === questions.length;

  // Map options to QuestionFlow format (no "Other" — rendered separately with inline input)
  const flowOptions: QuestionFlowOption[] = useMemo(() => {
    if (!question) return [];
    return question.options.map(opt => ({
      id: opt.id ?? opt.label,
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
    setCustomSelected(false);
    storeAnswer(question.id, optionIds);
    window._questionAnswered?.(question.id, JSON.stringify(optionIds));
    if (isLastQuestion) {
      setTimeout(() => setShowSummary(true), 300);
    }
  }, [question, isLastQuestion, storeAnswer]);

  const handleCustomSubmit = useCallback(() => {
    if (!question || !customText.trim()) return;
    const answer = [customText.trim()];
    storeAnswer(question.id, answer);
    window._questionAnswered?.(question.id, JSON.stringify(answer));
    setCustomSelected(false);
    setCustomText('');
    if (isLastQuestion) {
      setTimeout(() => setShowSummary(true), 300);
    }
  }, [question, customText, isLastQuestion, storeAnswer]);

  const handleTextAnswer = useCallback((text: string) => {
    if (!question) return;
    storeAnswer(question.id, [text]);
    window._questionAnswered?.(question.id, JSON.stringify([text]));
    if (isLastQuestion) {
      setTimeout(() => setShowSummary(true), 300);
    }
  }, [question, isLastQuestion, storeAnswer]);

  const handleBack = useCallback(() => {
    if (activeIndex <= 0) return;
    setShowSummary(false);
    setCustomSelected(false);
    window._editQuestion?.(questions[activeIndex - 1]!.id);
  }, [activeIndex, questions]);

  const handleSkip = useCallback(() => {
    if (!question) return;
    setCustomSelected(false);
    storeSkip(question.id);
    window._questionSkipped?.(question.id);
  }, [question, storeSkip]);

  const handleEditFromSummary = useCallback((index: number) => {
    setShowSummary(false);
    setCustomSelected(false);
    window._editQuestion?.(questions[index]!.id);
  }, [questions]);

  const handleSubmitAll = useCallback(() => {
    window._questionsSubmitted?.();
  }, []);

  const handleCancel = useCallback(() => {
    setCustomSelected(false);
    storeClear();
    window._questionsCancelled?.();
  }, [storeClear]);

  // ── Summary page ──
  if (showSummary || allAnswered) {
    return (
      <div className="question-view my-3 animate-[fade-in_220ms_ease-out]">
        <div
          className="rounded-xl border p-5"
          style={{ borderColor: 'var(--border)', backgroundColor: 'var(--card)' }}
        >
          <h2 className="text-base font-semibold mb-4" style={{ color: 'var(--fg)' }}>
            Review Your Answers
          </h2>

          <div className="space-y-3 mb-4">
            {questions.map((q, i) => {
              const answerDisplay = q.skipped
                ? 'Skipped'
                : (() => {
                    if (!q.answer) return 'No answer';
                    const ids = Array.isArray(q.answer) ? q.answer : [q.answer];
                    const labels = ids.map(id => {
                      const opt = q.options.find(o => (o.id ?? o.label) === id);
                      return opt?.label ?? id;
                    });
                    return labels.join(', ');
                  })();

              return (
                <div
                  key={q.id}
                  className="flex items-start gap-3 rounded-lg px-3 py-2"
                  style={{ backgroundColor: 'var(--tool-bg)' }}
                >
                  <div className="flex-1 min-w-0">
                    <div className="text-[12px] font-medium [&_p]:m-0 [&_p]:inline" style={{ color: 'var(--fg)' }}>
                      <MarkdownRenderer content={q.text} />
                    </div>
                    <div className="text-[11px] mt-0.5" style={{ color: q.skipped ? 'var(--fg-muted)' : 'var(--accent)' }}>
                      {answerDisplay}
                    </div>
                  </div>
                  <Button
                    variant="ghost"
                    size="sm"
                    className="shrink-0 h-6 w-6 p-0"
                    onClick={() => handleEditFromSummary(i)}
                    title="Edit answer"
                  >
                    <Pencil className="h-3 w-3" />
                  </Button>
                </div>
              );
            })}
          </div>

          <div className="flex justify-end gap-2">
            <Button variant="outline" size="sm" onClick={handleCancel}>
              <X className="h-3 w-3 mr-1" />
              Cancel
            </Button>
            <Button size="sm" onClick={handleSubmitAll} className="question-view-btn">
              <Send className="h-3 w-3 mr-1" />
              Submit All
            </Button>
          </div>
        </div>
      </div>
    );
  }

  if (!question) return null;

  // ── Question card (unified boundary) ──
  return (
    <div className="question-view my-3 animate-[fade-in_220ms_ease-out]">
      <div
        className="question-card rounded-xl border overflow-hidden"
        style={{ borderColor: 'var(--border)', backgroundColor: 'var(--card)' }}
      >
        {/* Text input question */}
        {question.type === 'text' ? (
          <div className="p-4">
            <div className="text-sm font-semibold mb-2 [&_p]:m-0 [&_p]:inline" style={{ color: 'var(--fg)' }}>
              <MarkdownRenderer content={question.text} />
            </div>
            <TextInput
              key={question.id}
              defaultAnswer={typeof question.answer === 'string' ? question.answer : ''}
              onAnswer={handleTextAnswer}
            />
          </div>
        ) : (
          <>
            {/* QuestionFlow — embedded without its own card border */}
            <div className="question-flow-embedded">
              <QuestionFlow
                key={question.id}
                id={`question-${question.id}`}
                step={activeIndex + 1}
                title={<MarkdownRenderer content={question.text} />}
                titleText={question.text}
                options={flowOptions}
                selectionMode={selectionMode}
                defaultValue={defaultValue}
                onSelect={handleSelect}
                onBack={activeIndex > 0 ? handleBack : undefined}
              />
            </div>

            {/* "Other" option — visually continuous with the options above */}
            <div className="px-4 pb-2">
              <div className="mx-1 mb-1" style={{ borderTop: '1px solid var(--border)' }} />

              <button
                className="flex items-start gap-3 w-full text-left py-1.5 px-1 group"
                onClick={() => setCustomSelected(!customSelected)}
              >
                <span className="flex h-6 items-center">
                  <span
                    className="flex size-4 shrink-0 items-center justify-center border-2 rounded-full transition-colors"
                    style={{
                      borderColor: customSelected ? 'var(--accent, #0078d4)' : 'color-mix(in srgb, var(--fg-muted) 50%, transparent)',
                      backgroundColor: customSelected ? 'var(--accent, #0078d4)' : 'transparent',
                    }}
                  >
                    {customSelected && (
                      <span className="size-2 rounded-full bg-white" />
                    )}
                  </span>
                </span>
                <div className="flex flex-col text-left">
                  <span className="text-[13px] font-medium leading-5" style={{ color: 'var(--fg)' }}>Other</span>
                  {!customSelected && (
                    <span className="text-[11px]" style={{ color: 'var(--fg-muted)' }}>Type a custom answer</span>
                  )}
                </div>
              </button>

              {customSelected && (
                <div className="ml-7 flex gap-1.5 pb-1 animate-[fade-in_150ms_ease-out]">
                  <input
                    autoFocus
                    value={customText}
                    onChange={e => setCustomText(e.target.value)}
                    onKeyDown={e => {
                      if (e.key === 'Enter' && customText.trim()) handleCustomSubmit();
                      if (e.key === 'Escape') { setCustomSelected(false); setCustomText(''); }
                    }}
                    placeholder="Type a custom answer..."
                    className="flex-1 text-sm px-3 py-1.5 rounded-lg border bg-transparent outline-none focus:ring-1"
                    style={{
                      borderColor: 'var(--accent, #0078d4)',
                      color: 'var(--fg)',
                    }}
                  />
                  <Button size="sm" className="h-8 question-view-btn" onClick={handleCustomSubmit} disabled={!customText.trim()}>
                    <Send className="h-3 w-3 mr-1" />
                    Submit
                  </Button>
                </div>
              )}
            </div>
          </>
        )}

        {/* Divider before actions */}
        <div className="mx-4" style={{ borderTop: '1px solid var(--border)' }} />

        {/* Action row: Skip + Chat about + Cancel — inside the card */}
        <div className="px-3 pt-2 pb-1">
          <ActionRow
            question={question}
            onSkip={handleSkip}
            onChatAbout={question.type !== 'text' && question.options.length > 0
              ? () => setChatAbout({ qid: question.id, label: question.options[0]?.label ?? '' })
              : undefined
            }
            onCancel={handleCancel}
          />
        </div>

        {/* Chat about input — inside the card */}
        {chatAbout && (
          <div className="px-4 pb-3">
            <ChatAboutInput
              questionId={chatAbout.qid}
              optionLabel={chatAbout.label}
              onClose={() => setChatAbout(null)}
            />
          </div>
        )}

        {/* Step dots — inside the card */}
        <StepDots
          total={questions.length}
          current={activeIndex}
          answeredIndices={answeredIndices}
        />
      </div>
    </div>
  );
}

// ── Text Input (no card border — parent provides the boundary) ──

function TextInput({
  defaultAnswer,
  onAnswer,
}: {
  defaultAnswer: string;
  onAnswer: (text: string) => void;
}) {
  const [text, setText] = useState(defaultAnswer);

  return (
    <>
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
    </>
  );
}
