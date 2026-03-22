import { useVirtualizer } from '@tanstack/react-virtual';
import { useCallback, useRef } from 'react';

interface UseVirtualScrollOptions {
  count: number;
  estimateSize?: number;
  overscan?: number;
  enabled?: boolean;
}

export function useVirtualScroll({
  count,
  estimateSize = 80,
  overscan = 5,
  enabled = true,
}: UseVirtualScrollOptions) {
  const parentRef = useRef<HTMLDivElement | null>(null);

  const virtualizer = useVirtualizer({
    count,
    getScrollElement: () => parentRef.current,
    estimateSize: () => estimateSize,
    overscan,
    enabled,
  });

  const measureElement = useCallback(
    (node: HTMLElement | null) => {
      if (node) {
        const index = Number(node.dataset.index);
        if (!isNaN(index)) {
          virtualizer.measureElement(node);
        }
      }
    },
    [virtualizer],
  );

  return {
    parentRef,
    virtualizer,
    measureElement,
    virtualItems: virtualizer.getVirtualItems(),
    totalSize: virtualizer.getTotalSize(),
  };
}
