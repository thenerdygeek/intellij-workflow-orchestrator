import { useState, useMemo } from 'react';
import { RichBlock } from './RichBlock';
import { cn } from '@/lib/utils';
import { Search, ArrowUp, ArrowDown, ArrowUpDown } from 'lucide-react';

interface TableData {
  columns: string[];
  rows: string[][];
  sortable?: boolean;
  searchable?: boolean;
}

interface DataTableProps {
  tableSource: string;
}

export function DataTable({ tableSource }: DataTableProps) {
  const [sortCol, setSortCol] = useState<number | null>(null);
  const [sortDir, setSortDir] = useState<'asc' | 'desc'>('asc');
  const [search, setSearch] = useState('');

  // Parse JSON
  let data: TableData;
  try {
    data = JSON.parse(tableSource) as TableData;
  } catch {
    return (
      <RichBlock type="table" source={tableSource} error={new Error('Invalid table JSON')}>
        <pre className="p-3 text-[11px] font-mono" style={{ color: 'var(--error)' }}>
          Failed to parse table data
        </pre>
      </RichBlock>
    );
  }

  const { columns, rows, sortable = true, searchable = true } = data;

  // Filter
  const filtered = useMemo(() => {
    if (!search.trim()) return rows;
    const q = search.toLowerCase();
    return rows.filter(row => row.some(cell => cell.toLowerCase().includes(q)));
  }, [rows, search]);

  // Sort
  const sorted = useMemo(() => {
    if (sortCol === null) return filtered;
    const dir = sortDir === 'asc' ? 1 : -1;
    return [...filtered].sort((a, b) => {
      const av = a[sortCol] ?? '';
      const bv = b[sortCol] ?? '';
      // Try numeric comparison
      const an = parseFloat(av);
      const bn = parseFloat(bv);
      if (!isNaN(an) && !isNaN(bn)) return (an - bn) * dir;
      return av.localeCompare(bv) * dir;
    });
  }, [filtered, sortCol, sortDir]);

  const handleSort = (colIdx: number) => {
    if (!sortable) return;
    if (sortCol === colIdx) {
      if (sortDir === 'asc') setSortDir('desc');
      else { setSortCol(null); setSortDir('asc'); }
    } else {
      setSortCol(colIdx);
      setSortDir('asc');
    }
  };

  return (
    <RichBlock type="table" source={tableSource}>
      <div>
        {/* Search */}
        {searchable && (
          <div className="flex items-center gap-2 px-3 py-2" style={{ borderBottom: '1px solid var(--border)' }}>
            <Search className="h-3 w-3 shrink-0" style={{ color: 'var(--fg-muted)' }} />
            <input
              value={search}
              onChange={e => setSearch(e.target.value)}
              placeholder="Search..."
              className="flex-1 bg-transparent text-[12px] outline-none"
              style={{ color: 'var(--fg)' }}
            />
            {search && (
              <span className="text-[10px]" style={{ color: 'var(--fg-muted)' }}>
                {sorted.length} of {rows.length}
              </span>
            )}
          </div>
        )}

        {/* Table */}
        <div className="overflow-x-auto">
          <table className="w-full text-[12px]" style={{ color: 'var(--fg)' }}>
            <thead>
              <tr style={{ borderBottom: '2px solid var(--border)' }}>
                {columns.map((col, i) => (
                  <th
                    key={i}
                    className={cn(
                      'px-3 py-2 text-left font-semibold whitespace-nowrap',
                      sortable && 'cursor-pointer select-none hover:brightness-110',
                    )}
                    style={{ color: 'var(--fg-secondary)' }}
                    onClick={() => handleSort(i)}
                  >
                    <span className="flex items-center gap-1">
                      {col}
                      {sortable && (
                        <span className="shrink-0">
                          {sortCol === i ? (
                            sortDir === 'asc' ? <ArrowUp className="h-3 w-3" /> : <ArrowDown className="h-3 w-3" />
                          ) : (
                            <ArrowUpDown className="h-3 w-3 opacity-30" />
                          )}
                        </span>
                      )}
                    </span>
                  </th>
                ))}
              </tr>
            </thead>
            <tbody>
              {sorted.map((row, ri) => (
                <tr
                  key={ri}
                  style={{
                    borderBottom: '1px solid var(--border)',
                    backgroundColor: ri % 2 === 1 ? 'var(--hover-overlay)' : 'transparent',
                  }}
                >
                  {row.map((cell, ci) => (
                    <td key={ci} className="px-3 py-1.5 whitespace-nowrap">
                      {cell}
                    </td>
                  ))}
                </tr>
              ))}
              {sorted.length === 0 && (
                <tr>
                  <td colSpan={columns.length} className="px-3 py-4 text-center" style={{ color: 'var(--fg-muted)' }}>
                    No matching rows
                  </td>
                </tr>
              )}
            </tbody>
          </table>
        </div>

        {/* Footer */}
        {!searchable && (
          <div className="px-3 py-1.5 text-[10px]" style={{ color: 'var(--fg-muted)', borderTop: '1px solid var(--border)' }}>
            {rows.length} row{rows.length !== 1 ? 's' : ''}
          </div>
        )}
      </div>
    </RichBlock>
  );
}
