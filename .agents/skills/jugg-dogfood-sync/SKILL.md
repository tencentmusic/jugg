---
name: jugg-dogfood-sync
description: 'Incrementally refresh, exactly reanchor after content-preserving source history rewrites, verify, promote, or clean up the local Jugg dogfood integration branch. In the Jugg repository, treat the exact request "更新 dogfood" as Refresh: preserve the current candidate tree and merge only new local develop/main content. Promote by merging main into develop and reusing dogfood rerere resolutions. Never push dogfood.'
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

- **Refresh**: incrementally merge new `develop` and `main` commits into the existing dogfood candidate. If a source history was rewritten without changing its recorded tree, use the exact-tree Reanchor fast path before merging later source commits.
- **Verify**: inspect and validate the current dogfood candidate without changing `develop`.
- **Promote**: merge the current `main` into `develop`, reuse dogfood conflict resolutions, and require the result to match the verified dogfood tree. Require an explicit request to synchronize or promote dogfood to develop.
- **Cleanup**: delete dogfood after the develop release. Require an explicit cleanup or deletion request.

Do not infer Promote from a Refresh or Verify request.

Reanchor is a guarded Refresh fast path, not a separate authorization or an approximate history-mapping mode. Never use patch-id, range-diff, or a partially matching tree as proof that a rewrite is content-preserving.

## Shared Invariants

1. Treat local `develop` and `main` branch refs as the source snapshots. Do not substitute remote-tracking refs silently.
2. During one integration cycle, preserve dogfood history and let it accumulate only merges from `develop` and `main` plus the resolutions required by those merges. Never add independent product changes.
3. Normal Refresh must not reset dogfood to develop. A Reanchor may replace the local dogfood ref only after exact tree identity, parent mapping, a recovery ref, and independent review are established. Otherwise reset dogfood only after a successful Promote or an explicitly requested rebuild.
4. Before any destructive operation, confirm the target branch and worktree are clean. Stop rather than stashing or discarding unrelated changes.
5. Preserve both branches' valid behavior. Never resolve a core conflict by choosing an entire `ours` or `theirs` file without behavior-level evidence.
6. Treat rerere as a textual conflict-resolution cache, not as a replacement for Git ancestry or the verified dogfood tree.
7. Do not push commits, branches, tags, or notes.
8. Stage and commit only the merge result for this workflow.
9. Exact tree identity is the only lossless rewrite proof. If any rewritten source tree differs from its recorded tree, stop and request an explicit rebuild.

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

Locate the worktree whose symbolic `HEAD` is `dogfood`. Run every remaining Refresh Git, Gradle, review, validation, commit, and package command with that dogfood worktree as the actual working directory. Never continue Refresh from the develop or main worktree. Code blocks below omit `git -C` only under this working-directory invariant. Before proceeding, require:

```bash
test "$(git symbolic-ref --short HEAD)" = "dogfood"
test -z "$(git status --short)"
```

Record the exact dogfood, develop, and main SHAs. Read the previously integrated source SHAs from the latest dogfood candidate commit. For a legacy candidate without trailers, use `git merge-base dogfood develop` and `git merge-base dogfood main` once as the migration baseline. New candidate commits must record `Dogfood-Previous`, `Dogfood-Develop`, and `Dogfood-Main` trailers.

Require the previously integrated develop/main SHAs to remain ancestors of dogfood. If both recorded source SHAs remain ancestors of their current source branches, continue with normal Refresh. If either source history was rewritten, evaluate the exact-tree Reanchor fast path below. If Reanchor is not eligible, stop and request an explicit rebuild instead of silently resetting dogfood.

### 2. Reanchor after a content-preserving history rewrite

Use Reanchor only when at least one recorded source SHA is no longer an ancestor of its current local branch. Fix these values before proceeding:

- Previous dogfood candidate and its recorded develop/main SHAs.
- Current local develop/main SHAs.
- Commit tree IDs for all four source SHAs.

Require all of the following:

1. The previous dogfood candidate contains both recorded source SHAs.
2. For every rewritten source, the current source tip has exactly the same commit tree ID as its recorded SHA.
3. For every non-rewritten source, the recorded SHA remains an ancestor of the current source branch.
4. The dogfood worktree is clean and no Git operation is in progress.
5. The previous dogfood candidate was produced by this workflow and contains no independent product changes.

Choose the Reanchor parents independently for each source:

- Rewritten and tree-identical source: use its current tip.
- Non-rewritten source: use its recorded SHA. Merge any later commits from that source after Reanchor.

In the Reanchor commit, `Dogfood-Develop` and `Dogfood-Main` must equal these selected parents, not later source tips that are not yet ancestors. Record the fixed current source tips separately in the body as pending Refresh snapshots when either side has later commits.

Create a local recovery ref before creating the candidate. If the exact ref already exists, require it to resolve to the previous dogfood SHA; never overwrite a mismatched recovery ref. Otherwise create it with a must-not-exist old value:

```bash
backup_ref="refs/dogfood-backups/<previous-dogfood-sha>"
git update-ref "$backup_ref" <previous-dogfood-sha> ""
```

Create a provisional merge commit with `git commit-tree` using:

- The exact tree of the previous dogfood candidate.
- The selected develop parent first and main parent second.
- An English subject such as `[other] reanchor current candidate after source history rewrite`.
- `Dogfood-Previous`, `Dogfood-Develop`, `Dogfood-Main`, and `Dogfood-Reanchor-From` trailers.
- The verification commands that will be run after the ref move.

Before moving dogfood, require:

```bash
test "$(git rev-parse <previous-dogfood-sha>^{tree})" = "$(git rev-parse <reanchor-commit>^{tree})"
git diff --exit-code <previous-dogfood-sha> <reanchor-commit>
test "$(git show -s --format='%P' <reanchor-commit>)" = "<reanchor-develop-parent> <reanchor-main-parent>"
git merge-base --is-ancestor <reanchor-develop-parent> <reanchor-commit>
git merge-base --is-ancestor <reanchor-main-parent> <reanchor-commit>
```

Delegate an independent read-only review of the fixed SHAs, tree IDs, parent selection, recovery ref, and provisional commit. The reviewer must explicitly confirm exact tree identity and no blocking/high-risk finding. No source-level conflict review is required for the identical tree, but later incremental merges still use the normal review gate.

Immediately before moving dogfood, confirm current develop/main still equal the fixed source snapshots. Locate the dogfood worktree from `git worktree list --porcelain`, then update only the dogfood ref with compare-and-swap:

```bash
git -C <dogfood-worktree> update-ref refs/heads/dogfood <reanchor-commit> <previous-dogfood-sha>
test "$(git -C <dogfood-worktree> rev-parse HEAD)" = "<reanchor-commit>"
test -z "$(git -C <dogfood-worktree> status --short)"
```

Do not run `reset --hard`. The old and Reanchor commits have the same tree, so the clean dogfood index and worktree are already synchronized. A reset from another worktree could move or overwrite the wrong branch.

Run the default integration gate and build the installable package because commit-derived version/build metadata changes even when the source tree does not. If validation fails, preserve the failure evidence, atomically restore dogfood from the recovery ref, and report the failed Reanchor. Do not fall through to an approximate transplant.

Restore only when dogfood still points to the failed Reanchor commit:

```bash
test "$(git rev-parse refs/dogfood-backups/<previous-dogfood-sha>)" = "<previous-dogfood-sha>"
git -C <dogfood-worktree> update-ref refs/heads/dogfood <previous-dogfood-sha> <reanchor-commit>
test "$(git -C <dogfood-worktree> rev-parse HEAD)" = "<previous-dogfood-sha>"
test -z "$(git -C <dogfood-worktree> status --short)"
```

After a successful Reanchor, continue normal Refresh and merge only current develop/main tips not already contained by the new candidate. If no source has later commits, the reviewed Reanchor commit is the final candidate.

### 3. Enable safe conflict reuse

Set repository-local configuration:

```bash
git config --local rerere.enabled true
git config --local rerere.autoupdate false
git config --local merge.conflictStyle diff3
```

Keep `rerere.autoupdate` disabled so a reused resolution must still be reviewed and staged manually.

### 4. Incrementally advance the candidate

Merge only the fixed source snapshot SHAs that are not already ancestors of dogfood. Use the fixed order `develop`, then `main`:

```bash
git merge --no-ff --no-commit <fixed-develop-sha>
test "$(git rev-parse MERGE_HEAD)" = "<fixed-develop-sha>"
git merge --no-ff --no-commit <fixed-main-sha>
test "$(git rev-parse MERGE_HEAD)" = "<fixed-main-sha>"
```

Skip a merge when that fixed source snapshot is already contained by dogfood. When both snapshots need merging, finish the first merge commit before starting the second. Leave the final merge uncommitted until review and validation complete. If neither snapshot advanced, report a no-op Refresh and do not create an empty commit.

Never merge the movable branch name after fixing snapshots. After finishing the candidate for the fixed SHAs, reread local develop/main. If a ref advanced, start another Refresh cycle. If the previous fixed SHA is no longer its ancestor, run the rewrite/Reanchor eligibility check again instead of treating it as a normal incremental merge.

### 5. Resolve conflicts by behavior

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

### 6. Delegate independent merge review

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

If the subagent reports a blocking or high-risk merge error, fix the merge, restage the affected files, and delegate another review. Repeat until no blocking or high-risk finding remains. If develop or main advances during review, finish the already fixed and reviewed snapshot candidate or safely abort it, then start a new Refresh cycle. If the new ref no longer contains the previous fixed snapshot, run Reanchor eligibility again instead of merging it as a normal descendant. If subagents are unavailable, stop and report that the mandatory merge review could not be completed; do not create the candidate commit.

### 7. Validate before committing

Run the evidence required by `docs/ai_knowledge/06_testing.md`. The default integration gate is:

```bash
git diff --cached --check
./gradlew :idea:compileKotlin :main:compileTestKotlin :cmd_line:compileTestKotlin
```

Then run targeted tests owned by the conflicted or semantically overlapping behavior. Never run unfiltered `:main:test` or `:idea:test`.

Before a candidate is promoted, also run the relevant manual or L3 dogfood scenarios. Compilation alone cannot verify runtime behavior.

Keep the merge uncommitted until the selected validation passes. If validation exposes an integration issue, preserve the failure evidence, fix and restage the merge result, repeat the independent merge review, and then rerun the affected validation. Any change to the staged merge result invalidates the previous subagent review.

Reanchor is the only exception to the uncommitted-merge rule because new parentage requires a commit object. Its candidate must retain the exact previously verified tree, remain recoverable through the backup ref, pass independent proof review before the branch move, and be rolled back if the post-move validation fails.

### 8. Create or retain the candidate merge commit

Use an English commit subject that identifies the source advanced in this candidate, for example:

```text
[other] integrate main updates into current candidate
[other] integrate develop updates into current candidate
```

Include the source SHAs, high-risk conflict decisions, and verification evidence in the body. Do not mention dogfood as a permanent product branch.

End the commit body with:

```text
Dogfood-Previous: <previous dogfood SHA>
Dogfood-Develop: <fixed develop SHA actually contained by this commit>
Dogfood-Main: <fixed main SHA actually contained by this commit>
```

The trailers always describe the fixed source snapshots actually contained by this candidate, even when a movable branch ref advances during review or validation. Immediately before committing, rerun `git rev-parse develop` and `git rev-parse main` and compare both values with the fixed source snapshots. If either branch advanced, do not merge the movable ref into the current candidate. Finish the already reviewed fixed-snapshot candidate safely, then start another Refresh cycle; never rebuild automatically.

For a successful Reanchor with no later source commits, retain the already reviewed Reanchor commit rather than creating a second commit with a different hash. Report its exact tree identity and recovery ref. If later commits were merged, the final merge commit uses the normal trailers and records the Reanchor commit as `Dogfood-Previous`.

## Verify

Do not modify branches during a Verify-only request.

Locate the dogfood worktree, run every Verify Git and Gradle command with it as the actual working directory, and require `git symbolic-ref --short HEAD` to equal `dogfood`. Never validate a candidate from the develop or main worktree.

Inspect:

```bash
git status --short --branch
git show --summary --format=fuller dogfood
git merge-base --is-ancestor develop dogfood
git merge-base --is-ancestor main dogfood
git diff --check develop..dogfood
```

Confirm the dogfood candidate contains the current tips of both source branches. If either ancestry check fails, report the candidate as stale and recommend an incremental Refresh; Refresh may use Reanchor only when exact tree identity proves a source rewrite was content-preserving.

Run only the validation requested by the user or required to close missing evidence. Build the installable package during Verify only when requested or when package/build evidence is needed. Do not claim a candidate is promotable without current build and behavior evidence.

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

Immediately before changing develop, confirm the fixed develop/main refs have not moved. In the develop worktree, merge the fixed main SHA without committing and verify `MERGE_HEAD`:

```bash
git merge --no-ff --no-commit <fixed-main-sha>
test "$(git rev-parse MERGE_HEAD)" = "<fixed-main-sha>"
```

Review every rerere result before staging. Then require the uncommitted develop merge tree to match the fixed verified dogfood tree:

```bash
git diff --exit-code <fixed-dogfood-sha>
```

Any difference means rerere did not fully reproduce the verified candidate, including semantic fixes outside textual conflict blocks. Investigate and reconcile the difference before committing. Run the selected validation again in the develop worktree. Immediately before committing, confirm develop, main, and dogfood still equal their fixed SHAs. If any ref moved, stop, safely abort the uncommitted formal merge, Refresh dogfood when needed, and restart Promote from new fixed snapshots.

After the formal merge succeeds, reset the clean local dogfood worktree to `develop`. This starts the next incremental cycle while preserving the repository-local rerere cache. Never fast-forward develop to dogfood and never push as part of this skill.

## Cleanup

Clean up only after the user explicitly states that the develop release is complete and asks to delete dogfood.

Before deletion, verify `dogfood` is an ancestor of `develop`. If dogfood is checked out in its worktree, detach that worktree at `develop` before deleting the branch. Then run:

```bash
git branch -D dogfood
git rerere gc
```

Do not delete the worktree or rerere cache manually. Preserve `refs/dogfood-backups/*` by default, list them in the response, and delete only an exact backup ref when the user explicitly requests that recovery ref's deletion. Report that the preserved worktree is detached after cleanup when applicable.

## Failure Handling

- Dirty dogfood or develop worktree: stop without stashing.
- Source branch advances during Refresh: finish the fixed-snapshot candidate or safely abort it, then start a new cycle; if ancestry was rewritten, rerun Reanchor eligibility instead of merging blindly.
- Previously integrated source SHA is no longer an ancestor of its branch: attempt Reanchor only when the rewritten source tip has the exact recorded tree; otherwise stop and request an explicit rebuild.
- Reanchor tree mismatch, ambiguous parent mapping, missing recovery ref, or failed independent proof review: do not move dogfood; request an explicit rebuild.
- Reanchor validation failure after the branch move: preserve evidence, restore dogfood with compare-and-swap from `refs/dogfood-backups/<previous-dogfood-sha>`, and stop.
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
- For Refresh: independent subagent review conclusion, findings, and any re-review performed after fixes. For other operations, report review only when one was actually required.
- Previous dogfood SHA, incremental source ranges, reused rerere resolutions, and any forgotten paths.
- For Reanchor: recorded/current source tree IDs, selected parents, exact tree comparison, recovery ref, and rollback status.
- Exact verification commands and results.
- Candidate or promoted commit hash.
- Confirmation that no push occurred.

## Build the Installable Package

After a successful Refresh or Promote operation, finish by building the latest plugin package. For Verify, build it only when requested or when package/build evidence is part of the verification. Run the build and resolve the package path in the worktree whose result was validated: dogfood for Refresh/Verify, develop for Promote. Never build or report a package from another worktree:

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
