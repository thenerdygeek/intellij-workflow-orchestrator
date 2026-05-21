export type ShellKind = 'bash' | 'powershell' | 'cmd' | 'unknown';

export function detectShellKind(shell: string | undefined | null): ShellKind {
  if (!shell) return 'unknown';
  const s = shell.toLowerCase().replace(/\\/g, '/');
  const leaf = s.slice(s.lastIndexOf('/') + 1);
  if (leaf === 'pwsh' || leaf === 'pwsh.exe' || leaf === 'powershell' || leaf === 'powershell.exe') {
    return 'powershell';
  }
  if (leaf === 'cmd' || leaf === 'cmd.exe') {
    return 'cmd';
  }
  if (leaf === 'bash' || leaf === 'bash.exe' ||
      leaf === 'zsh' || leaf === 'ksh' || leaf === 'sh' ||
      leaf === 'dash' || leaf === 'fish') {
    return 'bash';
  }
  return 'unknown';
}

const CONTINUATION: Record<Exclude<ShellKind, 'unknown'>, string> = {
  bash: '\\',
  powershell: '`',
  cmd: '^',
};

const MIN_LENGTH_FOR_FORMAT = 40;
const INDENT = '    ';

interface Split {
  segment: string;
}

function splitTopLevel(cmd: string): Split[] {
  const out: Split[] = [];
  let start = 0;
  let i = 0;
  let paren = 0;
  let brace = 0;
  let bracket = 0;
  let quote: '"' | "'" | '`' | null = null;

  while (i < cmd.length) {
    const c = cmd[i];
    const c2 = cmd[i + 1];

    if (c === '\\' && quote !== "'") {
      i += 2;
      continue;
    }

    if (quote) {
      if (c === quote) quote = null;
      i++;
      continue;
    }

    if (c === '"' || c === "'" || c === '`') {
      quote = c;
      i++;
      continue;
    }

    if (c === '(') { paren++; i++; continue; }
    if (c === ')') { paren = Math.max(0, paren - 1); i++; continue; }
    if (c === '{') { brace++; i++; continue; }
    if (c === '}') { brace = Math.max(0, brace - 1); i++; continue; }
    if (c === '[') { bracket++; i++; continue; }
    if (c === ']') { bracket = Math.max(0, bracket - 1); i++; continue; }

    if (paren === 0 && brace === 0 && bracket === 0) {
      let opLen = 0;
      if ((c === '&' && c2 === '&') || (c === '|' && c2 === '|')) {
        opLen = 2;
      } else if (c === ';' || c === '|') {
        opLen = 1;
      }
      if (opLen > 0) {
        out.push({ segment: cmd.slice(start, i + opLen) });
        start = i + opLen;
        i += opLen;
        continue;
      }
    }

    i++;
  }

  if (start < cmd.length) {
    out.push({ segment: cmd.slice(start) });
  }

  return out;
}

export function formatShellCommand(command: string, shell: string | undefined | null): string {
  if (!command || command.includes('\n')) return command;
  if (command.length < MIN_LENGTH_FOR_FORMAT) return command;

  const kind = detectShellKind(shell);
  if (kind === 'unknown') return command;

  const splits = splitTopLevel(command);
  if (splits.length < 2) return command;

  const continuation = CONTINUATION[kind];
  const lines: string[] = [];
  for (let i = 0; i < splits.length; i++) {
    const text = splits[i]!.segment.trim();
    if (!text) continue;
    const prefix = lines.length === 0 ? '' : INDENT;
    if (i === splits.length - 1) {
      lines.push(prefix + text);
    } else {
      lines.push(prefix + text + ' ' + continuation);
    }
  }
  return lines.join('\n');
}
