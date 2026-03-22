import { useEffect, useRef, useState } from 'react';
import { useChatStore } from '@/stores/chatStore';

interface UseStreamingReturn {
  streamText: string;
  isStreaming: boolean;
  hasContent: boolean;
}

export function useStreaming(): UseStreamingReturn {
  const activeStream = useChatStore(s => s.activeStream);
  const [displayText, setDisplayText] = useState('');
  const rafRef = useRef<number | null>(null);
  const latestTextRef = useRef('');

  useEffect(() => {
    latestTextRef.current = activeStream?.text ?? '';
  }, [activeStream?.text]);

  useEffect(() => {
    if (!activeStream?.isStreaming) {
      if (rafRef.current) {
        cancelAnimationFrame(rafRef.current);
        rafRef.current = null;
      }
      setDisplayText(activeStream?.text ?? '');
      return;
    }

    const tick = () => {
      setDisplayText(latestTextRef.current);
      rafRef.current = requestAnimationFrame(tick);
    };
    rafRef.current = requestAnimationFrame(tick);

    return () => {
      if (rafRef.current) {
        cancelAnimationFrame(rafRef.current);
        rafRef.current = null;
      }
    };
  }, [activeStream?.isStreaming, activeStream?.text]);

  return {
    streamText: displayText,
    isStreaming: activeStream?.isStreaming ?? false,
    hasContent: displayText.length > 0,
  };
}
