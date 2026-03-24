import { useMemo } from 'react';
import { RichBlock } from './RichBlock';
import { Progress } from '@/components/ui/progress';
import { Check, Loader2, Clock, X } from 'lucide-react';
import { cn } from '@/lib/utils';

interface ProgressPhase {
  label: string;
  status: 'completed' | 'running' | 'pending' | 'failed';
  progress?: number;
  duration?: string;
}

interface ProgressData {
  title?: string;
  phases: ProgressPhase[];
  overall?: number;
}

interface ProgressViewProps {
  progressSource: string;
}

function PhaseIcon({ status }: { status: ProgressPhase['status'] }) {
  switch (status) {
    case 'completed':
      return <Check className="size-3.5" style={{ color: 'var(--success)' }} />;
    case 'running':
      return <Loader2 className="size-3.5 animate-spin" style={{ color: 'var(--accent)' }} />;
    case 'failed':
      return <X className="size-3.5" style={{ color: 'var(--error)' }} />;
    case 'pending':
      return <Clock className="size-3.5" style={{ color: 'var(--fg-muted)' }} />;
  }
}

export function ProgressView({ progressSource }: ProgressViewProps) {
  const data = useMemo<ProgressData | null>(() => {
    try {
      return JSON.parse(progressSource);
    } catch {
      return null;
    }
  }, [progressSource]);

  if (!data) {
    return (
      <RichBlock type="progress" source={progressSource}>
        <pre className="p-3 text-[11px] font-mono" style={{ color: 'var(--error)' }}>
          Failed to parse progress data
        </pre>
      </RichBlock>
    );
  }

  const { title, phases, overall } = data;
  const computedOverall = overall ?? Math.round(
    (phases.filter(p => p.status === 'completed').length / phases.length) * 100
  );

  return (
    <RichBlock type="progress" source={progressSource}>
      <div className="p-3 space-y-3">
        {/* Header */}
        {title && (
          <div className="flex items-center justify-between">
            <span className="text-[13px] font-semibold" style={{ color: 'var(--fg)' }}>
              {title}
            </span>
            <span className="text-[12px] font-mono tabular-nums" style={{ color: 'var(--accent)' }}>
              {computedOverall}%
            </span>
          </div>
        )}

        {/* Overall progress */}
        <Progress value={computedOverall} className="h-2" />

        {/* Phase list */}
        <div className="space-y-2">
          {phases.map((phase, i) => (
            <div
              key={i}
              className="flex items-center gap-2 animate-[fade-in_200ms_ease-out_both]"
              style={{ animationDelay: `${i * 80}ms` }}
            >
              <PhaseIcon status={phase.status} />
              <span
                className={cn(
                  'flex-1 text-[12px]',
                  phase.status === 'completed' && 'line-through opacity-60',
                )}
                style={{
                  color: phase.status === 'running'
                    ? 'var(--fg)'
                    : phase.status === 'failed'
                      ? 'var(--error)'
                      : 'var(--fg-secondary)',
                }}
              >
                {phase.label}
              </span>
              {phase.status === 'running' && phase.progress != null && (
                <span
                  className="text-[10px] font-mono tabular-nums"
                  style={{ color: 'var(--accent)' }}
                >
                  {phase.progress}%
                </span>
              )}
              {phase.duration && (
                <span
                  className="text-[10px] font-mono tabular-nums"
                  style={{ color: 'var(--fg-muted)' }}
                >
                  {phase.duration}
                </span>
              )}
            </div>
          ))}
        </div>
      </div>
    </RichBlock>
  );
}
