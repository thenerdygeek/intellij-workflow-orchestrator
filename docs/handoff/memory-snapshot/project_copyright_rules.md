---
name: Copyright header enforcement rules
description: Detailed copyright year update logic for changed/new files — consolidate all years into startYear-currentYear format
type: project
---

## Copyright Header Rules

**Scope:** Only changed files and new files in the current PR/commit. Never touch unchanged files.

**New files:** Must have copyright header with current year (e.g., `2026`)

**Changed files:** Update the year in existing copyright header to include current year.

### Year Update Logic
The rule is: consolidate all years into a single range `{earliest}-{currentYear}`. If only current year, just `{currentYear}`.

Examples (assuming current year is 2026):
- `2025` → `2025-2026`
- `2019-2025` → `2019-2026`
- `2018, 2020-2023, 2025` → `2018-2026` (consolidate all into single range from earliest to current)
- `2026` → `2026` (already current, no change)
- `2020-2026` → `2020-2026` (already current, no change)

### Template Source
- NOT configurable — reads from `copyright.txt` file in the project repository root
- The file will contain the copyright text template (does not exist yet, will be created)

### Edge Cases
- Developer may have already added copyright manually — just ensure the year is current
- If copyright already contains current year, no modification needed

### Existing Infrastructure
- `CopyrightCheckService` in `:core` already checks for presence (regex match on first 10 lines)
- Only checks — does NOT auto-fix. Phase 2B needs to add auto-fix (update years, add missing headers)
