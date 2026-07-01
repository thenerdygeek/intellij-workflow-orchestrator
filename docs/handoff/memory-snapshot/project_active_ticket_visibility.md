---
name: Active ticket visibility across tabs
description: User wants to discuss making the active ticket more visible across all tabs (Build, Handover, etc.), not just status bar and Sprint tab
type: project
---

The active ticket (set during Start Work) is currently only visible in:
1. Status bar widget (bottom of IDE)
2. Sprint tab (highlighted in list)

User wants to discuss making it more prominent — possibly a persistent ticket info bar across all tabs.

**Why:** The Build tab, Quality tab, Handover tab all use `activeTicketId` internally but don't display it. The user may not know which ticket context they're working in.

**How to apply:** Revisit this when the user brings it up. Consider a shared header component across all Workflow tabs showing the current ticket.
