import { useState, useCallback, useEffect, useRef, ComponentType } from 'react';

interface UseRichBlockResult<P> {
  Component: ComponentType<P> | null;
  isLoading: boolean;
  error: Error | null;
  retry: () => void;
}

export function useRichBlock<P>(
  loader: () => Promise<{ [key: string]: ComponentType<P> }>,
  exportName: string = 'default',
): UseRichBlockResult<P> {
  const [Component, setComponent] = useState<ComponentType<P> | null>(null);
  const [isLoading, setIsLoading] = useState(true);
  const [error, setError] = useState<Error | null>(null);
  const attemptRef = useRef(0);

  const load = useCallback(() => {
    setIsLoading(true);
    setError(null);
    attemptRef.current += 1;
    loader()
      .then((module) => {
        const Comp = module[exportName] as ComponentType<P> | undefined;
        if (!Comp) throw new Error(`Export "${exportName}" not found in module`);
        setComponent(() => Comp);
        setIsLoading(false);
      })
      .catch((err) => {
        setError(err instanceof Error ? err : new Error(String(err)));
        setIsLoading(false);
      });
  }, [loader, exportName]);

  useEffect(() => { load(); }, [load]);
  const retry = useCallback(() => { load(); }, [load]);

  return { Component, isLoading, error, retry };
}
