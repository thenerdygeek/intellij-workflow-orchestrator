---
name: Trust user-stated infrastructure facts; don't re-ask
description: When the user states an infrastructure fact (URL count, port, auth scheme, repo layout), accept it as ground truth — don't keep proposing variants we imagined
type: feedback
originSessionId: ef626b87-1ed6-4f84-90da-16cbac5980ee
---
When the user tells you something concrete about their infrastructure — "I only have one Nexus URL", "we use Basic auth", "we don't have a separate Docker registry host" — **accept that as ground truth and stop asking about alternatives we imagined exist**.

**Why:** Earlier Nexus audit memos speculated that the user might have two URLs (admin REST + Docker registry) because Sonatype docs describe both patterns. I kept asking for a "Docker registry URL" across multiple turns even after the user said they only have one. They escalated with "I have told you this before multiple times" — a clear sign the repeated asks were costing trust, not gathering information.

**How to apply:**
- When the user states an infrastructure fact, write it down in the active project memory file *immediately*, with a "DO NOT re-ask" framing.
- If a memo from a previous session contradicts a user-stated fact ("the URL is X" vs memo "there are two URLs"), **trust the user, mark the memo as superseded, and move on**. Memos from earlier sessions are not authoritative over what the user just said.
- If the probe/tool output contradicts the user's claim (e.g. `/v2/` 404s on the URL they gave us), the next move is to investigate variants of the *same* URL (sub-paths, ports they might not know about) — never to re-ask "do you have a second URL?"
- Setup-shape questions go in the project memory, not the conversation. Once captured, treat them as resolved.
