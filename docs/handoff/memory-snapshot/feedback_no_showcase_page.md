---
name: Never use showcase page for testing
description: Always render the actual chat UI (index.html) for visual testing, never the showcase page
type: feedback
originSessionId: 57cd1c66-2c92-4b9d-a7b1-5410b1ba0ff5
---
Always render the exact chat UI as the agent tab renders — use the main `index.html` page (localhost:5173/), NOT the showcase page (localhost:5173/showcase.html).

**Why:** User wants to see how content actually renders in the real agent tab, not in a component gallery.

**How to apply:** When using Playwright to test agent chat rendering, always navigate to the root URL and inject content via the bridge functions (startSession, appendToken, endStream, etc.).
