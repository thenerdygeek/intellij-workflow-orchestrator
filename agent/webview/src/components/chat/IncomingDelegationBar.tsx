// Copyright 2025-2026 Workflow Orchestrator Contributors
// SPDX-License-Identifier: Apache-2.0
import { memo, useEffect, useRef, useState } from 'react';
import { useChatStore } from '@/stores/chatStore';
import { kotlinBridge } from '@/bridge/jcef-bridge';

// ── Pure display component (no store coupling — testable via props) ──────────

export interface IncomingDelegationEntry {
  key: string;
  delegatorRepo: string;
  deadlineEpochMs: number;
}

interface IncomingDelegationBarProps {
  /** List of pending incoming delegations to display. */
  delegations: IncomingDelegationEntry[];
  /** Called with the delegation key when the user clicks Start. */
  onStart: (key: string) => void;
  /** Called with the delegation key when the countdown reaches 0. */
  onExpire?: (key: string) => void;
}

/**
 * Single pill for one incoming delegation.
 *
 * Renders the delegator repo name and a live countdown (seconds remaining).
 * When the countdown hits 0, calls `onExpire` so the container can remove the
 * entry. Clicking "Start" calls `onStart` with the delegation key.
 */
const IncomingDelegationPill = memo(function IncomingDelegationPill({
  entry,
  onStart,
  onExpire,
}: {
  entry: IncomingDelegationEntry;
  onStart: (key: string) => void;
  onExpire?: (key: string) => void;
}) {
  const computeSecondsLeft = () =>
    Math.max(0, Math.ceil((entry.deadlineEpochMs - Date.now()) / 1000));

  const [secondsLeft, setSecondsLeft] = useState<number>(computeSecondsLeft);
  const expiredRef = useRef(false);

  useEffect(() => {
    expiredRef.current = false;
    setSecondsLeft(computeSecondsLeft());

    const id = setInterval(() => {
      const s = computeSecondsLeft();
      setSecondsLeft(s);
      if (s === 0 && !expiredRef.current) {
        expiredRef.current = true;
        clearInterval(id);
        onExpire?.(entry.key);
      }
    }, 1000);

    return () => clearInterval(id);
    // Deps intentionally limited to [key, deadline] so the 1s interval is created ONCE per
    // delegation (adding the handlers would reset the interval every render and restart the
    // countdown). Stale-closure-safe by invariant: the captured `onExpire` closes only over the
    // stable zustand `clearIncomingDelegation` action and the immutable `entry.key`, so a stale
    // reference still performs the correct clear. See IncomingDelegationBar container.
    // eslint-disable-next-line react-hooks/exhaustive-deps
  }, [entry.key, entry.deadlineEpochMs]);

  const isUrgent = secondsLeft <= 10;

  return (
    <div
      role="status"
      aria-live="polite"
      className="flex items-center gap-1.5 animate-[fade-in_200ms_ease-out]"
    >
      {/* Inbox / incoming arrow icon */}
      <svg
        width="11"
        height="11"
        viewBox="0 0 16 16"
        fill="none"
        aria-hidden="true"
        style={{ color: 'var(--accent, #3b82f6)', flexShrink: 0 }}
      >
        <path
          d="M2 6V13H14V6M8 2V9M8 9L5 6M8 9L11 6"
          stroke="currentColor"
          strokeWidth="1.4"
          strokeLinecap="round"
          strokeLinejoin="round"
        />
      </svg>

      <span className="text-[10px]" style={{ color: 'var(--fg-muted, #9ca3af)' }}>
        Incoming from{' '}
        <span className="font-medium" style={{ color: 'var(--accent, #3b82f6)' }}>
          {entry.delegatorRepo}
        </span>
      </span>

      {/* Countdown badge */}
      <span
        data-testid="incoming-delegation-countdown"
        className="text-[10px] font-medium tabular-nums"
        style={{ color: isUrgent ? 'var(--error, #ef4444)' : 'var(--fg-muted, #9ca3af)' }}
      >
        {secondsLeft}s
      </span>

      {/* Start button */}
      <button
        data-testid="incoming-delegation-start"
        onClick={() => onStart(entry.key)}
        disabled={secondsLeft === 0}
        className="flex items-center rounded px-1.5 py-0.5 text-[10px] font-medium transition-colors hover:bg-[var(--hover-overlay,rgba(255,255,255,0.1))] disabled:opacity-40 disabled:cursor-not-allowed"
        style={{
          color: 'var(--accent, #3b82f6)',
          border: '1px solid color-mix(in srgb, var(--accent, #3b82f6) 40%, transparent)',
        }}
        aria-label={`Start incoming delegation from ${entry.delegatorRepo}`}
      >
        Start
      </button>
    </div>
  );
});

/**
 * Renders all pending incoming delegations as compact pills in the top bar.
 *
 * Pure component variant (props-driven) is used in unit tests; the mounted
 * container variant (`IncomingDelegationBar`) wires props from the store and
 * `kotlinBridge`.
 */
export const IncomingDelegationBarPure = memo(function IncomingDelegationBarPure({
  delegations,
  onStart,
  onExpire,
}: IncomingDelegationBarProps) {
  if (delegations.length === 0) return null;

  return (
    <div className="flex items-center gap-2 flex-wrap" data-testid="incoming-delegation-bar">
      {delegations.map((entry) => (
        <IncomingDelegationPill
          key={entry.key}
          entry={entry}
          onStart={onStart}
          onExpire={onExpire}
        />
      ))}
    </div>
  );
});

/**
 * Store-connected variant — wires props from `useChatStore` and `kotlinBridge`.
 * Mount this inside `TopBar` (or any other layout slot visible during chat).
 */
export const IncomingDelegationBar = memo(function IncomingDelegationBar() {
  const raw = useChatStore((s) => s.incomingDelegations);
  const clearIncomingDelegation = useChatStore((s) => s.clearIncomingDelegation);

  const delegations: IncomingDelegationEntry[] = Object.entries(raw).map(
    ([key, { delegatorRepo, deadlineEpochMs }]) => ({ key, delegatorRepo, deadlineEpochMs }),
  );

  const handleStart = (key: string) => {
    kotlinBridge.startIncomingDelegation(key);
    // Optimistically remove from the bar; Kotlin will also push
    // _incomingDelegationCleared once the session starts.
    clearIncomingDelegation(key);
  };

  const handleExpire = (key: string) => {
    clearIncomingDelegation(key);
  };

  return (
    <IncomingDelegationBarPure
      delegations={delegations}
      onStart={handleStart}
      onExpire={handleExpire}
    />
  );
});
