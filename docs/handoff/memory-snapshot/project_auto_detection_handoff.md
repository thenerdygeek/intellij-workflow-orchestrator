---
name: Auto-detection improvements hand-off
description: Research package for sonar/bitbucket/bamboo key auto-detection improvements; hand-off doc lives in repo
type: project
originSessionId: bfb6fea1-3b82-4145-8369-0178a20302b0
---
Research-and-plan hand-off for improving `sonarProjectKey`, `bitbucketProjectKey`/`bitbucketRepoSlug`, and `bambooPlanKey` auto-detection.

**Why:** v0.83.26-alpha fixed the cross-tab unification bug, but the underlying detectors are weak — Sonar misses Gradle entirely, Bitbucket relies on a regex with no validation, Bamboo runs N+1 API calls per detection. User asked to defer implementation to a new session.

**How to apply:** When picking this up, read `docs/architecture/auto-detection-improvements-handoff.md` on branch `refactor/cleanup-perf-caching` (commit `9b6aaa6e`). It is self-contained — current state per detector, recommended tiers (Sonar 4 / Bitbucket 5 / Bamboo 5) with verified-vs-inferred tags, source citations, suggested phasing A–F, what NOT to do (lessons from v0.83.26), and four open questions to resolve at session start. Each phase is independently shippable; do not bundle.

**Branch-scoped:** tied to `refactor/cleanup-perf-caching`. If/when that branch merges or is deleted, this memory should be removed alongside the branch's other temp memories.
