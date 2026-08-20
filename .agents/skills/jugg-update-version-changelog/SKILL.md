---
name: jugg-update-version-changelog
description: Update the Jugg plugin version and changelog files, including changelog-only refreshes that keep the current version and finalizing an existing version commit. Use only inside a Jugg project when the user asks in Chinese or English to "更新版本", "更新 change log", "更新 changelog", "只更新 changelog", "更新 changelog 版本不变", "版本提交收尾", "把版本提交移到最后", "把版本提交置为 HEAD", update version, update change log, changelog only, keep the version unchanged, release a new version, prepare Jugg release notes, or rebase an update-version commit to the last commit. Do not use outside Jugg repositories.
---

# Jugg Update Version Changelog

## Overview

Use this skill to update Jugg release metadata consistently: Gradle version, RC changelog YAML, HTML changelog pages, verification, commit staging, and version tagging. Keep the workflow scoped to Jugg repositories only.

## Scope Guard

Before editing anything, confirm the current working tree is a Jugg repository. Prefer the user's current directory, then search upward for these project markers:

- `build.gradle`
- `change_log/change_log_rc.yaml`
- `change_log/change_log_rc_cn.yaml`
- `docs/ai_knowledge/00_overview.md`

If those markers are missing, stop and tell the user this skill is only for Jugg projects.

Inside a Jugg project, follow the repository's `AGENTS.md` instructions first. In particular, read the required knowledge-base files before code or documentation changes when the project instructions require it.

## Choose the Workflow

- **Update Version**: the user asks to 更新版本, bump the plugin version, or release a new version. Increment `versionName`, write changelog files, create a new `[other] update version to X.Y.Z` commit, and tag it.
- **Update Changelog Without Changing Version**: the user asks to 更新 changelog, 只更新 changelog, 更新 changelog 版本不变, or to refresh release notes while keeping the current plugin version. Do not increment `versionName`. Move the latest version-update commit to `HEAD`, then add changelog entries for commits newly included by that move that are not already listed.
- **Finalize an Existing Version Commit**: the user asks 版本提交收尾, or to summarize later changes into an already-created version commit and move that commit to `HEAD`.

Never treat a changelog-only request as a version bump. Never create a second `[other] update version to X.Y.Z` commit for the same version.

## Update Version

1. Inspect the current state:
   - Run `git status --short`.
   - Read the root `build.gradle` version.
   - Check tracked changelog files with `git ls-files change_log idea/src/main/resources/change_log`.

2. Decide the target version:
   - Use the exact version when the user provides one.
   - Otherwise increment the patch version from the existing Jugg plugin version.
   - Use the local date in `YYYY.MM.DD` format for changelog entries.

3. Update version metadata:
   - Update the root `build.gradle` `versionName` value.
   - Keep formatting consistent with the existing file.

4. Update RC changelog YAML:
   - Update `change_log/change_log_rc.yaml`.
   - Update `change_log/change_log_rc_cn.yaml`.
   - Keep exactly one top-level `- version: X.Y.Z` declaration per patch version.
   - If the target version does not exist, prepend a new top-level entry.
   - If the target version already exists, amend that entry's `date`, `isNeedReinstall`, and `updates` as needed. Never create a second entry for the same patch version.
   - Include `date: YYYY.MM.DD`.
   - Keep English and Chinese content aligned by meaning, not by literal wording.
   - If tracked resource copies exist under `idea/src/main/resources/change_log/`, update those copies too. Do not create or stage untracked resource copies unless the repository already tracks them.

5. Update HTML changelog pages:
   - Update `change_log/change_log.html`.
   - Update `change_log/change_log_cn.html`.
   - If tracked resource copies exist under `idea/src/main/resources/change_log/`, update those copies too.
   - Compare the target version with the latest HTML section using semantic-version components.
   - For a major or minor version change, prepend a new `<h2>X.Y.Z (YYYY.MM.DD)</h2>` and a new `<ol>` containing only entries for the new version series. Never merge these entries into the previous major/minor list.
   - For a patch-only change within the same `X.Y` series, reuse the existing `<ol>` and update its `<h2>` version and date, for example `3.0.21` to `3.0.22 (2026.06.27)`.
   - If the exact target version section already exists, amend that section instead of creating a duplicate.
   - Keep one aggregated HTML section per minor series.
   - Before adding an entry, compare its user-facing behavior with the existing entries in the active minor-series section. If the commit only fixes, optimizes, or refines a feature point already described there, do not add another HTML entry. Apply this rule equally to the English and Chinese HTML pages.
   - HTML aggregation and deduplication do not apply to RC YAML. RC uses one top-level declaration per patch version and amends the matching declaration when the target version already exists.
   - Sort entries by category within the section: `[feature]`, then `[optimize]`, then `[bugfix]`. Preserve reasonable order inside each category.
   - If an entry has another recognized prefix from the repository's commit convention, place it after the three main product categories unless the user says otherwise.

6. Verify:
   - Run `git diff --check`.
   - Run a targeted version check, usually `./gradlew :idea:properties --no-daemon | rg "Plugin Version|^version:"`.
   - For changelog-only/version metadata changes, do not add JOOX Android unit tests.
   - Inspect `git diff --stat` and `git diff -- <files>` before committing.

7. Commit:
   - Use the exact commit message when the user provides one.
   - Otherwise use `[other] update version to X.Y.Z`.
   - Stage only files changed for this version/changelog task.
   - Never stage unrelated user changes or untracked generated files.

8. Tag:
   - Create a lightweight tag named exactly `X.Y.Z` on the completed version commit, without a `v` prefix.
   - Create the tag only after the commit and all verification succeed.
   - If the tag already resolves to the completed version commit, treat tagging as complete.
   - If the tag exists on any other commit, stop and report the conflict. Never force, move, or delete it unless the user explicitly requests that destructive change.
   - Verify the tag resolves to `HEAD` with `test "$(git rev-parse X.Y.Z^{commit})" = "$(git rev-parse HEAD)"`.
   - Do not push the commit or tag unless the user explicitly asks.

## Update Changelog Without Changing Version

Use this workflow when the plugin version stays `X.Y.Z`. The version-update commit must end at `HEAD` after the changelog refresh.

1. Inspect state. Confirm root `build.gradle` `versionName` is the version to keep. Resolve the latest `[other] update version to X.Y.Z` commit for that version. Verify it is an ancestor of `HEAD`. Stash unrelated dirty or untracked files; never stage them for this task.
2. Record `ORIG_HEAD` and the version-update commit hash. List `<version-commit>..HEAD`. Those commits are what the move newly includes under this version.
3. Move the version-update commit to `HEAD` without squashing later commits. Replay `<version-commit>..HEAD` onto the version commit's parent, then cherry-pick the original version-update commit onto the new tip. Keep each later commit separate and in its original order.
4. After the move, update changelog files from the newly included commits:
   - Draft user-visible `[feature]` / `[optimize]` / `[bugfix]` entries.
   - Skip `[docs]`, `[test]`, `[refactor]`, and `[other]` unless they have user-visible product impact.
   - Compare each draft with the current `X.Y.Z` RC entry and the active HTML minor-series section. Add only entries that are not already described.
   - Update `date` to the local date. Amend the matching RC declaration and follow the HTML aggregation and category-sort rules from the version workflow. Never create a second RC declaration or HTML section for the same patch version.
5. Amend only those changelog file changes into the version-update commit now at `HEAD`. Keep the original subject, author, and author date. Do not change `versionName`. Do not squash `<version-commit>..ORIG_HEAD` into the version commit.
6. Recreate the lightweight `X.Y.Z` tag on the amended `HEAD` only if the old tag pointed at the version commit that was moved. If it pointed elsewhere, stop and report the conflict. Do not push.
7. Verify: `HEAD` subject is `[other] update version to X.Y.Z`, the intermediate commit count equals `<version-commit>..ORIG_HEAD`, `versionName` is unchanged, `git diff ORIG_HEAD --stat` shows only changelog files, and the working tree is clean except restored unrelated files. Report the new hash as the successor of the original version commit.

If `HEAD` is already the version-update commit, skip the move. Only amend changelog files when newly included user-visible commits still need entries.

## Finalize an Existing Version Commit

Use this workflow when the user says `版本提交收尾` or asks to summarize changes since an existing version commit, amend the summary into that commit, and move the version commit to `HEAD` or the last commit.

1. Resolve the version commit and original `HEAD`, then verify the version commit is an ancestor of `HEAD` and the working tree is clean.
   - Resolve the exact version tag before rewriting. If it points to the version commit being finalized, recreate it on the amended successor only after the rewrite succeeds. If it points elsewhere, stop and report the conflict.
2. Summarize the user-visible changes in `<version-commit>..HEAD`. Update the RC and aggregated HTML changelogs with that summary. Amend the matching RC version entry and follow the HTML deduplication rules.
3. Keep one RC declaration per patch: amend it when the version exists, or prepend it when the version does not exist.
4. Commit only the changelog summary as a temporary standalone commit.
5. Rewrite the commit order so every commit after the version commit remains a separate commit in its original order, followed by the version commit at the tip.
6. Amend only the temporary changelog-summary commit into the relocated version commit. Preserve the version commit's original subject, author, and author date unless the user explicitly requests changes.
7. Never squash the commits in `<version-commit>..HEAD` into the version commit. In this workflow, "summarize changes" means update the release notes, not combine the implementation commits.
8. Verify the final tree matches the tree produced by the original `HEAD` plus the changelog update. Confirm the version commit is `HEAD`, the intermediate commit count is preserved, and the working tree is clean.
9. Create or recreate the lightweight `X.Y.Z` tag on the final `HEAD`, then verify it resolves to that commit. Never move a tag that originally pointed outside the version commit being finalized.

Git amend changes the commit hash. Report the new hash as the amended successor of the original version commit rather than claiming the original hash still exists at `HEAD`.

## File Checklist

Usually inspect or edit these files:

- `build.gradle`
- `change_log/change_log_rc.yaml`
- `change_log/change_log_rc_cn.yaml`
- `change_log/change_log.html`
- `change_log/change_log_cn.html`

Only if tracked:

- `idea/src/main/resources/change_log/change_log_rc.yaml`
- `idea/src/main/resources/change_log/change_log_rc_cn.yaml`
- `idea/src/main/resources/change_log/change_log.html`
- `idea/src/main/resources/change_log/change_log_cn.html`

## Response Checklist

When the Jugg repository requires a final execution checklist, include the repository-mandated section and report:

- The files or docs used for locating the change.
- Whether project docs needed updates.
- The exact commit message or `N/A` if no repository commit was made.
- The exact tag and target commit, or `N/A` if no tag was created or verified.
- The exact verification commands run.
