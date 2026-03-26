import { memo } from 'react';
import { useChatStore } from '@/stores/chatStore';
import { kotlinBridge } from '@/bridge/jcef-bridge';

/**
 * Skill active banner — shown below TopBar when a skill is active.
 * Displays skill name with a deactivate button.
 */
export const SkillBanner = memo(function SkillBanner() {
  const skillName = useChatStore(s => s.skillBanner);

  if (!skillName) return null;

  return (
    <div
      className="flex items-center justify-between px-3 py-1 shrink-0 select-none animate-[fade-in_200ms_ease-out]"
      style={{
        borderBottom: '1px solid var(--border, #333)',
        background: 'color-mix(in srgb, var(--accent, #3b82f6) 8%, var(--bg, #1e1e1e))',
      }}
    >
      <div className="flex items-center gap-1.5 min-w-0">
        {/* Skill icon */}
        <svg
          width="12"
          height="12"
          viewBox="0 0 16 16"
          fill="none"
          style={{ color: 'var(--accent, #3b82f6)', flexShrink: 0 }}
        >
          <path
            d="M8 1L10 5.5L15 6.5L11.5 10L12.5 15L8 12.5L3.5 15L4.5 10L1 6.5L6 5.5L8 1Z"
            stroke="currentColor"
            strokeWidth="1.2"
            strokeLinecap="round"
            strokeLinejoin="round"
          />
        </svg>
        <span
          className="text-[11px] font-medium truncate"
          style={{ color: 'var(--accent, #3b82f6)' }}
        >
          {skillName}
        </span>
        <span
          className="text-[10px]"
          style={{ color: 'var(--fg-muted, #6b7280)' }}
        >
          active
        </span>
      </div>

      <button
        onClick={() => kotlinBridge.deactivateSkill()}
        className="flex items-center rounded px-1.5 py-0.5 text-[10px] font-medium transition-colors hover:bg-[var(--hover-overlay,rgba(255,255,255,0.06))]"
        style={{ color: 'var(--fg-muted, #6b7280)' }}
        title="Deactivate skill"
        aria-label={`Deactivate ${skillName} skill`}
      >
        <svg width="10" height="10" viewBox="0 0 16 16" fill="none">
          <path d="M4 4L12 12M12 4L4 12" stroke="currentColor" strokeWidth="1.5" strokeLinecap="round" />
        </svg>
      </button>
    </div>
  );
});
