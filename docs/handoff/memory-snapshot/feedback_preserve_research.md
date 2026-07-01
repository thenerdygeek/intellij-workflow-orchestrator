---
name: Never lose research — always save raw findings and summaries
description: All research, findings, analysis, and agent outputs must be saved to persistent files. Never let research exist only in conversation context.
type: feedback
---

All research must be permanently saved. Never let findings exist only in conversation context.

**Why:** Research is expensive (time, tokens, web searches). If it's lost when conversation context compresses, it has to be re-done from scratch. The user explicitly stated "we never would like to lose any research."

**How to apply:**
- Save raw agent research outputs to `~/Documents/Agentic_AI_Plugin_Architecture_Research_20260317/` organized by topic
- Save summaries and analysis alongside raw data
- When research agents complete, immediately save their output to files BEFORE summarizing in conversation
- Use descriptive filenames with dates: `research_{topic}_{date}.md`
- Update the research index file when adding new research
- Reference saved files in memory index for discoverability
