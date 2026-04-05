import { useState, useCallback } from 'react';

interface ProcessInputViewProps {
  processId: string;
  description: string;
  prompt: string;
  command: string;
  onSubmit: (input: string) => void;
}

export function ProcessInputView({ processId: _processId, description, prompt, command, onSubmit }: ProcessInputViewProps) {
  const [input, setInput] = useState('');

  const handleSubmit = useCallback(() => {
    if (input.length === 0) return;
    const withNewline = input.endsWith('\n') ? input : input + '\n';
    onSubmit(withNewline);
  }, [input, onSubmit]);

  const handleKeyDown = useCallback((e: React.KeyboardEvent) => {
    if (e.key === 'Enter' && !e.shiftKey) {
      e.preventDefault();
      handleSubmit();
    }
  }, [handleSubmit]);

  return (
    <div
      className="flex flex-col gap-2 rounded-lg border px-3 py-2.5"
      style={{ backgroundColor: 'var(--tool-bg, var(--bg))', borderColor: 'var(--accent-edit, #f59e0b)' }}
    >
      <div className="flex items-center gap-2">
        <span
          className="inline-block size-2 rounded-full"
          style={{ background: 'var(--accent-edit, #f59e0b)', animation: 'approval-breathe 2s ease-in-out infinite' }}
        />
        <span className="text-[12px] font-semibold" style={{ color: 'var(--fg)' }}>
          Process input requested
        </span>
      </div>

      <p className="text-[12px]" style={{ color: 'var(--fg-secondary)' }}>
        {description}
      </p>

      {prompt && (
        <code
          className="block rounded px-2 py-1 text-[11px] font-mono"
          style={{ backgroundColor: 'var(--code-bg)', color: 'var(--fg-muted)' }}
        >
          {prompt}
        </code>
      )}

      <div
        className="flex items-center gap-1.5 rounded px-2 py-1 text-[10px]"
        style={{ backgroundColor: 'var(--badge-edit-bg, var(--tool-bg))', color: 'var(--badge-edit-fg, var(--accent-edit, #f59e0b))' }}
      >
        <span>This input will be sent to:</span>
        <code className="font-mono">{command.length > 50 ? command.slice(0, 47) + '...' : command}</code>
      </div>

      <div className="flex items-center gap-1.5">
        <input
          type="text"
          value={input}
          onChange={e => setInput(e.target.value)}
          onKeyDown={handleKeyDown}
          placeholder="Enter input..."
          className="flex-1 rounded-md px-2 py-1.5 text-[12px] font-mono outline-none"
          style={{
            backgroundColor: 'var(--input-bg, var(--bg))',
            color: 'var(--fg)',
            border: '1px solid var(--border)',
          }}
          autoFocus
        />
        <button
          onClick={handleSubmit}
          disabled={input.length === 0}
          className="rounded-md px-3 py-1.5 text-[11px] font-medium transition-colors disabled:opacity-40"
          style={{ backgroundColor: 'var(--accent-edit, #f59e0b)', color: '#fff' }}
        >
          Send
        </button>
      </div>
    </div>
  );
}
