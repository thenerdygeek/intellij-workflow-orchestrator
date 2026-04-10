import React, { useState, useCallback, useMemo, memo } from 'react';
import { LazyMotion, domAnimation, m } from 'motion/react';

/** Animation parameters — opacity + transform primary, blur as garnish. */
const MATERIALIZE_INITIAL = { opacity: 0, y: 3, filter: 'blur(2px)' };
const MATERIALIZE_ANIMATE = { opacity: 1, y: 0, filter: 'blur(0px)' };
const MATERIALIZE_TRANSITION = { duration: 0.12, ease: [0.25, 0.1, 0.25, 1] as const };

/**
 * Single animated character — memoized to prevent re-render when siblings update.
 */
const AnimatedChar = memo<{
  char: string;
  index: number;
  onComplete: (index: number) => void;
}>(({ char, index, onComplete }) => {
  const display = char === ' ' ? '\u00A0' : char;
  return (
    <m.span
      className="inline will-change-[transform,filter,opacity]"
      initial={MATERIALIZE_INITIAL}
      animate={MATERIALIZE_ANIMATE}
      transition={MATERIALIZE_TRANSITION}
      onAnimationComplete={() => onComplete(index)}
    >
      {display}
    </m.span>
  );
});
AnimatedChar.displayName = 'AnimatedChar';

interface BlurTextStreamProps {
  /** Full text released by the PresentationBuffer so far. */
  text: string;
}

/**
 * Two-zone streaming text renderer:
 * - Settled zone: plain <span> with all graduated characters
 * - Trailing zone: last ~N characters as animated <m.span> elements
 *
 * Graduation: when a character's animation completes, it moves from
 * the trailing zone to the settled zone. This keeps animated DOM node
 * count constant (~15) regardless of total text length.
 */
export const BlurTextStream: React.FC<BlurTextStreamProps> = ({ text }) => {
  // How many characters have graduated to the settled zone
  const [settledCount, setSettledCount] = useState(0);

  const handleGraduate = useCallback((index: number) => {
    // Graduate all characters up to and including this index
    setSettledCount(prev => Math.max(prev, index + 1));
  }, []);

  const settledText = text.slice(0, settledCount);
  const trailingChars = text.slice(settledCount);

  // Convert newlines in settled text to <br/> for display
  const settledHtml = useMemo(() => {
    return settledText.split('\n').map((line, i, arr) => (
      <React.Fragment key={i}>
        {line}
        {i < arr.length - 1 && <br />}
      </React.Fragment>
    ));
  }, [settledText]);

  return (
    <LazyMotion features={domAnimation}>
      <span className="whitespace-pre-wrap break-words">
        {/* Zone 1: Settled — plain text, zero overhead */}
        <span>{settledHtml}</span>

        {/* Zone 2: Trailing — per-character animation */}
        {Array.from(trailingChars).map((char, i) => {
          const absoluteIndex = settledCount + i;
          // Newlines graduate immediately
          if (char === '\n') {
            return <br key={absoluteIndex} />;
          }
          return (
            <AnimatedChar
              key={absoluteIndex}
              char={char}
              index={absoluteIndex}
              onComplete={handleGraduate}
            />
          );
        })}
      </span>
    </LazyMotion>
  );
};
