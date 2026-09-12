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
- **Update Changelog Without Changing Version**: the user asks to 更新 changelog, 只更新 changelog, 更新 changelog 版本不变, or to refresh release notes while keeping the current plugin version. Do not increment `versionName`. Move the latest version-update commit to `HEAD`, then refresh changelog files with Changelog Entry Rules. An existing draft that already covers a hash range is a work split, not a second inclusion policy: re-audit those lines, then append only later commits the draft does not already cover.
- **Finalize an Existing Version Commit**: the user asks 版本提交收尾, or to summarize later changes into an already-created version commit and move that commit to `HEAD`.

Never treat a changelog-only request as a version bump. Never create a second `[other] update version to X.Y.Z` commit for the same version.

## Changelog Entry Rules

Apply these rules to RC YAML and HTML in every workflow. A hash range, an uncommitted draft, or “only continue later commits” does not freeze existing lines or create a second inclusion policy.

Draft `change_log/change_log_rc_cn.yaml` first. Mirror meaning into `change_log_rc.yaml` and both HTML pages. Do not write four independent summaries. Within one patch version, YAML and HTML include the same capability points. HTML still aggregates across patches inside one `X.Y` `<ol>`.

### What to list

List user-observable capability points and failure symptoms for this version window.

Commit prefixes `[feature]` / `[optimize]` / `[bugfix]` are candidate categories only. Classify by what the user perceives: a new capability, an existing path that is faster or more stable, or a distinct failure. Do not copy commit subjects that name mechanisms.

Skip `[docs]`, `[test]`, `[refactor]`, and `[other]` unless they have user-visible product impact. Also skip skill, wiki, and repo-meta files (`.agents/skills`, `docs/skills`, `docs/wiki`, contributing/security templates).

### Follow-ups stay inside the parent line

In the same version window, a later commit that only makes an already-listed capability work (compat, recovery, error path, edge case) does not get its own line. Fold it into the parent, or rewrite the parent so the parent covers it. Do not delete the follow-up and leave an unchanged parent that never mentioned it.

Keep a separate line when a reader of the parent would not know this distinct wrong behavior was fixed. Sharing a subsystem with an earlier feature is not enough to absorb it.

Examples:

| Keep or absorb | Why |
|---|---|
| Absorb Hilt receiver injection into `兼容 Hilt，…` | Makes the listed Hilt capability work |
| Absorb system-app reinstall / custom-script recovery into those features | Same capability, error-path fixes |
| Rewrite heartbeat/hot-reload commits into `优化自研部署通道耗时` when that is the user-facing story | Mechanism is not a marketed feature |
| Keep `避免 profile 构建误用 debug 任务元数据` | Flutter/C++ source support does not tell the reader that profile variants stop picking debug metadata |

### Write for users

Rewrite; do not transcribe git. Chinese RC is the content baseline.

- Feature: `支持` / `兼容` + object, then the scene. Do not lead with pipeline steps.
- Optimize: the user-perceived result (`耗时`, `更简洁的警告`). Not heartbeat, D8, JVMTI, or similar internals.
- Bugfix: the user-visible failure plus scope (`小概率`, `特定版本 AGP 8.8.0`, `Android Studio Quail`). Not internal root cause (`注入运行时脱糖`, `父类型`, `Receiver`).

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

4. Update RC changelog YAML with Changelog Entry Rules:
   - Draft `change_log/change_log_rc_cn.yaml` first, then mirror meaning into `change_log/change_log_rc.yaml`.
   - Keep exactly one top-level `- version: X.Y.Z` declaration per patch version.
   - If the target version does not exist, prepend a new top-level entry.
   - If the target version already exists, amend that entry's `date`, `isNeedReinstall`, and `updates` as needed. Never create a second entry for the same patch version.
   - Include `date: YYYY.MM.DD`.
   - Keep English and Chinese content aligned by meaning, not by literal wording.
   - Prefix each `updates` item with `[feature]`, `[optimize]`, or `[bugfix]`, using the same category as the matching HTML entry.
   - Sort `updates` by category: `[feature]`, then `[optimize]`, then `[bugfix]`. Preserve reasonable order inside each category.
   - Quote YAML strings that start with `[` so they remain scalars, for example `- "[bugfix] Prevent APK updates from failing on paths with shell characters"`.
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
   - Mirror the Chinese RC capability points. Within one patch version, do not give HTML a different inclusion set from YAML.
   - Before adding an entry, compare its user-facing behavior with the existing entries in the active minor-series section. If the commit only fixes, optimizes, or refines a feature point already described there, do not add another HTML entry. Apply this rule equally to the English and Chinese HTML pages, and to RC YAML for the same patch.
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
4. After the move, update changelog files from the newly included commits using Changelog Entry Rules:
   - Re-audit any existing draft for this version, then append only later commits that are not already covered.
   - Draft Chinese RC first; mirror meaning into English RC and both HTML pages.
   - Update `date` to the local date. Amend the matching RC declaration and follow the HTML aggregation and category-sort rules from the version workflow. Never create a second RC declaration or HTML section for the same patch version.
5. Amend only those changelog file changes into the version-update commit now at `HEAD`. Keep the original subject, author, and author date. Do not change `versionName`. Do not squash `<version-commit>..ORIG_HEAD` into the version commit.
6. Recreate the lightweight `X.Y.Z` tag on the amended `HEAD` only if the old tag pointed at the version commit that was moved. If it pointed elsewhere, stop and report the conflict. Do not push.
7. Verify: `HEAD` subject is `[other] update version to X.Y.Z`, the intermediate commit count equals `<version-commit>..ORIG_HEAD`, `versionName` is unchanged, `git diff ORIG_HEAD --stat` shows only changelog files, and the working tree is clean except restored unrelated files. Report the new hash as the successor of the original version commit.

If `HEAD` is already the version-update commit, skip the move. Only amend changelog files when newly included user-visible commits still need entries.

## Finalize an Existing Version Commit

Use this workflow when the user says `版本提交收尾` or asks to summarize changes since an existing version commit, amend the summary into that commit, and move the version commit to `HEAD` or the last commit.

1. Resolve the version commit and original `HEAD`, then verify the version commit is an ancestor of `HEAD` and the working tree is clean.
   - Resolve the exact version tag before rewriting. If it points to the version commit being finalized, recreate it on the amended successor only after the rewrite succeeds. If it points elsewhere, stop and report the conflict.
2. Summarize the user-visible changes in `<version-commit>..HEAD` with Changelog Entry Rules. Draft Chinese RC first, then mirror into English RC and HTML. Amend the matching RC version entry and follow the same-patch inclusion and HTML aggregation rules.
3. Keep one RC declaration per patch: amend it when the version exists, or prepend it when the version does not exist.
4. Commit only the changelog summary as a temporary standalone commit.
5. Rewrite the commit order so every commit after the version commit remains a separate commit in its original order, followed by the version commit at the tip.
6. Amend only the temporary changelog-summary commit into the relocated version commit. Preserve the version commit's original subject, author, and author date unless the user explicitly requests changes.
7. Never squash the commits in `<version-commit>..HEAD` into the version commit. In this workflow, "summarize changes" means update the release notes, not combine the implementation commits.
8. Verify the final tree matches the tree produced by the original `HEAD` plus the changelog update. Confirm the version commit is `HEAD`, the intermediate commit count is preserved, and the working tree is clean.
9. Create or recreate the lightweight `X.Y.Z` tag on the final `HEAD`, then verify it resolves to that commit. Never move a tag that originally pointed outside the version commit being finalized.

Git amend changes the commit hash. Report the new hash as the amended successor of the original version commit rather than claiming the original hash still exists at `HEAD`.

## Common Mistakes

| Mistake | Do this instead |
|---|---|
| Keep every `[bugfix]` from the same window as the parent feature | Absorb follow-ups into the parent, or rewrite the parent |
| Treat an uncommitted draft or a hash range as already-approved | Re-audit with these rules, then append only uncovered later commits |
| Copy commit subjects (`AGP D8`, heartbeat, `注入运行时脱糖`) | Rewrite the user-visible result and scope |
| Promote a commit `[feature]` (hot reload) when users only see latency | Classify by user perception |
| Absorb profile/debug metadata into Flutter/C++ source support | Keep distinct wrong behavior on its own line |
| Update one changelog file and leave the other three stale | Chinese RC first, then mirror the other three |

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
