import type { UiMessage } from '@/bridge/types';
import { CheckCircle2 } from 'lucide-react';

interface PlanApprovedBubbleProps {
  message: UiMessage;
}

export function PlanApprovedBubble({ message: _message }: PlanApprovedBubbleProps) {
  return (
    <div className="flex justify-end w-full animate-[message-enter_220ms_ease-out_both]">
      <div
        className="max-w-[85%] rounded-lg border p-3 bg-[var(--user-bg)]"
        style={{ borderColor: 'var(--border)' }}
      >
        <div className="flex items-center gap-2">
          <CheckCircle2 className="w-4 h-4 shrink-0" style={{ color: 'var(--accent, #6366f1)' }} />
          <span className="text-[13px] text-[var(--fg)]">Implementation plan approved</span>
          <button
            className="ml-auto text-[13px] cursor-pointer bg-transparent border-none p-0
              underline decoration-[var(--link)]/30 hover:decoration-[var(--link)]"
            style={{ color: 'var(--link)' }}
            onClick={() => window._openApprovedPlan?.()}
          >
            View implementation plan
          </button>
        </div>
      </div>
    </div>
  );
}
