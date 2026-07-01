---
name: Actual developer workflow sequence
description: The real task completion workflow - PR comes BEFORE automation because Bamboo builds (which produce docker tags) only trigger after PR is raised
type: project
---

## Actual Developer Workflow (Sequential)

1. Developer finishes code
2. Creates PR (Bitbucket) — this triggers Bamboo builds
3. Bamboo runs 3 build pipelines: Artifacts, OSS scan, Sonar
4. Wait for all 3 to be green
5. Take docker tag from successful Bamboo build artifacts
6. Go to 3-4 automation suites, run them with `dockerTagsAsJson` (this repo's docker tag + other service configs)
7. Wait for no regression (all suites pass)
8. Share docker tags + automation suite result links to QA **via email**
9. Add suite links to Jira ticket comment
10. QA handover can be batched — multiple tickets, multiple services' docker tags in one handover
11. Time logging is done manually, daily (not per-task)

## PR Creation Details
- Should be fully automatic — one click
- Title: auto-generated from ticket ID + branch name
- Description: Cody-generated from diff
- Target branch: configurable default (e.g., `develop`)
- Reviewers: from Bitbucket defaults or configured list
- Minimal user input required

## QA Handover
- NOT an email feature — just a copy-to-clipboard panel
- Developer copies formatted docker tags + automation suite links to paste into email/Slack/wherever
- Can include multiple services' docker tags and multiple suite results

## Time Logging
- Daily, manual, max 7h per day
- Variable per ticket depending on work done

## "Complete Task" Concept
- There is NO single "Complete Task" moment
- Jira comment (suite links), QA email, time logging are SEPARATE actions at different times
- However, developer should be able to create custom automation buttons that chain actions together

## Cody Pre-Review
- Optional — developer can trigger anytime, should NOT block the flow

## Key Insight
PR MUST come before automation — docker tags are a build artifact from Bamboo, and Bamboo builds trigger on PR creation. The plugin's Phase 2B flow must respect this ordering.
