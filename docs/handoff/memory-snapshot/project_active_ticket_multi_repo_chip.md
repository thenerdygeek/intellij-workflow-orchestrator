---
name: Active ticket multi-repo branch chip (TODO)
description: CurrentWorkSection.kt active-ticket chip must show every repo's branch for the ticket, not just editor's repo
type: project
originSessionId: 37ba2642-e416-4e4e-b609-2145e6caa389
---
`jira/ui/CurrentWorkSection.kt:146` chip currently shows ONE branch, derived from the editor's repo. A Jira ticket can span multiple modules/repos, so the chip must become multi-valued.

**Why:** discovered during 2026-04-27 sweep audit of `RepoContextResolver.resolveCurrentEditorRepoOrPrimary()` misuses. Fixing this is bigger than the rest of the sweep — it's a UI redesign + new data source, not a one-line repo-source swap. Deliberately deferred from the sweep PR so the small fixes ship cleanly.

**How to apply:** when picking this up, the design has three parts:

1. **Discover candidate repos for the ticket.** Strongest signal: Bitbucket Server `/rest/branch-utils/latest/projects/{p}/repos/{r}/branches?filterText=ABC-123` per repo in `PluginSettings.repos`. Cache per ticket (TTL ~60s), invalidate on `BranchChanged` / `TicketChanged`. Fall back to local git branch scan, then Jira→Bitbucket project mapping.
2. **For each candidate, compute `isCheckedOut`** = `repo.currentBranchName == ticketBranch`.
3. **UI** in `CurrentWorkSection.kt`: replace the single `JLabel` chip with a wrapping list of `(repo, branch, ✓/✗)` rows. Each row gets a "Switch branch" affordance using `BranchSwitchAction` with that repo as `preferredRoot`.

**Open design Qs to resolve before implementation:**
- Should the chip offer "check out repoA's branch" when no candidate is currently checked out?
- What if the ticket key resolves to zero branches across all repos — empty state copy?
- Where does the new "tickets→repo-branches" lookup live? New `core/service` (e.g., `TicketBranchLocator`) or extend `JiraTicketProvider`?

Ties into the broader #3 from the audit — see related fixes in the sweep PR for the other 11 sites that were single-line repo-source swaps.
