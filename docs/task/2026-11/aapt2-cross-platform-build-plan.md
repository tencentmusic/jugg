# AAPT2 inclink cross-platform build plan

Purpose: Make the Linux and Windows AAPT2 inclink targets reproducible from the pinned source archives without an AOSP checkout.
Decision: Reuse the standalone CMake graph for every host target, require an explicit target toolchain for cross builds, and use a host-built pinned protobuf compiler for generated sources.
Impact: Linux and Windows builds no longer depend on `/Volumes/android`; macOS remains the runtime-verified target, while Linux and Windows receive compile and target-format evidence until native runtime hosts are available.

## Scope

The source dependencies are the 17 entries in `aapt2-inclink/scripts/dependencies.json`. The target compiler, C++ runtime, platform SDK and linker are external toolchain inputs. No AOSP checkout, `out/soong`, Homebrew library, or target `protoc` is part of the build contract.

## Work record

- [x] Make zlib and generated libpng headers visible in cross-platform CMake builds.
- [x] Make protobuf generation accept an explicit host `protoc` during cross compilation.
- [x] Align Windows source selection and ABI flags with AOSP host configuration.
- [x] Add `aapt2-inclink/scripts/build_host.py` for explicit Linux and Windows target builds.
- [x] Build an x86-64 Linux ELF from the pinned cache with Zig wrappers.
- [x] Build an x86-64 Windows PE32+ executable from the pinned cache with Zig wrappers.
- [x] Strip release debug sections with a target-compatible tool and embed the release build number.
- [x] Replace the Linux and Windows packaged prebuilts with the verified target-format artifacts.
- [x] Re-run the macOS build/smoke after the cross-platform CMake changes.
- [x] Record native Linux/Windows runtime smoke as an environment-dependent follow-up; macOS runtime verification is sufficient for this task.

## Verification boundary

The repository entry point was exercised with an isolated pinned dependency cache. The Linux artifact is `/tmp/aapt2-linux-script/aapt2-inclink` and `file` reports an x86-64 ELF executable; its SHA-256 is `9311690933bf38f6ceb9f4523ab77ba6f1dd4bc702fa0556abfea1561917eb53`. The Windows artifact is `/tmp/aapt2-windows-script/aapt2-inclink.exe` and `file` reports a PE32+ x86-64 executable; its SHA-256 is `eec752882204637429a60f01d5b2bb889c8d7ecfe0d78652073cc7d9311bbc14`. Both contain build number `eng.wormch.20260126.211344` and were stripped with the target-compatible LLVM object copier. These artifacts now replace the Linux and Windows packaged prebuilts. Neither target artifact is executed on macOS. The macOS arm64 release build and smoke pass after the same CMake changes.

## Completion rule

The build-path work is complete: the repository entry point reproduces both target formats from the pinned dependency cache and the macOS runtime checks remain green. Native Linux and Windows runtime checks are optional follow-up evidence and do not reintroduce AOSP or fallback dependencies.

## Optional follow-up evidence

Run the existing `scripts/smoke.py` on a Linux host and a Windows host when those environments are available. This task intentionally does not claim those runtime checks.
