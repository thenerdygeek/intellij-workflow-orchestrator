/**
 * QuestionView — wraps tool-ui QuestionFlow with additional features:
 * - Text input questions (not just select)
 * - Skip button per question
 * - "Chat about this" follow-up per question
 * - Summary page before final submit
 * - Submit all / Cancel buttons
 *
 * All content is wrapped in a single unified card boundary.
 */

import { useState, useCallback, useMemo, useRef, useEffect } from 'react';
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
import { answerToDisplay } from '@/util/question-answer';

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
  subject,
  onClose,
}: {
  questionId: string;
  /** The QUESTION this follow-up is about (its text), not any single option. */
  subject: string;
  onClose: () => void;
}) {
  const [msg, setMsg] = useState('');

  const handleSend = () => {
    if (msg.trim()) {
      // Chat-about is per-question: send the question id + its text so the agent
      // has the right context, regardless of which options were offered.
      window._chatAboutOption?.(questionId, subject, msg.trim());
      onClose();
    }
  };

  return (
    <div
      className="rounded-lg p-3 animate-[fade-in_150ms_ease-out]"
      style={{ backgroundColor: 'var(--tool-bg)' }}
    >
      <div className="text-[11px] mb-2" style={{ color: 'var(--fg-muted)' }}>
        Ask about this question
        <Badge variant="secondary" className="text-[10px] ml-1">{subject}</Badge>
      </div>
      <div className="flex gap-1.5">
        <input
          autoFocus
          value={msg}
          onChange={e => setMsg(e.target.value)}
          onKeyDown={e => { if (e.key === 'Enter') handleSend(); if (e.key === 'Escape') onClose(); }}
          placeholder="What would you like to know about this question?"
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
  // When the user clicks "Edit answer" on the review summary we navigate back to
  // that question locally (the Kotlin _editQuestion callback is a no-op — editing
  // is a pure webview concern). editingIndex overrides activeIndex while editing
  // and suppresses the all-answered auto-summary so the question card is reachable.
  const [editingIndex, setEditingIndex] = useState<number | null>(null);
  const [chatAbout, setChatAbout] = useState<{ qid: string; questionText: string } | null>(null);
  const [customText, setCustomText] = useState('');
  const [customSelected, setCustomSelected] = useState(false);
  // Mirror of QuestionFlow's live option selection so a multi-select answer can
  // UNION checked options with a free-text "Other" value (instead of either path
  // overwriting the other).
  const [liveSelectedIds, setLiveSelectedIds] = useState<string[]>([]);
  const handleSelectionChange = useCallback((ids: string[]) => setLiveSelectedIds(ids), []);
  const storeAnswer = useChatStore(s => s.answerQuestion);
  const storeSkip = useChatStore(s => s.skipQuestion);
  const storeClear = useChatStore(s => s.clearQuestions);
  // The wizard's source of truth for which question is active. Forward/back
  // navigation drives this (nothing else advanced it — multi-question wizards
  // were stuck on the first question).
  const storeShowQuestion = useChatStore(s => s.showQuestion);

  // Single owned handle for the "reveal summary" delay. Tracked so navigation
  // (Back / Skip / Cancel) and unmount can cancel it — otherwise a pending timer
  // fires setShowSummary against stale/cleared questions and flashes the summary.
  const summaryTimerRef = useRef<ReturnType<typeof setTimeout> | null>(null);
  const cancelSummaryTimer = useCallback(() => {
    if (summaryTimerRef.current !== null) {
      clearTimeout(summaryTimerRef.current);
      summaryTimerRef.current = null;
    }
  }, []);
  const scheduleSummary = useCallback(() => {
    cancelSummaryTimer();
    summaryTimerRef.current = setTimeout(() => {
      summaryTimerRef.current = null;
      setShowSummary(true);
    }, 300);
  }, [cancelSummaryTimer]);
  // Cancel any pending timer on unmount.
  useEffect(() => cancelSummaryTimer, [cancelSummaryTimer]);

  // While editing, the shown question is the edit target, not the live activeIndex.
  const shownIndex = editingIndex ?? activeIndex;
  const question = questions[shownIndex];
  const isLastQuestion = shownIndex === questions.length - 1;

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
  const submitAnswer = useCallback((answer: string[]) => {
    if (!question) return;
    setCustomSelected(false);
    storeAnswer(question.id, answer);
    window._questionAnswered?.(question.id, JSON.stringify(answer));
    if (editingIndex !== null) {
      // Finished editing one answer → return to the review summary.
      cancelSummaryTimer();
      setEditingIndex(null);
      setShowSummary(true);
    } else if (isLastQuestion) {
      scheduleSummary();
    } else {
      // Advance to the next question.
      storeShowQuestion(shownIndex + 1);
    }
  }, [question, editingIndex, isLastQuestion, shownIndex, storeAnswer, scheduleSummary, cancelSummaryTimer, storeShowQuestion]);

  const handleSelect = useCallback((optionIds: string[]) => {
    // Multi-select: fold a free-text "Other" value into the option set so the two
    // can coexist. Single-select stays mutually exclusive (option wins).
    const custom = customSelected && customText.trim() ? [customText.trim()] : [];
    const answer = question?.type === 'multi-select'
      ? Array.from(new Set([...optionIds, ...custom]))
      : optionIds;
    submitAnswer(answer);
  }, [question, customSelected, customText, submitAnswer]);

  const handleCustomSubmit = useCallback(() => {
    if (!question || !customText.trim()) return;
    const custom = customText.trim();
    // Multi-select: union the live option selection with the "Other" value so
    // submitting from the Other field keeps the checked options. Single-select
    // replaces (the custom answer IS the single choice).
    const answer = question.type === 'multi-select'
      ? Array.from(new Set([...liveSelectedIds, custom]))
      : [custom];
    setCustomText('');
    submitAnswer(answer);
  }, [question, customText, liveSelectedIds, submitAnswer]);

  const handleTextAnswer = useCallback((text: string) => {
    submitAnswer([text]);
  }, [submitAnswer]);

  const handleBack = useCallback(() => {
    cancelSummaryTimer();
    setCustomSelected(false);
    if (editingIndex !== null) {
      // Back out of an edit → return to the review summary.
      setEditingIndex(null);
      setShowSummary(true);
      return;
    }
    if (shownIndex <= 0) return;
    window._editQuestion?.(questions[shownIndex - 1]!.id);
    storeShowQuestion(shownIndex - 1);
  }, [editingIndex, shownIndex, questions, cancelSummaryTimer, storeShowQuestion]);

  const handleSkip = useCallback(() => {
    if (!question) return;
    cancelSummaryTimer();
    setCustomSelected(false);
    storeSkip(question.id);
    window._questionSkipped?.(question.id);
    if (editingIndex !== null) {
      setEditingIndex(null);
      setShowSummary(true);
    } else if (!isLastQuestion) {
      // Advance to the next question (skipping the last lets the all-answered
      // guard surface the summary once the store update flows back).
      storeShowQuestion(shownIndex + 1);
    }
  }, [question, editingIndex, isLastQuestion, shownIndex, storeSkip, cancelSummaryTimer, storeShowQuestion]);

  const handleEditFromSummary = useCallback((index: number) => {
    cancelSummaryTimer();
    setShowSummary(false);
    setCustomSelected(false);
    setEditingIndex(index);
    window._editQuestion?.(questions[index]!.id);
  }, [questions, cancelSummaryTimer]);

  const handleSubmitAll = useCallback(() => {
    window._questionsSubmitted?.();
  }, []);

  const handleCancel = useCallback(() => {
    cancelSummaryTimer();
    setCustomSelected(false);
    storeClear();
    window._questionsCancelled?.();
  }, [storeClear, cancelSummaryTimer]);

  // ── Summary page ── (suppressed while editing a specific answer)
  if ((showSummary || allAnswered) && editingIndex === null) {
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
                : !q.answer
                  ? 'No answer'
                  : answerToDisplay(q.options, q.answer);

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
        {/* Optional per-question header chip — scannable tag above the question text */}
        {question.header ? (
          <div className="px-4 pt-3">
            <span
              data-testid="question-header"
              className="inline-block rounded-full px-2 py-0.5 text-[11px] font-semibold uppercase tracking-wide"
              style={{ color: 'var(--accent-fg, var(--fg))', backgroundColor: 'var(--accent-bg, var(--hover))' }}
            >
              {question.header}
            </span>
          </div>
        ) : null}
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
                step={shownIndex + 1}
                title={<MarkdownRenderer content={question.text} />}
                titleText={question.text}
                options={flowOptions}
                selectionMode={selectionMode}
                defaultValue={defaultValue}
                onSelect={handleSelect}
                onSelectionChange={handleSelectionChange}
                onBack={(editingIndex !== null || shownIndex > 0) ? handleBack : undefined}
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
              ? () => setChatAbout({ qid: question.id, questionText: question.text })
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
              subject={chatAbout.questionText}
              onClose={() => setChatAbout(null)}
            />
          </div>
        )}

        {/* Step dots — inside the card */}
        <StepDots
          total={questions.length}
          current={shownIndex}
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
