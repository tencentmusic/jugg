---
name: jugg-dogfood-sync
description: 'Incrementally refresh, verify, promote, or clean up the local Jugg dogfood integration branch. In the Jugg repository, treat the exact request "更新 dogfood" as Refresh: preserve the current dogfood history and merge only new local develop/main commits. Promote by merging main into develop and reusing dogfood rerere resolutions. Never push dogfood.'
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

- **Refresh**: incrementally merge new `develop` and `main` commits into the existing dogfood candidate.
- **Verify**: inspect and validate the current dogfood candidate without changing `develop`.
- **Promote**: merge the current `main` into `develop`, reuse dogfood conflict resolutions, and require the result to match the verified dogfood tree. Require an explicit request to synchronize or promote dogfood to develop.
- **Cleanup**: delete dogfood after the develop release. Require an explicit cleanup or deletion request.

Do not infer Promote from a Refresh or Verify request.

## Shared Invariants

1. Treat local `develop` and `main` branch refs as the source snapshots. Do not substitute remote-tracking refs silently.
2. During one integration cycle, preserve dogfood history and let it accumulate only merges from `develop` and `main` plus the resolutions required by those merges. Never add independent product changes.
3. Normal Refresh must not reset dogfood to develop. Reset dogfood only after a successful Promote or an explicitly requested rebuild.
4. Before any destructive operation, confirm the target branch and worktree are clean. Stop rather than stashing or discarding unrelated changes.
5. Preserve both branches' valid behavior. Never resolve a core conflict by choosing an entire `ours` or `theirs` file without behavior-level evidence.
6. Treat rerere as a textual conflict-resolution cache, not as a replacement for Git ancestry or the verified dogfood tree.
7. Do not push commits, branches, tags, or notes.
8. Stage and commit only the merge result for this workflow.

## Refresh

### 1. Establish the source snapshots

Inspect:

```bash
git status --short --branch
git worktree list --porcelain
git rev-parse dogfood
git rev-parse develop
git rev-parse main
git for-each-ref --format='%(upstream:short)' refs/heads/dogfood
```

Require all of the following:

- `develop`, `main`, and `dogfood` exist locally.
- The dogfood worktree is clean.
- `dogfood` has no upstream. If it has one, stop and report it rather than continuing.
- No merge, rebase, cherry-pick, or revert is already in progress.

Record the exact dogfood, develop, and main SHAs. Read the previously integrated source SHAs from the latest dogfood candidate commit. For a legacy candidate without trailers, use `git merge-base dogfood develop` and `git merge-base dogfood main` once as the migration baseline. New candidate commits must record `Dogfood-Previous`, `Dogfood-Develop`, and `Dogfood-Main` trailers.

Require the previously integrated develop/main SHAs to remain ancestors of both dogfood and their current source branches. If either source history was rewritten, stop and request an explicit rebuild instead of silently resetting dogfood.

### 2. Enable safe conflict reuse

Set repository-local configuration:

```bash
git config --local rerere.enabled true
git config --local rerere.autoupdate false
git config --local merge.conflictStyle diff3
```

Keep `rerere.autoupdate` disabled so a reused resolution must still be reviewed and staged manually.

### 3. Incrementally advance the candidate

Merge only source tips that are not already ancestors of dogfood. Use the fixed order `develop`, then `main`:

```bash
git merge --no-ff --no-commit develop
git merge --no-ff --no-commit main
```

Skip a merge when that source tip is already contained by dogfood. When both branches advanced, finish the first merge commit before starting the second. Leave the final merge uncommitted until review and validation complete. If neither branch advanced, report a no-op Refresh and do not create an empty commit.

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

Also inspect files modified by both source branches since their previously integrated SHAs, even when Git merged them automatically. Textual success does not prove semantic compatibility. Do not rescan the full historical divergence during an incremental Refresh.

If a reused resolution is stale, run `git rerere forget <path>` and resolve that file again.

### 5. Delegate independent merge review

After every conflict is resolved and the reviewed merge result is staged, delegate one independent subagent to review the merge before final validation or commit. The review is mandatory for Refresh.

Give the subagent only the task-local evidence it needs:

- Repository path, previous dogfood SHA, and the previous/current develop/main source SHAs.
- Conflict files from the incremental merges, including files already resolved and staged.
- A request to review `previous dogfood..new candidate`, the new source ranges, and the current behavior owners.

Require the subagent to:

1. Work read-only. It must not edit, stage, commit, reset, or push.
2. Review every new resolved conflict and important file modified by both branches in the new source ranges, even when Git merged it automatically.
3. Check that the merge preserves both branches' still-valid behavior, package ownership, public contracts, version intent, and test owners.
4. Report findings first, ordered by severity, with file paths, line numbers, evidence, and a concrete correction direction.
5. Explicitly state when it finds no blocking or high-risk merge error, and list remaining validation gaps separately.

Wait for the review result before continuing. Do not give the subagent the intended resolution or the main agent's conclusions; the review must be independent.

If the subagent reports a blocking or high-risk merge error, fix the merge, restage the affected files, and delegate another review. Repeat until no blocking or high-risk finding remains. If develop or main advances during review, finish or safely abort the active merge, then incrementally merge the new tip. If subagents are unavailable, stop and report that the mandatory merge review could not be completed; do not create the candidate commit.

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

Use an English commit subject that identifies the source advanced in this candidate, for example:

```text
[other] integrate main updates into current candidate
[other] integrate develop updates into current candidate
```

Include the source SHAs, high-risk conflict decisions, and verification evidence in the body. Do not mention dogfood as a permanent product branch.

End the commit body with:

```text
Dogfood-Previous: <previous dogfood SHA>
Dogfood-Develop: <current develop SHA>
Dogfood-Main: <current main SHA>
```

Immediately before committing, rerun `git rev-parse develop` and `git rev-parse main` and compare both values with the recorded source snapshots. If either branch advanced, finish the current candidate safely and run another incremental Refresh; never rebuild automatically.

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

Confirm the dogfood candidate contains the current tips of both source branches. If either ancestry check fails, report the candidate as stale and recommend an incremental Refresh.

Run only the validation requested by the user or required to close missing evidence. Do not claim a candidate is promotable without current build and behavior evidence.

## Promote

Promote only after the user explicitly asks to synchronize dogfood or merge main into develop.

Before changing `develop`:

1. Fix the exact develop, main, and verified dogfood SHAs.
2. Confirm the dogfood and develop worktrees are clean.
3. Confirm `git merge-base --is-ancestor develop dogfood` succeeds.
4. Confirm `git merge-base --is-ancestor main dogfood` succeeds.
5. Confirm the current dogfood candidate has passed the agreed integration, targeted, and dogfood verification.
6. Locate the worktree that has `develop` checked out using `git worktree list --porcelain`.

If either source branch advanced, stop and incrementally Refresh dogfood before Promote.

In the develop worktree, merge main without committing:

```bash
git merge --no-ff --no-commit main
```

Review every rerere result before staging. Then require the uncommitted develop merge tree to match the verified dogfood tree:

```bash
git diff --exit-code dogfood
```

Any difference means rerere did not fully reproduce the verified candidate, including semantic fixes outside textual conflict blocks. Investigate and reconcile the difference before committing. Run the selected validation again in the develop worktree, then create the formal main merge commit.

After the formal merge succeeds, reset the clean local dogfood worktree to `develop`. This starts the next incremental cycle while preserving the repository-local rerere cache. Never fast-forward develop to dogfood and never push unless the user separately and explicitly asks for a push.

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
- Source branch advances during Refresh: complete or abort the active merge safely, then incrementally merge the new tip; never reset automatically.
- Previously integrated source SHA is no longer an ancestor of its branch: stop and request an explicit rebuild.
- Merge or validation failure: keep the merge state for diagnosis unless the user asks to abort; never report success.
- Mandatory subagent review unavailable or incomplete: stop before committing the candidate.
- Blocking or high-risk review finding: fix the merge and repeat independent review before validation and commit.
- Incorrect rerere resolution: forget only the affected path and resolve it again.
- Develop merge result differs from the verified dogfood tree: stop before committing, inspect the difference, and restore tree equality.
- Any unexpected upstream or push configuration: stop and report the risk.

## Response Checklist

Use the repository-mandated execution checklist. Also report:

- Operation performed: Refresh, Verify, Promote, or Cleanup.
- Exact develop and main source SHAs.
- Conflict files and high-risk behavior decisions.
- Independent subagent review conclusion, findings, and any re-review performed after fixes.
- Previous dogfood SHA, incremental source ranges, reused rerere resolutions, and any forgotten paths.
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
