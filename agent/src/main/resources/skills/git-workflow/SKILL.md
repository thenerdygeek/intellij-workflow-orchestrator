---
name: git-workflow
description: Enterprise git workflow best practices for branch management, code review, and change investigation. Use whenever the task involves git operations or version control — this includes working with branches, comparing changes, reviewing commits, investigating ticket-related changes, preparing merges, or understanding file history. Trigger phrases include "branch", "commit", "diff", "blame", "who changed", "what changed", "merge", "rebase", "cherry-pick", "stash", "history", "log", "compare branches", "review changes", "check the diff", and "shelve". For example, if the user asks "What changed on this branch?", "Who modified this file?", or "Compare my branch with main", load this skill first. It provides structured workflows for safe branching strategies, thorough change investigation using blame and history, clean commit practices, and proper merge/rebase procedures that avoid common pitfalls in enterprise codebases.
preferred-tools: [git_status, git_diff, git_log, git_branches, git_show_file, git_show_commit, git_merge_base, git_file_history, git_blame, git_stash_list]
---

# Git Workflow Best Practices

## Before Any Git Operation
1. Run `git_status` to understand current state (branch, uncommitted changes)
2. Run `git_branches` to see available branches — NEVER assume "main" or "master" exists

## Comparing Branches
To check if changes exist between branches:
1. `git_branches` — find the actual branch names
2. `git_merge_base(ref1="current-branch", ref2="target-branch")` — find divergence point
3. `git_diff(ref="target-branch")` — see actual differences
4. For a specific file: `git_diff(ref="target-branch", path="src/main/Foo.kt")`

NEVER use `run_command("git diff origin/...")` — this may trigger network operations.

## Checking if a Ticket's Changes Are on a Branch
1. `git_log(max_count=30)` — search commit messages for the ticket key
2. `git_show_commit(commit="<hash>")` — view the specific commit's changes
3. `git_diff(ref="<base-branch>", path="<changed-file>")` — compare with base

## Reviewing File History
1. `git_file_history(path="src/main/Foo.kt")` — see all commits that touched this file (follows renames)
2. `git_blame(path="src/main/Foo.kt", start_line=40, end_line=60)` — see who changed specific lines
3. `git_show_file(path="src/main/Foo.kt", ref="HEAD~5")` — see what the file looked like 5 commits ago

## Viewing a File at a Different Branch
Use `git_show_file(path="src/main/Foo.kt", ref="develop")` — NOT `run_command("git checkout develop")`.
NEVER switch branches. NEVER checkout. Read file content at any ref without modifying working tree.

## Understanding Branch Divergence
1. `git_merge_base(ref1="feature-branch", ref2="develop")` — common ancestor + commit counts
2. `git_log(ref="develop", max_count=20)` — recent commits on the target branch
3. `git_diff(ref="develop")` — full diff between current branch and target

## Safe Read-Only Commands via run_command
If you need a git command not covered by the git_* tools (e.g., `git log --oneline --graph`),
you may use run_command but ONLY for read-only operations. The following are automatically blocked:
- push, fetch, pull, clone (network operations)
- reset --hard, clean -f, rebase, merge (destructive)
- Any command referencing origin/ or upstream/ (remote refs)

## PR-Related Tasks

For PR-related git tasks, use Bitbucket tools: `bitbucket_get_pr_diff` for diffs, `bitbucket_get_pr_changes` for changed files, `bitbucket_get_pr_commits` for commit history, `bitbucket_create_pr` to create PRs.

## CI Context

Before confirming a branch is ready to merge, check `bamboo_build_status` or `bitbucket_get_build_statuses` for the latest build result.

## Destructive Operations

If the user asks to rebase, merge, or force-push, explain that these operations are blocked for safety. Offer to help prepare the command for the user to run manually.

## Common Mistakes to Avoid
- Don't assume the base branch is "main" — enterprise repos often use "develop", "master", or custom names
- Don't use `run_command("git diff origin/main")` — use `git_diff(ref="main")` instead
- Don't checkout other branches to read files — use `git_show_file` instead
- Don't run `git log` with huge output — use `max_count` parameter to limit
- Don't forget `--follow` for file history — `git_file_history` includes it automatically
