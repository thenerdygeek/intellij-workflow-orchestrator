/**
 * Animation constants for programmatic use.
 * CSS keyframes and utility classes live in styles/animations.css.
 */

/** Easing curves */
export const EASING = {
  /** Default ease-out for entrances */
  DEFAULT: 'cubic-bezier(0.16, 1, 0.3, 1)',
  /** Snappy spring for interactive feedback */
  SPRING: 'cubic-bezier(0.34, 1.56, 0.64, 1)',
  /** Smooth deceleration for slides */
  DECELERATE: 'cubic-bezier(0, 0, 0.2, 1)',
  /** Subtle acceleration for exits */
  ACCELERATE: 'cubic-bezier(0.4, 0, 1, 1)',
  /** Linear for continuous animations */
  LINEAR: 'linear',
} as const;

/** Duration values in milliseconds */
export const DURATION = {
  /** Micro-interactions: button press, icon swap */
  INSTANT: 100,
  /** Fast transitions: tooltips, color changes */
  FAST: 150,
  /** Standard transitions: expand/collapse, fade */
  NORMAL: 220,
  /** Deliberate transitions: overlays, panels */
  SLOW: 320,
  /** Emphasis animations: message entrance */
  EMPHASIS: 400,
} as const;

/** Stagger delay between consecutive animated items (ms) */
export const STAGGER = {
  /** Tight stagger for list items */
  TIGHT: 30,
  /** Default stagger for cards/messages */
  DEFAULT: 50,
  /** Relaxed stagger for heavy elements */
  RELAXED: 80,
} as const;

/** Common transform values */
export const TRANSFORM = {
  /** Slide-up entrance offset */
  SLIDE_UP: 'translateY(8px)',
  /** Slide-down entrance offset */
  SLIDE_DOWN: 'translateY(-8px)',
  /** Scale-down for press feedback */
  PRESS_SCALE: 'scale(0.97)',
  /** Scale for overlay entrance */
  OVERLAY_SCALE_FROM: 'scale(0.97)',
  /** Neutral position */
  NONE: 'none',
} as const;
