interface Props { ts: number; }

export function UserMessageRevertButton({ ts }: Props) {
  return (
    <button
      onClick={(e) => {
        e.stopPropagation();
        const confirmed = window.confirm(
          'Checkpoint to here?\n\n' +
          'This will:\n' +
          '  • Revert file changes made after this message\n' +
          '  • Remove chat messages after this point\n' +
          '  • Restore the message text to your chat input\n\n' +
          'Continue?'
        );
        if (confirmed) {
          window._revertToUserMessage?.(ts);
        }
      }}
      className="px-3 py-1 rounded-md text-[11px] font-medium border shadow-sm transition-colors hover:bg-[var(--hover-overlay)]"
      style={{
        background: 'var(--toolbar-bg)',
        color: 'var(--link)',
        borderColor: 'var(--border)',
      }}
      aria-label="Checkpoint to here"
      title="Revert files & chat to this point, restore message to input"
    >
      ⟲ Checkpoint to here
    </button>
  );
}
