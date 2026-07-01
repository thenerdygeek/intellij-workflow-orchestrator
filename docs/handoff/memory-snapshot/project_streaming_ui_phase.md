---
name: Streaming UI phase — presentation scheduler
description: Cline's 40ms coalescing presentation scheduler to port during upcoming streaming UI upgrade
type: project
originSessionId: 96f92ef3-ef4a-415b-bb56-9d70087dd48e
---
Cline decouples XML parsing from UI rendering via a `TaskPresentationScheduler` (40ms local / 90ms remote cadence). Parsing happens synchronously on every SSE chunk, but UI updates are batched and coalesced to prevent thrash during fast streaming.

**Why:** This is deferred from the incremental XML parser port (2026-04-10) because the user has a separate streaming UI upgrade planned that should handle presentation timing holistically.

**How to apply:** When the user discusses streaming UI improvements, smooth rendering, or chat output performance — this is the missing piece. Port Cline's `TaskPresentationScheduler` from `src/core/task/TaskPresentationScheduler.ts` and `src/core/task/latency.ts`. Key features:
- Coalesced flush scheduling (40ms default, configurable)
- Priority types: immediate (0ms for first visible chunk and tool transitions), normal (40ms), low
- Prevents excessive `onStreamChunk` calls during fast streaming
- Decouples parsing frequency from rendering frequency

**Source files in Cline:**
- `src/core/task/TaskPresentationScheduler.ts` — scheduler implementation
- `src/core/task/latency.ts` — cadence configuration, priority types
- `src/core/task/index.ts` lines 2210+ — `presentAssistantMessage()` scheduling
