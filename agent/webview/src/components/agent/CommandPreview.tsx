import { CodeBlock } from '@/components/markdown/CodeBlock';

export interface CommandPreviewEnv {
  key: string;
  value: string;
}

export interface CommandPreviewProps {
  command: string;
  shell: string;
  cwd: string;
  env: CommandPreviewEnv[];
  separateStderr?: boolean;
}

export function CommandPreview({
  command,
  shell,
  cwd,
  env,
  separateStderr,
}: CommandPreviewProps) {
  return (
    <div
      data-testid="command-preview"
      className="flex flex-col gap-1.5 rounded-lg border p-0.5"
      style={{
        backgroundColor: 'var(--tool-bg, rgba(0,0,0,0.1))',
        borderColor: 'var(--border)',
      }}
    >
      <CodeBlock code={command} language="bash" isStreaming={false} />
      <div className="flex flex-wrap items-center gap-1 px-2 pb-1 text-[10px]">
        <Chip label={shell} />
        <Chip label={cwd} />
        {separateStderr && <Chip label="separate-stderr" />}
        {env.map((e) => (
          <Chip key={e.key} label={`${e.key}=${e.value}`} />
        ))}
      </div>
    </div>
  );
}

function Chip({ label }: { label: string }) {
  return (
    <span
      className="rounded px-1.5 py-0.5 font-mono"
      style={{
        backgroundColor: 'var(--hover-overlay-strong, rgba(0,0,0,0.15))',
        color: 'var(--fg-secondary)',
      }}
    >
      {label}
    </span>
  );
}
