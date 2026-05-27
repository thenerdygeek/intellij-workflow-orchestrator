import { useCallback, useState } from 'react';

/**
 * A single pending compress-confirm prompt: the display fields plus the promise
 * `resolve` that the AttachmentManager's `confirmCompress(...)` awaits.
 */
export interface CompressRequest {
  originalKB: number;
  capKB: number;
  filename: string;
  resolve: (proceed: boolean) => void;
}

/**
 * Serializes oversize-image compress-confirm prompts (bug #7). The previous
 * single-slot state dropped the first request's `resolve` when a second oversize
 * file arrived before the first prompt was answered — hanging that attach forever.
 * Here each `request(...)` enqueues its own resolve; only the head is shown, and
 * `resolveCurrent` answers it and advances to the next.
 */
export function useCompressQueue() {
  const [queue, setQueue] = useState<CompressRequest[]>([]);

  const request = useCallback(
    (originalKB: number, capKB: number, filename: string) =>
      new Promise<boolean>((resolve) => {
        setQueue((q) => [...q, { originalKB, capKB, filename, resolve }]);
      }),
    [],
  );

  const resolveCurrent = useCallback((proceed: boolean) => {
    setQueue((q) => {
      if (q.length === 0) return q;
      const [head, ...rest] = q;
      head!.resolve(proceed);
      return rest;
    });
  }, []);

  return { current: queue[0] ?? null, request, resolveCurrent };
}
