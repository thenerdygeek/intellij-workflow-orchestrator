import { describe, expect, it, vi, beforeEach } from 'vitest';
import { render, screen, fireEvent } from '@testing-library/react';
import { EditStatsBar } from '@/components/agent/EditStatsBar';

const sampleDiff = {
  totalAdded: 57, totalRemoved: 11,
  files: [
    { path: 'src/Foo.kt', added: 45, removed: 8, status: 'MODIFIED' as const },
    { path: 'src/Bar.kt', added: 12, removed: 3, status: 'CREATED' as const },
  ],
};

describe('EditStatsBar', () => {
  beforeEach(() => {
    (window as any)._revertFileToBaseline = vi.fn();
    (window as any)._revertAll = vi.fn();
    (window as any)._navigateToFile = vi.fn();
  });

  it('renders nothing when diff is null', () => {
    const { container } = render(<EditStatsBar diff={null} />);
    expect(container.firstChild).toBeNull();
  });

  it('renders totals and file count when collapsed', () => {
    render(<EditStatsBar diff={sampleDiff} />);
    expect(screen.getByText('+57')).toBeInTheDocument();
    expect(screen.getByText('-11')).toBeInTheDocument();
    expect(screen.getByText(/2 files/)).toBeInTheDocument();
  });

  it('shows per-file rows when expanded', () => {
    render(<EditStatsBar diff={sampleDiff} />);
    fireEvent.click(screen.getByLabelText(/expand/i));
    expect(screen.getByText('src/Foo.kt')).toBeInTheDocument();
    expect(screen.getByText('src/Bar.kt')).toBeInTheDocument();
  });

  it('per-file revert calls _revertFileToBaseline with the path', () => {
    render(<EditStatsBar diff={sampleDiff} />);
    fireEvent.click(screen.getByLabelText(/expand/i));
    const fooRow = screen.getByText('src/Foo.kt').closest('[data-testid="file-row"]')!;
    fireEvent.click(fooRow.querySelector('[aria-label="Revert this file"]')!);
    expect((window as any)._revertFileToBaseline).toHaveBeenCalledWith('src/Foo.kt');
  });

  it('clicking the file path opens the file via _navigateToFile', () => {
    render(<EditStatsBar diff={sampleDiff} />);
    fireEvent.click(screen.getByLabelText(/expand/i));
    fireEvent.click(screen.getByText('src/Foo.kt'));
    expect((window as any)._navigateToFile).toHaveBeenCalledWith('src/Foo.kt');
  });

  it('global revert calls _revertAll', () => {
    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(true);
    render(<EditStatsBar diff={sampleDiff} />);
    fireEvent.click(screen.getByText(/revert all/i));
    expect((window as any)._revertAll).toHaveBeenCalled();
    confirmSpy.mockRestore();
  });

  it('global revert does NOT call _revertAll when user cancels confirmation', () => {
    const confirmSpy = vi.spyOn(window, 'confirm').mockReturnValue(false);
    render(<EditStatsBar diff={sampleDiff} />);
    fireEvent.click(screen.getByText(/revert all/i));
    expect((window as any)._revertAll).not.toHaveBeenCalled();
    confirmSpy.mockRestore();
  });

  it('hides MODIFIED files with zero added and zero removed from expanded list', () => {
    const diffWithZeroRow = {
      totalAdded: 12, totalRemoved: 3,
      files: [
        { path: 'src/Foo.kt', added: 12, removed: 3, status: 'MODIFIED' as const },
        { path: 'src/Bar.kt', added: 0, removed: 0, status: 'MODIFIED' as const },
        { path: 'src/Created.kt', added: 0, removed: 0, status: 'CREATED' as const },
      ],
    };
    render(<EditStatsBar diff={diffWithZeroRow} />);
    fireEvent.click(screen.getByLabelText(/expand/i));
    expect(screen.getByText('src/Foo.kt')).toBeInTheDocument();
    expect(screen.queryByText('src/Bar.kt')).not.toBeInTheDocument();
    expect(screen.getByText('src/Created.kt')).toBeInTheDocument();
  });
});
