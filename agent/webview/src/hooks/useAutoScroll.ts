import { useCallback, useEffect, useRef, useState } from 'react';

interface UseAutoScrollOptions {
  threshold?: number;
  dependency?: unknown;
}

interface UseAutoScrollReturn {
  containerRef: React.RefObject<HTMLDivElement | null>;
  isScrolledUp: boolean;
  hasNewMessages: boolean;
  scrollToBottom: (smooth?: boolean) => void;
  onContentUpdate: () => void;
}

export function useAutoScroll({
  threshold = 100,
  dependency,
}: UseAutoScrollOptions = {}): UseAutoScrollReturn {
  const containerRef = useRef<HTMLDivElement | null>(null);
  const [isScrolledUp, setIsScrolledUp] = useState(false);
  const [hasNewMessages, setHasNewMessages] = useState(false);
  const isAtBottomRef = useRef(true);

  const checkIsAtBottom = useCallback(() => {
    const el = containerRef.current;
    if (!el) return true;
    const distanceFromBottom = el.scrollHeight - el.scrollTop - el.clientHeight;
    return distanceFromBottom <= threshold;
  }, [threshold]);

  const scrollToBottom = useCallback((smooth = true) => {
    const el = containerRef.current;
    if (!el) return;
    el.scrollTo({
      top: el.scrollHeight,
      behavior: smooth ? 'smooth' : 'instant',
    });
    setIsScrolledUp(false);
    setHasNewMessages(false);
    isAtBottomRef.current = true;
  }, []);

  const onContentUpdate = useCallback(() => {
    if (isAtBottomRef.current) {
      requestAnimationFrame(() => scrollToBottom(true));
    } else {
      setHasNewMessages(true);
    }
  }, [scrollToBottom]);

  useEffect(() => {
    const el = containerRef.current;
    if (!el) return;

    const handleScroll = () => {
      const atBottom = checkIsAtBottom();
      isAtBottomRef.current = atBottom;
      setIsScrolledUp(!atBottom);
      if (atBottom) {
        setHasNewMessages(false);
      }
    };

    el.addEventListener('scroll', handleScroll, { passive: true });
    return () => el.removeEventListener('scroll', handleScroll);
  }, [checkIsAtBottom]);

  useEffect(() => {
    onContentUpdate();
  }, [dependency, onContentUpdate]);

  return {
    containerRef,
    isScrolledUp,
    hasNewMessages,
    scrollToBottom,
    onContentUpdate,
  };
}
