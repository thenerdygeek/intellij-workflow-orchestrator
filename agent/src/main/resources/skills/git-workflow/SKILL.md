---
name: git-workflow
description: Enterprise git workflow best practices for branch management, code review, and change investigation. Use whenever the task involves git operations or version control — this includes working with branches, comparing changes, reviewing commits, investigating ticket-related changes, preparing merges, or understanding file history. Trigger phrases include "branch", "commit", "diff", "blame", "who changed", "what changed", "merge", "rebase", "cherry-pick", "stash", "history", "log", "compare branches", "review changes", "check the diff", and "shelve". For example, if the user asks "What changed on this branch?", "Who modified this file?", or "Compare my branch with main", load this skill first. It provides structured workflows for safe branching strategies, thorough change investigation using blame and history, clean commit practices, and proper merge/rebase procedures that avoid common pitfalls in enterprise codebases.
user-invocable: true
preferred-tools: [run_command, changelist_shelve]
---

# Git Workflow Best Practices

## Tool Availability

All git operations use `run_command` with standard git CLI commands. The `changelist_shelve` tool
provides IDE-native IntelliJ changelist shelving.

`run_command` blocks destructive remote operations (push, fetch, pull, clone, reset --hard, clean -f,
rebase, merge, and any command referencing origin/ or upstream/).

## Before Any Git Operation
1. Use `run_command("git status")` to understand current state (branch, uncommitted changes)
2. Use `run_command("git branch -a")` to see available branches — NEVER assume "main" or "master" exists

## Comparing Branches
To check if changes exist between branches:
1. `run_command("git branch -a")` — find the actual branch names
2. `run_command("git merge-base <ref1> <ref2>")` — find divergence point
3. `run_command("git diff <target-branch>")` — see actual differences
4. For a specific file: `run_command("git diff <target-branch> -- src/main/Foo.kt")`

NEVER use `run_command("git diff origin/...")` — this references remote refs which are blocked.

## Checking if a Ticket's Changes Are on a Branch
1. `run_command("git log --oneline -30")` — search commit messages for the ticket key
2. `run_command("git show <hash>")` — view the specific commit's changes
3. `run_command("git diff <base-branch> -- <changed-file>")` — compare with base

## Reviewing File History
1. `run_command("git log --oneline --follow -- src/main/Foo.kt")` — all commits that touched this file (follows renames)
2. `run_command("git blame src/main/Foo.kt -L 40,60")` — who changed specific lines
3. `run_command("git show HEAD~5:src/main/Foo.kt")` — file content 5 commits ago

## Viewing a File at a Different Branch
Use `run_command("git show <branch>:src/main/Foo.kt")` — NOT `run_command("git checkout <branch>")`.
NEVER switch branches. NEVER checkout. Read file content at any ref without modifying working tree.

## Understanding Branch Divergence
1. `run_command("git merge-base feature-branch develop")` — find common ancestor
2. `run_command("git log --oneline develop -20")` — recent commits on the target branch
3. `run_command("git diff develop")` — full diff between current branch and target

## Shelving Changes (IntelliJ Changelists)
Use `changelist_shelve` to shelve IntelliJ changelists (IDE-native, not git stash):
- Shelve uncommitted changes before switching context
- Unshelve when returning to the task
- Prefer this over `git stash` when working inside IntelliJ

## PR-Related Tasks

For PR-related git tasks, use Bitbucket tools (deferred — activate via `tool_search` first):
- `bitbucket_pr(action="get_pr_diff")` for diffs
- `bitbucket_pr(action="get_pr_changes")` for changed files
- `bitbucket_pr(action="get_pr_commits")` for commit history
- `bitbucket_pr(action="create_pr")` to create PRs

## CI Context

Before confirming a branch is ready to merge, check build status (deferred — activate via `tool_search` first):
- `bamboo_builds(action="build_status")` for Bamboo CI
- `bitbucket_repo(action="get_build_statuses")` for Bitbucket pipelines

## Destructive Operations

If the user asks to rebase, merge, or force-push, explain that these operations are blocked for safety. Offer to help prepare the command for the user to run manually.

## Common Mistakes to Avoid
- Don't assume the base branch is "main" — enterprise repos often use "develop", "master", or custom names
- Don't use `run_command("git diff origin/main")` — use `run_command("git diff main")` instead
- Don't checkout other branches to read files — use `run_command("git show <ref>:<path>")` instead
- Don't run `git log` with huge output — use `-20` or `--oneline` to limit
- Don't forget `--follow` for file history — include it in `git log --follow -- <file>`
- Don't reference remote refs (origin/, upstream/) — they are blocked for safety
