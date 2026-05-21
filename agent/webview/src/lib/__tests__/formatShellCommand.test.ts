import { describe, it, expect } from 'vitest';
import { detectShellKind, formatShellCommand } from '@/lib/formatShellCommand';

describe('detectShellKind', () => {
  it.each([
    ['/bin/bash', 'bash'],
    ['/usr/local/bin/zsh', 'bash'],
    ['/bin/sh', 'bash'],
    ['bash', 'bash'],
    ['fish', 'bash'],
    ['powershell.exe', 'powershell'],
    ['C:\\Program Files\\PowerShell\\7\\pwsh.exe', 'powershell'],
    ['pwsh', 'powershell'],
    ['cmd.exe', 'cmd'],
    ['C:\\Windows\\System32\\cmd.exe', 'cmd'],
    ['', 'unknown'],
    ['something-weird', 'unknown'],
    ['/opt/myshell', 'unknown'],
  ])('detectShellKind(%j) === %j', (input, expected) => {
    expect(detectShellKind(input)).toBe(expected);
  });
});

describe('formatShellCommand', () => {
  it('leaves a short command unchanged', () => {
    expect(formatShellCommand('git status', '/bin/bash')).toBe('git status');
  });

  it('leaves a command without operators unchanged even when long', () => {
    const cmd = 'npm install --save-dev @testing-library/react @testing-library/jest-dom';
    expect(formatShellCommand(cmd, '/bin/bash')).toBe(cmd);
  });

  it('leaves an already-multiline command untouched', () => {
    const cmd = 'echo a\necho b\necho c';
    expect(formatShellCommand(cmd, '/bin/bash')).toBe(cmd);
  });

  it('returns the input unchanged when the shell is unknown', () => {
    const cmd = 'git fetch origin main && git rebase origin/main && git status';
    expect(formatShellCommand(cmd, '/opt/weirdshell')).toBe(cmd);
  });

  it('formats a chained bash command with backslash continuations', () => {
    const cmd = 'git fetch origin main && git rebase origin/main && git status';
    expect(formatShellCommand(cmd, '/bin/bash')).toBe(
      'git fetch origin main && \\\n' +
      '    git rebase origin/main && \\\n' +
      '    git status'
    );
  });

  it('formats a chained powershell command with backtick continuations', () => {
    const cmd = 'git fetch origin main && git rebase origin/main && git status';
    expect(formatShellCommand(cmd, 'pwsh.exe')).toBe(
      'git fetch origin main && `\n' +
      '    git rebase origin/main && `\n' +
      '    git status'
    );
  });

  it('formats a chained cmd.exe command with caret continuations', () => {
    const cmd = 'git fetch origin main && git rebase origin/main && git status';
    expect(formatShellCommand(cmd, 'C:\\Windows\\System32\\cmd.exe')).toBe(
      'git fetch origin main && ^\n' +
      '    git rebase origin/main && ^\n' +
      '    git status'
    );
  });

  it('breaks on a pipe operator at the top level', () => {
    const cmd = 'git log --oneline --since="last week" | grep -v Merge | head -n 20';
    const out = formatShellCommand(cmd, '/bin/bash');
    expect(out).toBe(
      'git log --oneline --since="last week" | \\\n' +
      '    grep -v Merge | \\\n' +
      '    head -n 20'
    );
  });

  it('breaks on a semicolon operator at the top level', () => {
    const cmd = 'cd /tmp/very/deep/build/output ; rm -rf old ; tar -czf archive.tgz new';
    expect(formatShellCommand(cmd, '/bin/bash')).toBe(
      'cd /tmp/very/deep/build/output ; \\\n' +
      '    rm -rf old ; \\\n' +
      '    tar -czf archive.tgz new'
    );
  });

  it('does not split on operators that appear inside double quotes', () => {
    const cmd = 'git commit -m "feat: a && b || c" && git push origin main';
    expect(formatShellCommand(cmd, '/bin/bash')).toBe(
      'git commit -m "feat: a && b || c" && \\\n' +
      '    git push origin main'
    );
  });

  it('does not split on operators that appear inside single quotes', () => {
    const cmd = "echo 'pipe me | through this' && wc -l /tmp/somefile.long.path";
    expect(formatShellCommand(cmd, '/bin/bash')).toBe(
      "echo 'pipe me | through this' && \\\n" +
      "    wc -l /tmp/somefile.long.path"
    );
  });

  it('does not split inside a parenthesized subshell', () => {
    const cmd = '(cd build && make && make test) || echo "build failed and we stop here"';
    expect(formatShellCommand(cmd, '/bin/bash')).toBe(
      '(cd build && make && make test) || \\\n' +
      '    echo "build failed and we stop here"'
    );
  });

  it('respects bash backslash escapes outside quotes', () => {
    // `\&\&` is two escaped ampersands, not an && operator. So no split occurs there.
    const cmd = 'echo before \\&\\& after && echo final-segment-long-enough-to-trigger';
    expect(formatShellCommand(cmd, '/bin/bash')).toBe(
      'echo before \\&\\& after && \\\n' +
      '    echo final-segment-long-enough-to-trigger'
    );
  });

  it("treats backslash as literal inside single quotes (so '\\'' apostrophe idiom works)", () => {
    // Bash idiom: 'it'\''s' -- single-quote closes, escaped apostrophe, single-quote reopens.
    const cmd = "echo 'it'\\''s a long enough string xx' && echo y";
    expect(formatShellCommand(cmd, '/bin/bash')).toBe(
      "echo 'it'\\''s a long enough string xx' && \\\n" +
      "    echo y"
    );
  });
});
