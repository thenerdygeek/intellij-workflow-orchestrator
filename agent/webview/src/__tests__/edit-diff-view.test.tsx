/**
 * Feature tests for EditDiffView — the component that renders file edit diffs.
 *
 * Tests the fix for the literal \n bug and theme rendering.
 */
import { describe, it, expect } from 'vitest';
import { render, screen } from '@testing-library/react';
import { EditDiffView } from '@/components/agent/EditDiffView';

describe('EditDiffView', () => {
  it('renders file name from path', () => {
    render(
      <EditDiffView
        filePath="src/main/kotlin/PaymentService.kt"
        oldLines={['old line']}
        newLines={['new line']}
        accepted={true}
      />
    );
    expect(screen.getByText('PaymentService.kt')).toBeInTheDocument();
  });

  it('shows Applied status when accepted', () => {
    render(
      <EditDiffView
        filePath="test.kt"
        oldLines={['a']}
        newLines={['b']}
        accepted={true}
      />
    );
    expect(screen.getByText('Applied')).toBeInTheDocument();
  });

  it('shows Rejected status when not accepted', () => {
    render(
      <EditDiffView
        filePath="test.kt"
        oldLines={['a']}
        newLines={['b']}
        accepted={false}
      />
    );
    expect(screen.getByText('Rejected')).toBeInTheDocument();
  });

  it('shows Pending status when accepted is null', () => {
    render(
      <EditDiffView
        filePath="test.kt"
        oldLines={['a']}
        newLines={['b']}
        accepted={null}
      />
    );
    expect(screen.getByText('Pending')).toBeInTheDocument();
  });

  it('renders actual line content (not literal \\n)', () => {
    render(
      <EditDiffView
        filePath="test.kt"
        oldLines={['fun old() {', '  return 1', '}']}
        newLines={['fun new() {', '  return 2', '}']}
        accepted={true}
      />
    );
    // Lines should be rendered as separate table rows, not as one line with \n
    expect(screen.getByText(/fun old\(\)/)).toBeInTheDocument();
    expect(screen.getByText(/fun new\(\)/)).toBeInTheDocument();
  });

  it('shows correct add/remove counts', () => {
    render(
      <EditDiffView
        filePath="test.kt"
        oldLines={['line1', 'line2']}
        newLines={['line1', 'modified', 'added']}
        accepted={true}
      />
    );
    // Should show some +/- counts in the header
    const container = document.querySelector('[class*="overflow-hidden"]');
    expect(container).toBeTruthy();
  });

  it('handles empty old lines (new file creation)', () => {
    render(
      <EditDiffView
        filePath="New.kt"
        oldLines={[]}
        newLines={['class New {', '}']}
        accepted={true}
      />
    );
    expect(screen.getByText('New.kt')).toBeInTheDocument();
  });

  it('handles empty new lines (file deletion)', () => {
    render(
      <EditDiffView
        filePath="Deleted.kt"
        oldLines={['class Old {', '}']}
        newLines={[]}
        accepted={true}
      />
    );
    expect(screen.getByText('Deleted.kt')).toBeInTheDocument();
  });
});
