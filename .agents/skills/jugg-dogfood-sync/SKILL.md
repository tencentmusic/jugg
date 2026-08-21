---
name: jugg-dogfood-sync
description: 'Refresh, verify, promote, or clean up the local Jugg dogfood integration branch. In the Jugg repository, treat the exact request "更新 dogfood" as Refresh: rebuild dogfood from the latest develop and merge the latest main. Also use for dogfood conflict review, candidate validation, promotion to develop, or cleanup after release. Never push dogfood.'
---

# Jugg Dogfood Sync

## Scope Guard

Use this skill only when all of these repository markers exist:

- `build.gradle`
- `docs/ai_knowledge/00_overview.md`
- `docs/ai_knowledge/99_index.md`
- `docs/task/2026-08/dogfood_branch_sync_workflow.md`

Follow the repository `AGENTS.md` before any Git or file operation. Read the mandatory knowledge-base documents and the dogfood workflow document before acting.

The dogfood branch is local and disposable. Never push it, configure an upstream for it, or add dogfood-only product changes.

## Select the Operation

Choose exactly one operation from the user's request:

- **Refresh**: rebuild dogfood from the current `develop` tip and merge the current `main` tip.
- **Verify**: inspect and validate the current dogfood candidate without changing `develop`.
- **Promote**: move `develop` to the already verified dogfood candidate. Require an explicit request to synchronize or promote dogfood to develop.
- **Cleanup**: delete dogfood after the develop release. Require an explicit cleanup or deletion request.

Do not infer Promote from a Refresh or Verify request.

## Shared Invariants

1. Treat local `develop` and `main` branch refs as the source snapshots. Do not substitute remote-tracking refs silently.
2. Keep dogfood as a rebuildable candidate, not a persistent branch that alternately accumulates merges from develop and main.
3. Before any destructive operation, confirm the current branch is exactly `dogfood` and the worktree is clean. Stop rather than stashing or discarding unrelated changes.
4. Preserve both branches' valid behavior. Never resolve a core conflict by choosing an entire `ours` or `theirs` file without behavior-level evidence.
5. Do not push commits, branches, tags, or notes.
6. Stage and commit only the merge result for this workflow.

## Refresh

### 1. Establish the source snapshots

Inspect:

```bash
git status --short --branch
git worktree list --porcelain
git rev-parse develop
git rev-parse main
git merge-base develop main
git rev-list --left-right --count develop...main
git for-each-ref --format='%(upstream:short)' refs/heads/dogfood
```

Require all of the following:

- `develop`, `main`, and `dogfood` exist locally.
- The dogfood worktree is clean.
- `dogfood` has no upstream. If it has one, stop and report it rather than continuing.
- No merge, rebase, cherry-pick, or revert is already in progress.

Record the exact develop and main SHAs for the final response and merge commit body.

### 2. Enable safe conflict reuse

Set repository-local configuration:

```bash
git config --local rerere.enabled true
git config --local rerere.autoupdate false
git config --local merge.conflictStyle diff3
```

Keep `rerere.autoupdate` disabled so a reused resolution must still be reviewed and staged manually.

### 3. Rebuild the candidate

Only after the scope checks pass:

```bash
git reset --hard develop
git merge --no-ff --no-commit main
```

Resetting is allowed only on the clean local dogfood branch. The reset intentionally discards the previous dogfood candidate; rerere retains reusable conflict resolutions.

### 4. Resolve conflicts by behavior

List unresolved files with:

```bash
git diff --name-only --diff-filter=U
git status --short
git rerere diff
```

For each conflict:

1. Identify the current behavior owner from code and the knowledge base.
2. Determine the valid behavior introduced by develop and main.
3. Produce the minimum merge that preserves both valid contracts.
4. Review rerere output before staging a reused resolution.
5. Stage only reviewed files.

Also inspect important files modified on both sides even when Git merged them automatically. Textual success does not prove semantic compatibility.

If a reused resolution is stale, run `git rerere forget <path>` and resolve that file again.

### 5. Delegate independent merge review

After every conflict is resolved and the reviewed merge result is staged, delegate one independent subagent to review the merge before final validation or commit. The review is mandatory for Refresh.

Give the subagent only the task-local evidence it needs:

- Repository path and the recorded develop/main source SHAs.
- The unresolved conflict list captured after `git merge`, including files already resolved and staged.
- A request to compare the staged merge result with both source snapshots and the current behavior owners.

Require the subagent to:

1. Work read-only. It must not edit, stage, commit, reset, or push.
2. Review every resolved conflict and important file modified by both branches even when Git merged it automatically.
3. Check that the merge preserves both branches' still-valid behavior, package ownership, public contracts, version intent, and test owners.
4. Report findings first, ordered by severity, with file paths, line numbers, evidence, and a concrete correction direction.
5. Explicitly state when it finds no blocking or high-risk merge error, and list remaining validation gaps separately.

Wait for the review result before continuing. Do not give the subagent the intended resolution or the main agent's conclusions; the review must be independent.

If the subagent reports a blocking or high-risk merge error, fix the merge, restage the affected files, and delegate another review. Repeat until no blocking or high-risk finding remains. If develop or main advances during review, discard the stale candidate through a new Refresh. If subagents are unavailable, stop and report that the mandatory merge review could not be completed; do not create the candidate commit.

### 6. Validate before committing

Run the evidence required by `docs/ai_knowledge/06_testing.md`. The default integration gate is:

```bash
git diff --cached --check
./gradlew :idea:compileKotlin :main:compileTestKotlin :cmd_line:compileTestKotlin
```

Then run targeted tests owned by the conflicted or semantically overlapping behavior. Never run unfiltered `:main:test` or `:idea:test`.

Before a candidate is promoted, also run the relevant manual or L3 dogfood scenarios. Compilation alone cannot verify runtime behavior.

Keep the merge uncommitted until the selected validation passes. If validation exposes an integration issue, preserve the failure evidence, fix and restage the merge result, repeat the independent merge review, and then rerun the affected validation. Any change to the staged merge result invalidates the previous subagent review.

### 7. Create the candidate merge commit

Use an English commit subject that remains appropriate after promotion:

```text
[other] integrate main updates into develop
```

Include the source SHAs, high-risk conflict decisions, and verification evidence in the body. Do not mention dogfood as a permanent product branch.

Immediately before committing, rerun `git rev-parse develop` and `git rev-parse main` and compare both values with the recorded source snapshots. If either branch advanced, discard the stale candidate through a new Refresh instead of committing it.

## Verify

Do not modify branches during a Verify-only request.

Inspect:

```bash
git status --short --branch
git show --summary --format=fuller dogfood
git merge-base --is-ancestor develop dogfood
git merge-base --is-ancestor main dogfood
git diff --check develop..dogfood
```

Confirm the dogfood candidate contains the current tips of both source branches. If either ancestry check fails, report the candidate as stale and recommend Refresh.

Run only the validation requested by the user or required to close missing evidence. Do not claim a candidate is promotable without current build and behavior evidence.

## Promote

Promote only after the user explicitly asks to synchronize or promote dogfood to develop.

Before changing `develop`:

1. Confirm the dogfood worktree and develop worktree are clean.
2. Confirm `git merge-base --is-ancestor develop dogfood` succeeds.
3. Confirm `git merge-base --is-ancestor main dogfood` succeeds.
4. Confirm the current dogfood candidate has passed the agreed integration, targeted, and dogfood verification.
5. Locate the worktree that has `develop` checked out using `git worktree list --porcelain`.

If either source branch advanced, stop and Refresh instead of promoting a stale candidate.

In the develop worktree, run only:

```bash
git merge --ff-only dogfood
```

Do not run a second `git merge main`. Verify that `develop` and `dogfood` resolve to the same commit afterward. Never push unless the user separately and explicitly asks for a push.

## Cleanup

Clean up only after the user explicitly states that the develop release is complete and asks to delete dogfood.

Before deletion, verify `dogfood` is an ancestor of `develop`. If dogfood is checked out in its worktree, detach that worktree at `develop` before deleting the branch. Then run:

```bash
git branch -D dogfood
git rerere gc
```

Do not delete the worktree or rerere cache manually. Report that the preserved worktree is detached after cleanup when applicable.

## Failure Handling

- Dirty dogfood or develop worktree: stop without stashing.
- Source branch advances during validation: discard the stale candidate through a new Refresh.
- Merge or validation failure: keep the merge state for diagnosis unless the user asks to abort; never report success.
- Mandatory subagent review unavailable or incomplete: stop before committing the candidate.
- Blocking or high-risk review finding: fix the merge and repeat independent review before validation and commit.
- Incorrect rerere resolution: forget only the affected path and resolve it again.
- Failed fast-forward: do not fall back to a normal merge; refresh the dogfood candidate.
- Any unexpected upstream or push configuration: stop and report the risk.

## Response Checklist

Use the repository-mandated execution checklist. Also report:

- Operation performed: Refresh, Verify, Promote, or Cleanup.
- Exact develop and main source SHAs.
- Conflict files and high-risk behavior decisions.
- Independent subagent review conclusion, findings, and any re-review performed after fixes.
- Reused rerere resolutions and any forgotten paths.
- Exact verification commands and results.
- Candidate or promoted commit hash.
- Confirmation that no push occurred.

## Build the Installable Package

After a successful Refresh, Verify, or Promote operation, finish by building the latest plugin package:

```bash
./gradlew :idea:buildPlugin
```

Locate the newest generated ZIP, verify that it exists, and resolve its absolute path:

```bash
dogfood_distribution_dir="$(pwd)/idea/build/distributions"
dogfood_plugin_package="$(find "$dogfood_distribution_dir" -maxdepth 1 -type f -name '*.zip' -exec ls -t {} + | head -n 1)"
test -n "$dogfood_plugin_package" && test -f "$dogfood_plugin_package"
realpath "$dogfood_plugin_package"
```

Report the absolute ZIP path on its own line so the user can copy and paste it into the IDE installation dialog. Treat a failed build or missing ZIP as a failed operation. Do not build a package for Cleanup-only requests.
