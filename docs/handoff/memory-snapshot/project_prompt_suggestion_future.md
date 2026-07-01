---
name: Prompt suggestion feature planned
description: Wire prompt-kit's PromptSuggestion component when Kotlin bridge provides suggested prompts
type: project
---

prompt-kit's `prompt-suggestion.tsx` is copied but unused. Plan to wire it when:
1. Kotlin bridge adds a `window.updateSuggestions(json)` function that provides suggestions (from conversation history, skill descriptions, or context-aware prompts)
2. Create a `PromptSuggestions` wiring component in `components/input/` that reads suggestions from chatStore and renders `PromptSuggestion` chips above the InputBar
3. The component supports highlight matching (built into prompt-kit) for search-style suggestions

**Why:** User requested this as a future feature on 2026-03-24.
**How to apply:** When building the empty-state or input-area UI, check if this feature is ready to implement.
