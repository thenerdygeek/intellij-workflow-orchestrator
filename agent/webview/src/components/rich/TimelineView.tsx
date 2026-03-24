import { useMemo } from 'react';
import { RichBlock } from './RichBlock';

interface TimelineEvent {
  time: string;
  label: string;
  description?: string;
  status?: 'info' | 'success' | 'warning' | 'error';
}

interface TimelineData {
  title?: string;
  events: TimelineEvent[];
}

interface TimelineViewProps {
  timelineSource: string;
}

const STATUS_COLORS: Record<string, string> = {
  info: 'var(--accent)',
  success: 'var(--success)',
  warning: 'var(--warning)',
  error: 'var(--error)',
};

export function TimelineView({ timelineSource }: TimelineViewProps) {
  const data = useMemo<TimelineData | null>(() => {
    try { return JSON.parse(timelineSource); } catch { return null; }
  }, [timelineSource]);

  if (!data) {
    return (
      <RichBlock type="timeline" source={timelineSource}>
        <pre className="p-3 text-[11px] font-mono" style={{ color: 'var(--error)' }}>
          Failed to parse timeline data
        </pre>
      </RichBlock>
    );
  }

  return (
    <RichBlock type="timeline" source={timelineSource}>
      <div className="p-4">
        {data.title && (
          <div className="text-[13px] font-semibold mb-4" style={{ color: 'var(--fg)' }}>
            {data.title}
          </div>
        )}

        <div className="relative ml-4">
          {/* Vertical connecting line */}
          <div
            className="absolute left-0 top-2 bottom-2 w-[2px]"
            style={{ backgroundColor: 'var(--border)' }}
          />

          {data.events.map((event, i) => {
            const color = STATUS_COLORS[event.status ?? 'info'] ?? STATUS_COLORS.info;
            return (
              <div
                key={i}
                className="relative flex gap-4 pb-4 last:pb-0 animate-[fade-in_200ms_ease-out_both]"
                style={{ animationDelay: `${i * 80}ms` }}
              >
                {/* Dot on the line */}
                <div
                  className="absolute left-0 top-1.5 -translate-x-1/2 h-2.5 w-2.5 rounded-full border-2 shrink-0 z-10"
                  style={{ backgroundColor: color, borderColor: 'var(--bg)' }}
                />

                {/* Time */}
                <div
                  className="pl-5 shrink-0 text-[11px] font-mono tabular-nums w-14 text-right"
                  style={{ color: 'var(--fg-muted)' }}
                >
                  {event.time}
                </div>

                {/* Content */}
                <div className="flex-1 min-w-0">
                  <div className="text-[12px]" style={{ color: 'var(--fg)' }}>
                    {event.label}
                  </div>
                  {event.description && (
                    <div className="text-[11px] mt-0.5" style={{ color: 'var(--fg-muted)' }}>
                      {event.description}
                    </div>
                  )}
                </div>
              </div>
            );
          })}
        </div>
      </div>
    </RichBlock>
  );
}
