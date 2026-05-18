interface Props { ts: number; }

export function UserMessageRevertButton({ ts }: Props) {
  return (
    <button
      onClick={() => window._revertToUserMessage?.(ts)}
      className="opacity-0 group-hover:opacity-100 transition-opacity text-[11px] hover:underline"
      style={{ color: 'var(--link)' }}
      aria-label="Time-travel to this message"
      title="Time-travel: revert files & chat to this point, restore message to input"
    >
      ⟲ Time-travel here
    </button>
  );
}
