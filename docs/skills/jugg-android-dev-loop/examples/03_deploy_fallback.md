# Recipe: Deploy Fallback

## When to use

- `compile_and_deploy` fails in deploy stage and error indicates install/runtime state issue.
- Typical signature: `INSTALL_FAILED_UPDATE_INCOMPATIBLE`, signature mismatch.

## Minimal flow

1. Run `compile_and_deploy(projectDir)` and parse failure detail.
2. Match `install_signature_mismatch` in `references/error_patterns.md`.
3. If pattern confidence is high and auto-apply is allowed, run `clean_reinstall_apk(projectDir)`.
4. Re-run `restart_app(projectDir)`.
5. Run `screenshot(projectDir)` for recovery proof.

## Stop/Fallback

- If mismatch pattern is unclear: ask user before destructive reinstall path.
- If `clean_reinstall_apk` fails: stop and report diagnosis.

## Evidence

- Required: fallback action result and final screenshot.
- Include downgrade chain in summary.

## Summary template

- Verdict: `PASS|FAIL`
- Build path chain: `compile_and_deploy -> clean_reinstall_apk`
- Pattern: `install_signature_mismatch`, confidence, auto-applied or not
- Artifacts: absolute paths
