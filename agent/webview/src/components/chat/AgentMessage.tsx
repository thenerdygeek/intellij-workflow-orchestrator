import { memo, useEffect, useMemo, useRef } from 'react';
import type { UiMessage, Mention, Question } from '@/bridge/types';
import { useChatStore } from '@/stores/chatStore';
import { MarkdownRenderer } from '@/components/markdown/MarkdownRenderer';
import {
  Message as PkMessage,
  MessageAvatar,
} from '@/components/ui/prompt-kit/message';
import { CopyButton } from '@/components/ui/copy-button';
import { UserMessageRevertButton } from '@/components/agent/UserMessageRevertButton';
import { cn } from '@/lib/utils';
import { PlanApprovedBubble } from './PlanApprovedBubble';
import { scanAndLinkify } from '@/util/file-link-scanner';
import { scanAndSymbolLinkify } from '@/util/symbol-link-scanner';

// ── Mention chip rendering for user message bubbles ──

const mentionChipColors: Record<string, { color: string; bg: string; border: string }> = {
  file:   { color: 'var(--accent-read, #3b82f6)', bg: 'rgba(59,130,246,0.1)',  border: 'rgba(59,130,246,0.25)' },
  folder: { color: 'var(--accent-read, #3b82f6)', bg: 'rgba(59,130,246,0.08)', border: 'rgba(59,130,246,0.2)' },
  symbol: { color: 'var(--accent-search, #a78bfa)', bg: 'color-mix(in srgb, var(--accent-search, #a78bfa) 10%, transparent)', border: 'color-mix(in srgb, var(--accent-search, #a78bfa) 25%, transparent)' },
  ticket: { color: 'var(--accent-read, #3b82f6)', bg: 'rgba(59,130,246,0.1)',  border: 'rgba(59,130,246,0.25)' },
};
const defaultMentionColor = { color: 'var(--fg-secondary)', bg: 'var(--chip-bg, rgba(255,255,255,0.06))', border: 'var(--chip-border, rgba(255,255,255,0.1))' };

/** Prefix character for a mention type — shared between chip rendering and content splitting. */
function mentionPrefix(type: string): string {
  return type === 'skill' ? '/' : type === 'ticket' ? '#' : '@';
}

function MentionChip({ mention }: { mention: Mention }) {
  const colors = mentionChipColors[mention.type] ?? defaultMentionColor;
  return (
    <span
      className="inline-flex items-center rounded px-1 py-0 text-[11px] font-medium mx-0.5 align-baseline"
      style={{ color: colors.color, background: colors.bg, border: `1px solid ${colors.border}`, lineHeight: '1.6' }}
      title={mention.path || mention.label}
    >
      {mentionPrefix(mention.type)}{mention.label}
    </span>
  );
}

type Segment = { type: 'text'; text: string } | { type: 'chip'; mention: Mention };

/**
 * Split content text into text segments and chip segments at mention markers.
 *
 * Limitation: splits on exact string match of the marker (e.g., "@AuthService.kt").
 * Could false-positive if the same substring appears naturally in text. In practice
 * this is rare because: (a) labels are filenames/ticket-keys, not common words,
 * (b) the display text is constructed by the input bar which places markers at exact
 * positions, and (c) each mention is consumed on first match only.
 * A future improvement could add positional offsets to mentions for precise splitting.
 */
function splitContentWithMentions(content: string, mentions: Mention[]): Segment[] {
  let segments: Segment[] = [{ type: 'text', text: content }];

  for (const mention of mentions) {
    const marker = `${mentionPrefix(mention.type)}${mention.label}`;
    const next: Segment[] = [];
    for (const seg of segments) {
      if (seg.type === 'chip') { next.push(seg); continue; }
      const parts = seg.text.split(marker);
      for (let i = 0; i < parts.length; i++) {
        const part = parts[i]!;
        if (part) next.push({ type: 'text', text: part });
        if (i < parts.length - 1) next.push({ type: 'chip', mention });
      }
    }
    segments = next;
  }
  return segments;
}

/** Frozen Q&A snapshot rendered when an ask_questions wizard has been submitted. */
export function AnsweredQuestionsCard({ questions }: { questions: Question[] }) {
  return (
    <div
      className="my-1 rounded-lg border p-3"
      style={{ borderColor: 'var(--border)', backgroundColor: 'var(--card, var(--user-bg))' }}
    >
      <div className="mb-2 text-[11px] font-medium uppercase tracking-wide" style={{ color: 'var(--fg-secondary)' }}>
        Answered {questions.length} question{questions.length === 1 ? '' : 's'}
      </div>
      <ul className="space-y-2.5">
        {questions.map((q, i) => {
          const skipped = q.skipped;
          const answers = Array.isArray(q.answer) ? q.answer : (q.answer ? [q.answer] : []);
          return (
            <li key={q.id ?? i} className="text-[12px] leading-snug">
              <div className="font-medium flex gap-1 [&_p]:m-0 [&_p]:inline" style={{ color: 'var(--fg)' }}>
                <span className="shrink-0">{i + 1}.</span>
                <MarkdownRenderer content={q.text} />
              </div>
              <div className="mt-0.5 pl-3" style={{ color: skipped ? 'var(--fg-muted)' : 'var(--fg-secondary)' }}>
                {skipped ? (
                  <span className="italic">Skipped</span>
                ) : answers.length > 0 ? (
                  <span>{answers.join(', ')}</span>
                ) : (
                  <span className="italic">No answer</span>
                )}
              </div>
            </li>
          );
        })}
      </ul>
    </div>
  );
}

function UserContent({ content, mentions }: { content: string; mentions?: Mention[] }) {
  if (!mentions || mentions.length === 0) {
    return <p className="text-[13px] leading-relaxed whitespace-pre-wrap">{content}</p>;
  }
  const segments = splitContentWithMentions(content, mentions);
  return (
    <p className="text-[13px] leading-relaxed whitespace-pre-wrap">
      {segments.map((seg, i) =>
        seg.type === 'text'
          ? <span key={i}>{seg.text}</span>
          : <MentionChip key={i} mention={seg.mention} />
      )}
    </p>
  );
}

interface AgentMessageProps {
  message: UiMessage;
  isStreaming?: boolean;
}

export const AgentMessage = memo(function AgentMessage({
  message,
  isStreaming = false,
}: AgentMessageProps) {
  const contentRef = useRef<HTMLDivElement>(null);
  const editorTabMode = useChatStore((s) => s.editorTabMode);

  const isUser = message.say === 'USER_MESSAGE';
  const isFinalized = !isStreaming && !isUser;

  // Memoize the content string so the scanner effect's deps key off a stable
  // primitive value, not the parent's UiMessage object reference. When a
  // parent re-renders with a fresh `message` object but the same `text`,
  // `scanContent` keeps its identity and the effect doesn't re-fire. When
  // content actually changes (e.g., resumed-session edit), the effect re-runs
  // and re-scans the new DOM.
  const scanContent = useMemo(() => message.text ?? '', [message.text]);

  useEffect(() => {
    if (!isFinalized) return;
    const node = contentRef.current;
    if (!node) return;
    // Cooperative scheduling: idle callback if available (Chromium / JCEF),
    // rAF fallback for jsdom. On a 200-message resume this lets the browser
    // interleave layout/paint with linkify work instead of queueing 200
    // back-to-back rAFs.
    const ric = (window as Window & { requestIdleCallback?: typeof requestIdleCallback }).requestIdleCallback;
    if (ric) {
      const handle = ric(() => {
        void scanAndLinkify(node);
        void scanAndSymbolLinkify(node);
      }, { timeout: 200 });
      return () => (window as Window & { cancelIdleCallback?: typeof cancelIdleCallback }).cancelIdleCallback?.(handle);
    }
    const handle = requestAnimationFrame(() => {
      void scanAndLinkify(node);
      void scanAndSymbolLinkify(node);
    });
    return () => cancelAnimationFrame(handle);
  }, [isFinalized, scanContent]);

  if (message.say === 'PLAN_APPROVED') {
    return <PlanApprovedBubble message={message} />;
  }

  const content = message.text ?? '';

  // Editor-tab fullscreen: skip avatar / "Agent" label / max-w-[85%] bubble
  // chrome and render the markdown directly. The flex chain below propagates
  // height all the way down to the artifact iframe / mermaid SVG.
  if (editorTabMode) {
    return (
      <div ref={contentRef} className="flex h-full w-full flex-col">
        <MarkdownRenderer content={content} isStreaming={isStreaming} />
      </div>
    );
  }

  return (
    <PkMessage
      className={cn(
        'group w-full',
        Date.now() - message.ts < 1000 && 'animate-[message-enter_220ms_ease-out_both]',
        isUser ? 'flex-row-reverse' : '',
      )}
    >
      {/* Agent avatar (non-user messages only) */}
      {!isUser && (
        <MessageAvatar
          fallback="A"
          className="h-5 w-5 bg-[var(--accent,#6366f1)] text-[10px] font-bold text-[var(--bg)]"
        />
      )}

      {/* Content bubble */}
      <div
        ref={contentRef}
        className={cn(
          'relative max-w-[85%] rounded-lg px-4 py-3 whitespace-normal [overflow-wrap:anywhere]',
          isUser
            ? 'group bg-[var(--user-bg)] text-[var(--fg)]'
            : 'bg-transparent text-[var(--fg)]',
        )}
      >
        {!isUser && (
          <span className="mb-1 block text-[11px] font-medium text-[var(--fg-secondary)]">
            Agent
          </span>
        )}

        {/* Multimodal-agent Phase 6 — image+tools two-step workaround badge.
            Surfaces the routing detail without visually fragmenting the chat:
            user sees ONE assistant message, with a small strip noting that
            the image was analyzed in a separate request to enable tool use. */}
        {!isUser && message.analyzedImageBadge && (
          <div
            className="analyzed-image-badge mb-1 inline-flex items-center gap-1 rounded px-1.5 py-0.5 text-[11px]"
            title="Image was analyzed in a separate request to enable tool use."
            style={{
              color: 'var(--fg-muted)',
              background: 'var(--chip-bg, rgba(255,255,255,0.06))',
              border: '1px solid var(--chip-border, rgba(255,255,255,0.1))',
            }}
            aria-label="Image was analyzed in a separate request"
          >
            <span aria-hidden="true">📷</span>
            <span>image analyzed</span>
          </div>
        )}

        {isUser ? (
          <>
            <div className="absolute right-0 -top-9 opacity-0 group-hover:opacity-100 transition-opacity">
              <UserMessageRevertButton ts={message.ts} />
            </div>
            {/* Multimodal-agent — image attachments uploaded with this turn.
                Bytes are served from disk by the Kotlin AttachmentReadHandler
                via http://workflow-agent/attachments/{sha256}. */}
            {message.attachments && message.attachments.length > 0 && (
              <div className="mb-2 flex flex-wrap gap-2">
                {message.attachments.map(att => (
                  <img
                    key={att.sha256}
                    src={`http://workflow-agent/attachments/${att.sha256}`}
                    alt={att.originalFilename ?? att.sha256.slice(0, 8)}
                    title={att.originalFilename ?? `${att.mime} · ${Math.round(att.size / 1024)}KB`}
                    className="rounded border max-h-40 max-w-full object-contain"
                    style={{ borderColor: 'var(--input-border, rgba(255,255,255,0.1))' }}
                  />
                ))}
              </div>
            )}
            <UserContent content={content} mentions={message.mentions} />
          </>
        ) : (
          <MarkdownRenderer content={content} isStreaming={isStreaming} />
        )}

        {/* Copy button (visible on hover; available during streaming too —
            captures whatever's been streamed so far, same pattern as CodeBlock). */}
        {content && (
          <CopyButton
            text={content}
            hoverOnly
            label="Copy message"
            className="absolute top-2 right-2"
          />
        )}

        {/* Timestamp on hover */}
        <div className="mt-1 opacity-0 transition-opacity duration-200 group-hover:opacity-100">
          <span className="text-[10px] text-[var(--fg-muted)]">
            {new Date(message.ts).toLocaleTimeString([], {
              hour: '2-digit',
              minute: '2-digit',
            })}
          </span>
        </div>
      </div>
    </PkMessage>
  );
});
