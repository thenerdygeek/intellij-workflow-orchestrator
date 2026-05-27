import { memo } from 'react';

interface DropOverlayProps { active: boolean; }

export const DropOverlay = memo(function DropOverlay({ active }: DropOverlayProps) {
  if (!active) return null;
  return (
    <div
      className="absolute inset-0 z-20 flex items-center justify-center rounded-xl pointer-events-none"
      style={{
        border: '2px dashed var(--accent, #3b82f6)',
        background: 'color-mix(in srgb, var(--accent, #3b82f6) 8%, transparent)',
      }}
      aria-hidden="true"
    >
      <span className="text-sm font-medium" style={{ color: 'var(--accent, #3b82f6)' }}>
        Drop files to attach
      </span>
    </div>
  );
});
