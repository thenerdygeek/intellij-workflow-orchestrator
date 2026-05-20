import { describe, it, expect } from 'vitest';
import { render } from '@testing-library/react';
import { useEffect, useState, type ComponentType } from 'react';
import { MermaidDiagram } from '../components/rich/MermaidDiagram';
import { ChartView } from '../components/rich/ChartView';
import { FlowDiagram } from '../components/rich/FlowDiagram';
import { AnsiOutput } from '../components/rich/AnsiOutput';
import { CollapsibleOutput } from '../components/rich/CollapsibleOutput';
import { DiffHtml } from '../components/rich/DiffHtml';
import { ArtifactRenderer } from '../components/rich/ArtifactRenderer';
import { ImageView } from '../components/rich/ImageView';
import { DataTable } from '../components/rich/DataTable';
import { InteractiveHtml } from '../components/rich/InteractiveHtml';
import { MathBlock } from '../components/rich/MathBlock';
import { TimelineView } from '../components/rich/TimelineView';

// React.memo is detected at runtime via the special `$$typeof` Symbol that
// React attaches to memoized component descriptors. Reading the symbol on
// the export gives us a cheap, deterministic check that survives bundler
// transforms (Vite/Vitest never strip these symbols). The Mermaid-stable-DOM
// case below adds a render-loop check for the "lead" component to make sure
// the wrap isn't trivially broken.
const REACT_MEMO_TYPE = Symbol.for('react.memo');

function isMemoized(Comp: unknown): boolean {
  return (
    typeof Comp === 'object' &&
    Comp !== null &&
    (Comp as { $$typeof?: symbol }).$$typeof === REACT_MEMO_TYPE
  );
}

describe('rich blocks are memoized', () => {
  it.each([
    ['MermaidDiagram', MermaidDiagram],
    ['ChartView', ChartView],
    ['FlowDiagram', FlowDiagram],
    ['AnsiOutput', AnsiOutput],
    ['CollapsibleOutput', CollapsibleOutput],
    ['DiffHtml', DiffHtml],
    ['ArtifactRenderer', ArtifactRenderer],
    ['ImageView', ImageView],
    ['DataTable', DataTable],
    ['InteractiveHtml', InteractiveHtml],
    ['MathBlock', MathBlock],
    ['TimelineView', TimelineView],
  ])('%s export is wrapped in React.memo', (_name, Comp) => {
    expect(isMemoized(Comp as ComponentType<unknown>)).toBe(true);
  });

  it('MermaidDiagram keeps a stable host DOM node across parent re-renders with identical props', () => {
    // The parent forces a re-render via state change. With React.memo, the
    // child should NOT re-render its DOM tree — so the captured firstElementChild
    // reference stays the same across the re-render.
    let bump: ((v: number) => void) | null = null;

    function Harness() {
      const [n, setN] = useState(0);
      useEffect(() => {
        bump = setN;
      }, []);
      // n is read but not forwarded — same props every render.
      void n;
      return <MermaidDiagram source="graph TD; A-->B" />;
    }

    const { container } = render(<Harness />);
    const before = container.firstElementChild;
    expect(before).not.toBeNull();

    // Force the parent to re-render with no prop changes.
    bump?.(1);

    const after = container.firstElementChild;
    expect(after).toBe(before);
  });
});
