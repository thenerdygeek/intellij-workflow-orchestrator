import { memo, useEffect, useRef, useState } from 'react';
import { useChatStore } from '@/stores/chatStore';

/** Characters cycled through while a position hasn't settled. */
const GLYPHS = '!#$%&*@?<>/\\|+~';
const pickGlyph = () => GLYPHS[Math.floor(Math.random() * GLYPHS.length)]!;

/**
 * Session title for the TopBar. Plays a cryptic scramble-to-decrypt animation
 * whenever Kotlin calls `setSessionTitleAnimated` (which bumps the store's
 * `sessionTitleAnimateKey`). Plain `setSessionTitle` calls update without
 * animation so the initial provisional title appears instantly.
 *
 * Animation: each output character settles left-to-right over ~700ms. Unsettled
 * positions render a random glyph that rerolls every tick (~55ms). Padded with
 * spaces when old/new titles differ in length so the layout stays stable.
 */
export const SessionTitle = memo(function SessionTitle() {
  const sessionTitle = useChatStore(s => s.sessionTitle);
  const animateKey = useChatStore(s => s.sessionTitleAnimateKey);
  const [display, setDisplay] = useState(sessionTitle ?? '');
  const lastAnimateKey = useRef(animateKey);
  const timerRef = useRef<number | null>(null);

  // Non-animated updates (initial provisional title, clear on new chat) — sync instantly.
  useEffect(() => {
    if (animateKey === lastAnimateKey.current) {
      if (timerRef.current !== null) return; // mid-animation, let it finish
      setDisplay(sessionTitle ?? '');
    }
  }, [sessionTitle, animateKey]);

  // Animated transitions: scramble across the max length, settle left→right.
  useEffect(() => {
    if (animateKey === lastAnimateKey.current) return;
    lastAnimateKey.current = animateKey;

    const target = sessionTitle ?? '';
    if (!target) {
      setDisplay('');
      return;
    }

    const totalMs = 700;
    const tickMs = 55;
    const totalTicks = Math.ceil(totalMs / tickMs);
    let tick = 0;

    if (timerRef.current !== null) window.clearInterval(timerRef.current);

    timerRef.current = window.setInterval(() => {
      tick += 1;
      // Settled up to this position (inclusive exclusive boundary).
      const settled = Math.min(target.length, Math.floor((tick / totalTicks) * target.length));
      let out = target.slice(0, settled);
      for (let i = settled; i < target.length; i++) {
        out += target[i] === ' ' ? ' ' : pickGlyph();
      }
      setDisplay(out);

      if (tick >= totalTicks) {
        setDisplay(target);
        if (timerRef.current !== null) {
          window.clearInterval(timerRef.current);
          timerRef.current = null;
        }
      }
    }, tickMs);

    return () => {
      if (timerRef.current !== null) {
        window.clearInterval(timerRef.current);
        timerRef.current = null;
      }
    };
  }, [animateKey, sessionTitle]);

  if (!display) return null;

  return (
    <span
      className="text-[11px] font-medium truncate tabular-nums"
      style={{ color: 'var(--fg-secondary, #9ca3af)' }}
      title={sessionTitle ?? ''}
    >
      {display}
    </span>
  );
});
