import { memo, useMemo } from 'react';

interface EditDiffViewProps {
  filePath: string;
  oldLines: string[];
  newLines: string[];
  accepted: boolean | null;
}

interface DiffLine {
  type: 'add' | 'remove' | 'context';
  content: string;
  oldNum?: number;
  newNum?: number;
}

/**
 * Compute a simple line-level diff between old and new content.
 * Uses a basic LCS approach for small diffs, falls back to
 * showing all-removed then all-added for larger changes.
 */
function computeLineDiff(oldLines: string[], newLines: string[]): DiffLine[] {
  // For small diffs, use a simple matching approach
  if (oldLines.length + newLines.length > 200) {
    // Large diff: show removed then added
    const result: DiffLine[] = [];
    let oldNum = 1;
    let newNum = 1;
    for (const line of oldLines) {
      result.push({ type: 'remove', content: line, oldNum: oldNum++ });
    }
    for (const line of newLines) {
      result.push({ type: 'add', content: line, newNum: newNum++ });
    }
    return result;
  }

  // Build LCS table
  const m = oldLines.length;
  const n = newLines.length;
  const dp: number[][] = Array.from({ length: m + 1 }, () => Array(n + 1).fill(0));

  for (let i = 1; i <= m; i++) {
    for (let j = 1; j <= n; j++) {
      if (oldLines[i - 1] === newLines[j - 1]) {
        dp[i]![j] = dp[i - 1]![j - 1]! + 1;
      } else {
        dp[i]![j] = Math.max(dp[i - 1]![j]!, dp[i]![j - 1]!);
      }
    }
  }

  // Backtrack to build diff
  const result: DiffLine[] = [];
  let i = m, j = n;

  while (i > 0 || j > 0) {
    if (i > 0 && j > 0 && oldLines[i - 1] === newLines[j - 1]) {
      result.unshift({ type: 'context', content: oldLines[i - 1]!, oldNum: i, newNum: j });
      i--; j--;
    } else if (j > 0 && (i === 0 || dp[i]![j - 1]! >= dp[i - 1]![j]!)) {
      result.unshift({ type: 'add', content: newLines[j - 1]!, newNum: j });
      j--;
    } else {
      result.unshift({ type: 'remove', content: oldLines[i - 1]!, oldNum: i });
      i--;
    }
  }

  return result;
}

export const EditDiffView = memo(function EditDiffView({
  filePath,
  oldLines,
  newLines,
  accepted,
}: EditDiffViewProps) {
  const diffLines = useMemo(() => computeLineDiff(oldLines, newLines), [oldLines, newLines]);

  const addCount = diffLines.filter(l => l.type === 'add').length;
  const removeCount = diffLines.filter(l => l.type === 'remove').length;

  const statusColor = accepted === true
    ? 'var(--success, #22C55E)'
    : accepted === false
      ? 'var(--error, #EF4444)'
      : 'var(--fg-muted, #888)';

  const statusLabel = accepted === true ? 'Applied' : accepted === false ? 'Rejected' : 'Pending';

  // Extract filename from full path
  const fileName = filePath.split('/').pop() ?? filePath;

  return (
    <div
      className="my-2 overflow-hidden rounded-lg animate-[message-enter_220ms_ease-out_both]"
      style={{ border: '1px solid var(--border, #333)' }}
    >
      {/* Header */}
      <div
        className="flex items-center justify-between px-3 py-1.5"
        style={{
          borderBottom: '1px solid var(--border, #333)',
          background: 'var(--code-bg, #1a1a2e)',
        }}
      >
        <div className="flex items-center gap-2 min-w-0">
          <svg width="14" height="14" viewBox="0 0 16 16" fill="none" className="flex-shrink-0">
            <path d="M9.5 1.5H4a1 1 0 00-1 1v11a1 1 0 001 1h8a1 1 0 001-1V4.5L9.5 1.5z" stroke="var(--fg-muted, #888)" strokeWidth="1.2" strokeLinecap="round" strokeLinejoin="round" />
            <path d="M9.5 1.5V4.5H12.5" stroke="var(--fg-muted, #888)" strokeWidth="1.2" strokeLinecap="round" strokeLinejoin="round" />
          </svg>
          <span className="text-[11px] font-mono truncate" style={{ color: 'var(--fg-secondary)' }}>
            {fileName}
          </span>
        </div>
        <div className="flex items-center gap-2 flex-shrink-0">
          <span className="text-[10px] font-mono" style={{ color: 'var(--diff-rem-fg, #f4a5a5)' }}>
            -{removeCount}
          </span>
          <span className="text-[10px] font-mono" style={{ color: 'var(--diff-add-fg, #b5cea8)' }}>
            +{addCount}
          </span>
          <span
            className="text-[10px] font-medium px-1.5 py-0.5 rounded"
            style={{
              color: statusColor,
              background: `color-mix(in srgb, ${statusColor} 10%, transparent)`,
            }}
          >
            {statusLabel}
          </span>
        </div>
      </div>

      {/* Diff content */}
      <div
        className="overflow-auto font-mono text-[12px] leading-[1.6]"
        style={{ maxHeight: '400px', background: 'var(--code-bg, #1a1a2e)' }}
      >
        <table className="w-full border-collapse" style={{ tableLayout: 'fixed' }}>
          <colgroup>
            <col style={{ width: '40px' }} />
            <col style={{ width: '40px' }} />
            <col />
          </colgroup>
          <tbody>
            {diffLines.map((line, i) => {
              let bg = 'transparent';
              let fg = 'var(--fg)';
              let prefix = ' ';

              if (line.type === 'add') {
                bg = 'var(--diff-add-bg, rgba(106,153,85,0.12))';
                fg = 'var(--diff-add-fg, #b5cea8)';
                prefix = '+';
              } else if (line.type === 'remove') {
                bg = 'var(--diff-rem-bg, rgba(244,71,71,0.12))';
                fg = 'var(--diff-rem-fg, #f4a5a5)';
                prefix = '-';
              }

              return (
                <tr key={i} style={{ backgroundColor: bg }}>
                  <td
                    className="select-none text-right pr-1 border-r"
                    style={{
                      color: 'var(--fg-muted, #555)',
                      borderColor: 'var(--border, #333)',
                      fontSize: '10px',
                      paddingTop: '1px',
                      paddingBottom: '1px',
                      userSelect: 'none',
                    }}
                  >
                    {line.type !== 'add' ? line.oldNum : ''}
                  </td>
                  <td
                    className="select-none text-right pr-1 border-r"
                    style={{
                      color: 'var(--fg-muted, #555)',
                      borderColor: 'var(--border, #333)',
                      fontSize: '10px',
                      paddingTop: '1px',
                      paddingBottom: '1px',
                      userSelect: 'none',
                    }}
                  >
                    {line.type !== 'remove' ? line.newNum : ''}
                  </td>
                  <td
                    className="pl-2 whitespace-pre overflow-hidden text-ellipsis"
                    style={{ color: fg, paddingTop: '1px', paddingBottom: '1px' }}
                  >
                    <span style={{ color: 'var(--fg-muted, #555)', userSelect: 'none' }}>{prefix}</span>
                    {' '}{line.content}
                  </td>
                </tr>
              );
            })}
          </tbody>
        </table>
      </div>
    </div>
  );
});
