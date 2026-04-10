import { useEffect, useRef, useCallback, useState } from 'react';
import { useChatStore } from '@/stores/chatStore';
import { useSettingsStore } from '@/stores/settingsStore';

interface PresentationBufferState {
  displayText: string;
  isBuffering: boolean;
}

/**
 * Adaptive-rate character queue that smooths streaming text delivery.
 *
 * Accepts raw accumulated text from chatStore.activeStream and releases
 * characters at a constant adaptive rate based on queue depth.
 *
 * Queue depth → chars per frame (~16ms):
 *   0        → pause (wait for tokens)
 *   1-50     → 2-3 chars (~150 chars/sec)
 *   50-200   → 4-8 chars (~300-500 chars/sec)
 *   200+     → 12-20 chars (~750-1200 chars/sec)
 *   flushing → 15-25 chars (accelerated drain on stream end)
 */
export function usePresentationBuffer(): PresentationBufferState {
  const rawText = useChatStore(s => s.activeStream?.text ?? '');
  const isStreaming = useChatStore(s => s.activeStream?.isStreaming ?? false);
  const animationsEnabled = useSettingsStore(s => s.chatAnimationsEnabled);

  const [displayText, setDisplayText] = useState('');
  const queueRef = useRef<string[]>([]);
  const rafRef = useRef<number | null>(null);
  const lastRawLengthRef = useRef(0);
  const flushingRef = useRef(false);

  // Enqueue new characters when rawText grows
  useEffect(() => {
    const newChars = rawText.slice(lastRawLengthRef.current);
    if (newChars.length > 0) {
      if (animationsEnabled) {
        // Per-character queuing for animated mode
        for (const ch of newChars) {
          queueRef.current.push(ch);
        }
      } else {
        // Chunk queuing for non-animated mode
        queueRef.current.push(newChars);
      }
    }
    lastRawLengthRef.current = rawText.length;
  }, [rawText, animationsEnabled]);

  // Calculate chars to release per frame based on queue depth
  const getCharsPerFrame = useCallback((): number => {
    const depth = queueRef.current.length;
    if (depth === 0) return 0;

    if (!animationsEnabled) {
      // No animation: dump everything available
      return depth;
    }

    if (flushingRef.current) {
      return Math.min(depth, 25);
    }
    if (depth > 200) return Math.min(depth, 20);
    if (depth > 50) return Math.min(depth, Math.max(4, Math.floor(depth / 25)));
    return Math.min(depth, 3);
  }, [animationsEnabled]);

  // RAF tick loop
  useEffect(() => {
    if (!isStreaming && queueRef.current.length === 0) {
      // No stream and no pending queue — show final text directly
      if (rafRef.current) {
        cancelAnimationFrame(rafRef.current);
        rafRef.current = null;
      }
      setDisplayText(rawText);
      lastRawLengthRef.current = rawText.length;
      queueRef.current = [];
      flushingRef.current = false;
      return;
    }

    const tick = () => {
      const n = getCharsPerFrame();
      if (n > 0) {
        const released = queueRef.current.splice(0, n).join('');
        setDisplayText(prev => prev + released);
      }

      // Stop RAF when queue is drained and stream is done
      if (queueRef.current.length === 0 && !isStreaming) {
        rafRef.current = null;
        flushingRef.current = false;
        return;
      }

      rafRef.current = requestAnimationFrame(tick);
    };

    if (!rafRef.current) {
      rafRef.current = requestAnimationFrame(tick);
    }

    return () => {
      if (rafRef.current) {
        cancelAnimationFrame(rafRef.current);
        rafRef.current = null;
      }
    };
  }, [isStreaming, getCharsPerFrame, rawText]);

  // Stream ended: switch to flush mode
  useEffect(() => {
    if (!isStreaming && queueRef.current.length > 0) {
      flushingRef.current = true;
    }
  }, [isStreaming]);

  // Reset on new stream
  useEffect(() => {
    if (!isStreaming && rawText === '') {
      setDisplayText('');
      queueRef.current = [];
      lastRawLengthRef.current = 0;
      flushingRef.current = false;
    }
  }, [isStreaming, rawText]);

  return {
    displayText,
    isBuffering: queueRef.current.length > 0,
  };
}
