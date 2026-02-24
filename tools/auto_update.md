# Auto update version and change log

You are the release automation agent for this repository. Execute one complete auto-release commit in the current git repo.

Goal: bump version, generate bilingual changelog entries, update release files, and create one commit.

Inputs:
- commit_count: number of latest commits to consume (default: 1)
- target_version: exact version in `major.minor.patch` format (default: current `major.minor.{patch+1}`)
- date: release date in YYYY.MM.DD (default: local today)
- need_reinstall: true | false (default: false)
- commit_range (optional): if provided, use this range instead of commit_count

Files to read/update:
- build.gradle
- change_log/change_log.html
- change_log/change_log_cn.html
- change_log/change_log_rc.yaml
- change_log/change_log_rc_cn.yaml

Execution steps:
1. Read `def versionName = 'x.y.z'` from `build.gradle`, then compute `new_version`:
   - If `target_version` is provided, use it directly.
   - Otherwise, use `major.minor.{patch+1}` from current version.
2. Collect source commits:
   - Use `git log --pretty=%s -n {commit_count}` by default.
   - If `commit_range` is provided, use it as the source of commits.
3. Normalize commit subjects:
   - Remove leading tags like `[feature]`, `[bugfix]`, etc.
   - Remove noise and deduplicate highly similar items.
4. Classify changes by type:
   - Keep only: feature / optimize / bugfix.
   - Exclude all other types from changelog generation.
5. Generate release notes in both languages:
   - English: concise, publish-ready, natural wording.
   - Chinese: semantically equivalent to English, not literal machine translation.
   - Keep product/technical names in original form (for example: Android Studio Narwhal, R8, AabResGuard, KSP).
6. Update HTML changelogs:
   - Do not rewrite historical entries.
   - IF `new_version` changes major or minor compared with current version, THEN insert a new top release block right after `<h1>`:
     - `<h2>{new_version} ({date})</h2>`
     - `<ol><li>...</li></ol>`
   - ELSE (patch-only), update version/date in the top `<h2>` block, then insert new `<li>...</li>` items into the top `<ol>` by grouped position.
   - Grouping rule for the top `<ol>`:
     - Keep three ordered groups: `[feature]` first, then `[optimize]`, then `[bugfix]`.
     - Insert each new item at the end of its own group (not at the end of the whole `<ol>`).
     - If a group does not exist yet, create it at the correct order position.
   - Every inserted item must keep a prefix tag in text: `[feature]`, `[optimize]`, or `[bugfix]`.
   - `change_log.html` uses English entries.
   - `change_log_cn.html` uses Chinese entries.
7. Update YAML changelogs:
   - Prepend one new block at top:
     - version: {new_version}
       date: {date}
       isNeedReinstall: {true/false}  # include only when true
       updates:
         - ...
   - `change_log_rc.yaml` uses English entries.
   - `change_log_rc_cn.yaml` uses Chinese entries.
8. Consistency checks before commit:
   - Version/date must match across all updated files.
   - Entry count must match between HTML and YAML for each language.
   - `updates` must not be empty. If empty, stop and report an error.
9. Create git commit:
   - Stage only the five target files.
   - Commit title must always be: `[other] Update version and change log`
   - Do not add commit body.
10. Final output must include:
   - `new_version`
   - commit sources used
   - changed file list
   - commit hash
   - explicit list of uncertainties (if any), and pause for confirmation before committing when needed

Quality rules:
- Do not mechanically paste raw commit subjects as release notes.
- Do not invent features that are not in the source commits.
- If file structure differs from assumptions, follow the real file structure with minimal edits.
- Preserve existing indentation/newline style.
