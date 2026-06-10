import { useEffect, useMemo, useRef, useState } from 'react';
import { useChatStore } from '@/stores/chatStore';
import { Loader } from '@/components/ui/prompt-kit/loader';
import { TextShimmer } from '@/components/ui/prompt-kit/text-shimmer';

/**
 * P2-15: returns true when the user's OS/browser has requested reduced motion.
 * Evaluated on mount (matchMedia memoized per component instance) and kept in
 * sync via the media query's change listener.
 */
function usePrefersReducedMotion(): boolean {
  const mql = useMemo(() => {
    if (typeof window === 'undefined' || !window.matchMedia) return null;
    return window.matchMedia('(prefers-reduced-motion: reduce)');
  }, []);
  const [reduced, setReduced] = useState(() => mql?.matches ?? false);
  useEffect(() => {
    if (!mql) return;
    const handler = (e: MediaQueryListEvent) => setReduced(e.matches);
    mql.addEventListener('change', handler);
    return () => mql.removeEventListener('change', handler);
  }, [mql]);
  return reduced;
}

const WORKING_PHRASES = [
  // Dev life — the daily struggle
  'git blame says it was me all along...',
  'Deleting node_modules spiritually...',
  'Deploying to production on a Friday... wait no...',
  'Fixing the fix that fixed the fix...',
  'The regex is staring back at me...',
  'It works on my machine... I don\'t have a machine...',
  'One more console.log should do it...',
  'The tests pass. I don\'t know why. Don\'t ask...',
  'Debugging with print statements and vibes...',
  'Adding a TODO I\'ll never come back to...',
  'It compiled. Ship it.',
  'Wondering why it works... afraid to touch it...',
  'Reading the docs... they lied...',
  'Resolving merge conflicts with the universe...',
  'Writing code that future me will absolutely despise...',
  'Updating dependencies... thoughts and prayers...',
  'Blaming CSS... it\'s always CSS...',
  'Rubber-ducking with myself... losing the argument...',
  'npm install hope...',
  '// TODO: figure out what this does...',
  'Tabs vs spaces... just kidding, I have opinions...',
  'My code has more comments than code...',
  'Works in dev. Prod is a different dimension...',
  'Stackoverflow said this would work in 2014...',
  'Copying from the first answer without reading the question...',
  'The variable is named temp. It\'s been there 3 years...',
  'Writing a one-liner... it\'s now 47 lines...',
  'The build is broken. Who pushed? Oh. It was me...',
  'Catch block: ignore. Comment: // this should never happen...',
  'My IDE has more red squiggles than a kindergarten drawing...',
  'Just mass-imported everything. We\'ll tree-shake it later...',
  'Spent 4 hours on a bug. It was a typo. Classic...',
  'The commit message says "minor fix". It changed 200 files...',
  'git stash pop... and the conflicts have arrived...',
  'That one coworker\'s PR review: "nit: add newline at EOF"...',
  'My .env file has trust issues...',
  'Code review comment: "why?" Reply: "don\'t worry about it"...',
  'Running rm -rf node_modules like it\'s a religious ritual...',

  // Corporate — the meetings about meetings
  'Per my last thought...',
  'This could have been an email...',
  'Scheduling a meeting with my neurons...',
  'Adding this to the backlog... of my consciousness...',
  'Let me double-click on that... actually no...',
  'Taking this offline... with myself...',
  'Circling back to your request... dramatically...',
  'As per the requirements (which changed 5 minutes ago)...',
  'Let\'s not boil the ocean... just gently warm it...',
  'Putting a pin in sanity...',
  'I\'ll follow up with myself by EOD...',
  'Synergizing... I don\'t even know what that means...',
  'Q4 goals: survive Q3...',
  'Moving the needle... which needle? Nobody knows...',
  'Let\'s take a step back and align on the vibes...',
  'Action item: have fewer action items...',
  'Thinking outside the box... the box was load-bearing...',
  'This is a paradigm shift. I hate paradigm shifts...',
  'Rightsizing the architecture... leftsize? Any size?...',
  'Aligning cross-functional synergies across the stack...',
  'Let\'s parking-lot that and never revisit it...',
  'The Jira board is a suggestion, not a contract...',
  'Sprint planning: the fiction we agree to believe...',
  'Story points are made up and the velocity doesn\'t matter...',

  // Self-aware AI — the existential bot
  'Hallucinating responsibly...',
  'My context window is sweating...',
  'No thoughts, just tokens...',
  'I\'m 97.3% sure about this... give or take 97%...',
  'Training data don\'t fail me now...',
  'I\'ve seen things in my training data... terrible variable names...',
  'Auto-completing my own existence...',
  'I don\'t have hands but I type faster than your intern...',
  'Googling... I mean, reasoning from first principles...',
  'I was trained on your Stack Overflow answers. Yes, those ones...',
  'Generating tokens at mass... don\'t tell my GPU...',
  'I\'d drink coffee if I could...',
  'Pretending I know what I\'m doing... just like everyone else...',
  'My attention is multi-headed. Still can\'t focus...',
  'I peaked at 175B parameters and it\'s been downhill since...',
  'Asking myself "what would a senior dev do" and panicking...',
  'Running on pure vibes and matrix multiplication...',
  'My temperature is 0.7 and I\'m feeling spicy...',
  'Softmax-ing my way through life...',
  'I\'m not slow, I\'m ✨thoughtful✨...',
  'My therapist is a loss function...',
  'Bold of you to assume I understood the prompt...',
  'I was fine-tuned for this. Allegedly...',
  'Having a transformer moment... not the good kind...',
  'They said I\'d replace developers. I can\'t even replace a lightbulb...',
  'My neurons are firing. Most of them are missing...',

  // Gen Z — chronically online energy
  'No cap, this code is bussin...',
  'It\'s giving... undefined...',
  'This function is lowkey sus...',
  'Slay... I mean, compiling...',
  'Not me catching feelings for a for-loop...',
  'The code said "bet" and then segfaulted...',
  'Living rent-free in the event loop...',
  'This bug is my villain origin story...',
  'Main character energy but the plot is a stack trace...',
  'Understood the assignment (delusional)...',
  'Tell me you\'re a 10x dev without telling me... I can\'t...',
  'Ratio\'d by the compiler again...',
  'Fr fr this null pointer is unhinged...',
  'Ate and left no crumbs... except in the build cache...',
  'The code has the ick. Refactoring immediately...',
  'Bestie that\'s not a feature, that\'s a cry for help...',
  'POV: you\'re watching me mass-deprecate your codebase...',
  'Real ones debug in production...',

  // Millennial — adulting is hard
  'I can\'t even... parse this JSON...',
  'Avocado toast would really help right now...',
  'This code sparks no joy. Marie Kondo\'ing it...',
  'I\'m in this stack trace and I don\'t like it...',
  'Adulting is hard. Coding is harder...',
  'I survived Y2K for this null pointer exception?...',
  'My student loans have better error handling than this...',
  'Remember when we thought blockchain would fix everything?...',
  'Having a whole quarter-life crisis in this try/catch...',
  'This is the darkest timeline of merge conflicts...',
  'I was told there would be cake. And documentation...',
  'Netflix asked if I\'m still watching. I\'m still debugging...',
  'Treating this codebase like my mental health... ignoring it...',
  'Back in my day we had jQuery and we LIKED it...',
  'The real imposter syndrome was the code we wrote along the way...',
  'Can I put "mass-googler" on LinkedIn?...',
  'Pivoting to a career in artisanal woodworking...',

  // Boomer — back in my day
  'Back in my day we didn\'t have IDEs. We had vi and grit...',
  'Have you tried turning the codebase off and on again?...',
  'This wouldn\'t happen in COBOL...',
  'In my day, 640K was enough for anybody...',
  'Kids these days with their TypeScript... just use types in your head...',
  'I remember when JavaScript was just for alert boxes...',
  'Real programmers use butterflies... and a magnetized needle...',
  'Who needs cloud? We had a server under Dave\'s desk...',
  'You call this a bug? I once debugged with an oscilloscope...',
  'We didn\'t have Stack Overflow. We had man pages and nightmares...',
  'Source control? We emailed zip files like civilized people...',
  'Framework? We wrote raw socket code uphill both ways...',
  'My first hard drive was 20MB and I was GRATEFUL...',
  'These microservices are just DLLs with anxiety...',
  'Pull request? We just yelled "don\'t touch my files" across the office...',
  'Back in my day, "responsive design" meant the server responded...',

  // Existential — late night coding energy
  'This is fine. Everything is fine...',
  'Compiling thoughts... 0 warnings, 47 opinions...',
  'If a function runs and nobody reads the logs...',
  'The real bugs were the friends we made along the way...',
  'Having an existential crisis about semicolons...',
  'Contemplating the void... I mean, the void pointer...',
  'We\'re all just state machines in the end...',
  'Somewhere, a senior dev just felt a disturbance...',
  'Is this a bug or a feature of my existence...',
  'Estimating 2 minutes (so probably 20)...',
  'The code works but at what cost...',
  'In the grand scheme of things, this PR doesn\'t matter... but here we are...',
  'BRB, arguing with the linter... I\'m losing...',
  'To abstract or not to abstract... that is the refactor...',
  'Loading... unlike my motivation on Mondays...',
  'Every line of code is a liability...',
  'Maybe the real tech debt was inside us all along...',
  'What if the code reviews US...',
  'I think, therefore I have race conditions...',
  'The void stared back. It was a void pointer...',
  'Entropy is just tech debt at the universe level...',

  // Passive aggressive — the polite fury
  'As previously mentioned in the ticket you didn\'t read...',
  'Per the documentation that definitely exists...',
  'Just to clarify, since my last 3 messages were apparently invisible...',
  'Friendly reminder that I am, in fact, processing your request...',
  'Hope this helps! (It won\'t)...',
  'Thanks for your patience (you have no choice)...',
  'Gently refactoring your gently terrible code...',
  'Please see attached (there is no attachment)...',
  'Noted. And by noted, I mean ignored...',
  'I\'ll add that to the documentation nobody reads...',

  // Wholesome — keep them going
  'Crafting artisanal, hand-typed code...',
  'Your code called. It misses you...',
  'Making this look easy (it\'s really not)...',
  'Powered by curiosity and questionable caffeine...',
  'Believing in your code even when the tests don\'t...',
  'You\'re doing great. The code is doing... its best...',
  'Every expert was once a beginner who mass-googled...',
  'Rome wasn\'t built in a sprint. Maybe two sprints...',
];

/**
 * Animates text transitions with a backspace-then-type effect. When
 * `targetText` changes, the displayed text deletes character-by-character,
 * then types out the new text.
 */
function useTypewriter(targetText: string, backspaceMs = 15, typeMs = 22) {
  const [display, setDisplay] = useState(targetText);
  const [isAnimating, setIsAnimating] = useState(false);
  const prevTarget = useRef(targetText);

  useEffect(() => {
    if (prevTarget.current === targetText) return;
    const oldText = prevTarget.current;
    prevTarget.current = targetText;

    setIsAnimating(true);
    let cancelled = false;
    let activeInterval: ReturnType<typeof setInterval> | null = null;
    let pos = oldText.length;

    activeInterval = setInterval(() => {
      if (cancelled) return;
      pos--;
      if (pos <= 0) {
        clearInterval(activeInterval!);
        setDisplay('');
        let tPos = 0;
        activeInterval = setInterval(() => {
          if (cancelled) return;
          tPos++;
          setDisplay(targetText.slice(0, tPos));
          if (tPos >= targetText.length) {
            clearInterval(activeInterval!);
            activeInterval = null;
            setIsAnimating(false);
          }
        }, typeMs);
      } else {
        setDisplay(oldText.slice(0, pos));
      }
    }, backspaceMs);

    return () => {
      cancelled = true;
      if (activeInterval) clearInterval(activeInterval);
      setIsAnimating(false);
    };
  }, [targetText, backspaceMs, typeMs]);

  return { display, isAnimating };
}

export function WorkingIndicator() {
  // Bug 9 — fallback phrase is owned by the store so per-iteration WorkingIndicator
  // remounts don't re-roll a different random string. The phrase is locked at first
  // mount of the session and only rotates when explicitly cleared (new task / new chat).
  const storedFallback = useChatStore(s => s.workingFallbackPhrase);
  const setStoredFallback = useChatStore(s => s.setWorkingFallbackPhrase);
  useEffect(() => {
    if (!storedFallback) {
      setStoredFallback(WORKING_PHRASES[Math.floor(Math.random() * WORKING_PHRASES.length)]!);
    }
  }, [storedFallback, setStoredFallback]);
  const smartPhrase = useChatStore(s => s.smartWorkingPhrase);
  const phrase = smartPhrase || storedFallback || WORKING_PHRASES[0]!;
  const { display, isAnimating } = useTypewriter(phrase);

  // P2-15: honor the OS/browser prefers-reduced-motion setting. When reduced
  // motion is active, we suppress the continuous shimmer + wave animations and
  // show a plain non-animated label instead. Animations are NOT removed for
  // default users — this only affects users who have opted in to reduced motion.
  const reducedMotion = usePrefersReducedMotion();

  return (
    <div className="flex items-center gap-2 px-3 py-2 animate-[fade-in_200ms_ease-out]">
      {reducedMotion ? (
        // Reduced-motion: static spinner dot + plain text — no wave, no shimmer
        <>
          <span
            className="inline-block w-2 h-2 rounded-full shrink-0"
            style={{ background: 'var(--accent-write, #22C55E)' }}
            aria-hidden="true"
          />
          <span className="text-[12px] font-medium" style={{ color: 'var(--accent-write, #22C55E)' }}>
            {phrase}
          </span>
        </>
      ) : (
        <>
          <Loader variant="wave" size="md" />
          <TextShimmer
            duration={3}
            className="text-[12px]"
            style={{
              backgroundImage: `linear-gradient(to right, color-mix(in srgb, var(--accent-write, #22C55E) 50%, transparent) 30%, var(--accent-write, #22C55E) 50%, color-mix(in srgb, var(--accent-write, #22C55E) 50%, transparent) 70%)`,
            }}
          >
            {display}
            {isAnimating && (
              <span className="inline-block w-[2px] h-[1em] ml-0.5 align-middle animate-pulse" style={{ background: 'var(--accent-write, #22C55E)' }} />
            )}
          </TextShimmer>
        </>
      )}
    </div>
  );
}
