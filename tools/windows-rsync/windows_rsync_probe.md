# Windows rsync standalone transport preparation

This low-level script preserves raw diagnostics. Use [test setup](../../docs/task/2026-10/windows_rsync_test_setup.md) for production integration and acceptance. It requires Python 3.9+, no third-party Python packages, and never switches engines automatically.

With an existing `JUGG_REMOTE_CONFIG_FILE`, run `windows_rsync_acceptance.py`, including for password configurations. It reuses these fixtures, reads connection settings in-process, and excludes accounts, passwords, keys and raw argv/stdout/stderr from reports. Coverage includes small artifact fetch, an unauthorized key, changed host keys and links; `--benchmark-rounds 5` adds five SHA-256-checked rounds of 257 files/about 3 MiB. See [acceptance evidence](../../docs/task/2026-10/windows_rsync_acceptance.md). The raw PoC below stores complete commands and diagnostics: use a dedicated sanitized test configuration and do not publish its reports.

## Run after preparing the environment

1. Copy `windows_rsync_probe.example.json` outside the repository and prepare Cygwin configuration. Executables, keys and known_hosts require absolute paths; target must be a trusted test server in `user@host` form. The private key must not require interactive decryption. Keep credentials out of the repository.
2. Select rsync, ssh and cygpath executables from the same Cygwin `bin`. The script downloads no tools and does not change system SSH/Cygwin configuration.
3. The Linux server needs Python 3, upstream rsync, mktemp and stat. Use a dedicated account and a separate existing unauthorized key for the bad-key case. The known_hosts parent directory must exist; first connection uses accept-new to update that file.
4. Run from repository root:

```powershell
python tools/windows-rsync/windows_rsync_probe.py --config D:/private/cygwin.json
python tools/windows-rsync/windows_rsync_probe.py --config D:/private/cygwin-rsync.json
```

Enable link tests separately. The current Windows account must have native symlink creation permission; missing permission fails without automatic elevation or skipping:

```powershell
python tools/windows-rsync/windows_rsync_probe.py --config D:/private/cygwin.json --links
python tools/windows-rsync/windows_rsync_probe.py --config D:/private/cygwin-rsync.json --links
```

Default output is Git-ignored `verification/windows-rsync/<engine>-<random-id>/`. Each round creates an independent fixture without reusing or deleting existing files. Remote mktemp creates `/tmp/jugg-rsync-probe-*`; reports retain its exact path and evidence. After inspection, the tester cleans up that one directory.

Reports contain each argv, stdout/stderr, actual exit code, elapsed time and SHA-256 of the three executable entry points. They may contain usernames, paths, hosts and output; sanitize manually before sharing. Private-key contents are not recorded. Results are saved after each command, preserving evidence on failure; timeout is failure. SSH preflight and transfer execute separately.

## Prepared assertions

- Independent argv snapshots derive from `SyncFileCommand.getRsyncArguments()` and `JuggPathManager.RSYNC_PUSH_CONFIG_DIR_ARGUMENTS`: required configuration includes, default exclusions and upload deletion. Upload omits `--copy-unsafe-links` and preserves links. Recheck snapshots when production rules change.
- Chinese/space/`&`/parenthesized project paths, content updates, repeated-sync mtimes and unchanged local source hashes.
- Classpath-style include directories, pruning, delete/no-delete branches, download hashes and mtimes. Fixed representative `src/**` filters do not cover every real module's dynamic rules.
- Missing remote rsync path must fail. A configured bad_key must be unauthorized and fail; otherwise record not_run.
- Optional internal relative symlink preservation, external relative/absolute file-link expansion, and Windows Python readability of downloaded native links.
- Both engines use the same fixtures/expectations. Cygwin sets `winsymlinks:nativestrict` only for children and converts local paths with configured cygpath; remote paths/filter patterns are unchanged.

## Remaining acceptance

Passing proves only the selected cases. JVM/Android consumption of downloaded links, directory links/junctions, missing/unreadable links, script executable permissions, host-key changes, cancellation/process descendants, network interruption/partial transfer, silent abnormal exit, long/cross-drive/large real classpaths, JSch, clean-machine dependency closure and full Jugg Flow are not verified by this script. Those original-plan items remain release gates. Python readability is not JVM evidence.

The script removes Git/MSYS2 from PATH, retaining the rsync directory and System32, but this does not establish a clean-machine dependency closure. Entry-executable hashes do not replace DLL/source/license review. Configure actual Unicode/space-containing tool/key/runtime paths separately to verify them.

Python terminates only the direct child on timeout; SSH descendants are not guaranteed to exit. Timeout evidence is not cancellation acceptance. No plugin, Gradle build or Android device is required for this script.
