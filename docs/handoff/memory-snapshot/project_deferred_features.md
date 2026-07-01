---
name: Deferred features for Phase 3+
description: Features explicitly deferred from Phase 1-2B, to be planned after plugin is production-ready and usable
type: project
---

## Deferred Features (Phase 3+)

These were scoped out during Phase 2B planning. Only to be tackled after the current plugin (Phases 1A-2B) is fully implemented, tested, and usable.

### From features.md / spec out-of-scope:
1. **Regression Blame & Auto-Triage** (features.md §6.2) — auto-identify which commit caused a regression
2. **Developer Productivity Dashboard** (features.md §6.5) — metrics, cycle time, throughput
3. **Build Timeline View** (features.md §3.2) — visual timeline of build stages
4. **Inline Build Error Tracing** (features.md §3.4) — navigate from build error to source line
5. **Cody Epic Summarization** (features.md §1.3) — AI summary of epic progress

### Agent tool backlog:
6. **`sonar.local_analysis` — use IntelliJ's native Maven/Gradle runner** instead of `ProcessBuilder`. Currently uses shell subprocess which ignores IntelliJ's Settings > Build Tools > Maven/Gradle (custom Maven home, JVM args, `settings.xml` override). Switch to `MavenRunner.getInstance(project).run(...)` / `ExternalSystemUtil` so those settings are respected and `mvn` doesn't need to be on `PATH`.

### User's approach:
- First make the current plugin state usable (all phases 1A-2B implemented and working)
- Then plan Phase 3 features incrementally
