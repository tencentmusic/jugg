# AAPT2 prebuilt Git LFS migration

Purpose: Store packaged AAPT2 executables in Git LFS.
Decision: Convert the current three platform prebuilts in a new commit, preserving history and resource paths.
Impact: Contributors and plugin build CI must retrieve LFS content before packaging.

## Scope

- Track `aapt2-inclink/src/main/resources/tools/**/aapt2-inclink-*` with LFS and renormalize the existing macOS, Linux and Windows files.
- Enable LFS checkout in PR, Dev, Canary and release build jobs. Metadata-only and Wiki jobs do not package these resources.
- Document Git LFS setup in both contribution guides and the module README.
- Preserve existing history; historical binary blobs remain. No history rewrite, remote push or remote upload is part of this task.

## Evidence and verification

Before conversion, all three executables were ordinary Git blobs and no LFS attributes applied. Their combined size was 18,135,120 bytes. The initial worktree was clean.

This is storage/build maintenance with no runtime change. No new automated test is warranted: verification uses actual Git pointers, LFS restoration and packaged artifact contents (L0).

- Each staged blob is a canonical LFS pointer with the original SHA-256 and byte count.
- Worktree bytes and file modes match the pre-conversion snapshot for all three platforms.
- Local `git lfs smudge` restores each pointer byte-for-byte.
- `./gradlew :aapt2-inclink:jar --no-daemon` passed; all three JAR entries match the original SHA-256 values.
- `git diff --check` and staged diff checks passed.
- Remote LFS upload/download and GitHub Actions execution remain unverified until push/CI.

No runtime or architectural facts changed, so `docs/ai/` and user Wiki pages require no synchronization. Contributor setup and module storage guidance were updated together.
