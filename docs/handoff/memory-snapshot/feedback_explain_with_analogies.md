---
name: Explain jargon with analogies and examples
description: Whenever introducing a technical term (protocol, data structure, algorithm, library, platform primitive), define it in plain language, give a real-world analogy, and show a concrete scenario from this plugin. User is willing to learn but hasn't heard the jargon before.
type: feedback
originSessionId: 2bce1f3e-6143-4b15-9d97-55eb42086d7c
---
When introducing any technical term the user may not already know — HTTP protocol concepts (ETag, Cache-Control, 304, conditional GET), caching terminology (LRU, TTL, eviction, thundering herd, memoization), library names (Caffeine, OkHttp internals), IntelliJ platform primitives (CachedValuesManager, ModificationTracker, @Service, NonBlockingReadAction), threading terms (EDT, read action, coroutine scope), crypto (SHA-256, hash), architecture terms (interceptor, middleware, pipeline) — define it in three parts:

1. **Plain-English definition** (one sentence, no other jargon).
2. **Analogy** (everyday non-programming scenario that maps to the concept).
3. **Concrete plugin scenario** (where in *this* codebase / workflow the term would appear or matter).

**Why:** User explicitly asked to learn terminology while working through Phase 3 caching design. Verbatim quote: "I will also be learning from you, so whenever you mention about a terminology here please explain with easier words what that is with example scenarios like you were mentioning ETag I've never heard of it." User is running multi-session architecture work and cannot evaluate design trade-offs if the vocabulary is opaque.

**How to apply:**
- Inline explanation the FIRST time a term appears in a conversation; afterwards the term is "known" and can be used without re-explaining.
- If a term reappears days or sessions later, treat it as new and re-explain briefly — user prefers redundancy over opaque shorthand.
- Do NOT explain terms that are obviously already in the user's vocabulary from their own messages (e.g., if user says "Jira board", don't explain what a board is).
- In written docs (design docs, strategy files, commit notes) that the user will read asynchronously, include a glossary section up-front with the same three-part structure.
- Do NOT dump Wikipedia-length explanations. Three sentences per term is ideal; five is the ceiling.
- When a concept has multiple nuances, introduce the simple version first, then add "...with one catch:" for the subtlety.
- Useful analogy categories: libraries (lending, returns, receipts), restaurants (orders, menus, kitchens), postal mail (stamps, tracking, signed delivery), banking (statements, balances, transactions), phone calls (voicemail, caller ID), sports (substitutions, timeouts). Pick whichever fits the concept naturally.

**Scope:** entire working relationship, not just Phase 3. Persist across sessions.
