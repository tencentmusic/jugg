# Reject unresolved AAPT2 Git LFS pointers during packaging

Purpose: Fail plugin builds when AAPT2 prebuilts have not been downloaded from Git LFS.
Decision: Run an always-executed pointer-header check before the AAPT2 module processes resources.
Impact: `buildPlugin` and other resource consumers report the affected path and recovery commands instead of packaging a pointer.

## Evidence and implementation

Replacing the Linux prebuilt with its committed LFS pointer let the original `:aapt2-inclink:processResources` succeed. The failure boundary is resource packaging in `aapt2-inclink/build.gradle`; `main` consumes that module and the plugin consumes `main`.

Add `verifyLfsBinaries` as a dependency of `processResources`. Inspect only the LFS signature prefix of platform prebuilts. The task declares no outputs, so an up-to-date resource copy does not suppress verification. Fail with the resource path and instructions to install Git LFS, run `git lfs install`, and run `git lfs pull` at the repository root.

## Verification

Use real Gradle execution and artifact inspection (L0); no new automated test suite is needed for this small build guard.

- Before change: unresolved pointer fixture passed resource processing.
- After change: repeat the pointer fixture and require failure with the affected path and `git lfs pull` guidance; restore original bytes in a finally block.
- With downloaded binaries: build the module JAR and compare all three resource entries against original binary SHA-256 values.
- Inspect the `:idea:buildPlugin --dry-run` graph for the guard before resource processing.
- Run whitespace and worktree checks before commit.

Verification uses the documented JDK 17. The default JDK 21 triggered Gradle 7.3.3 Groovy compilation failure (`Unsupported class file major version 65`) before the guard could run.

All listed checks passed with JDK 17. Full plugin ZIP construction was not repeated; the actual module JAR and the plugin task graph provide the packaging-boundary evidence.

## Documentation

Update the module README. No runtime or architecture change, new code entry point, or user-facing Wiki change is involved; no `docs/ai/` update is needed.
