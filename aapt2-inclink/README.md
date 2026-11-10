# AAPT2 incremental linker

- `source/`: AOSP C++ source for `inclink`; command owners are `cmd/IncLink.cpp` and `cmd/IncLink.h`, with `Android.bp` / `Android.mk` build entry points.
- `src/main/resources/tools/`: prebuilt platform tools packaged by the existing Gradle module.

## Source history and builds

Imported from the local `/Users/wormchen/aosp/aapt2_jugg` checkout of https://github.com/sickworm/aapt2-inclink at `b5b59b2ac` using Git subtree without squashing. All 29 original commits retain their hashes, authors, messages, and parent relationships. Original commit paths are relative to the source repository root; current files live under `aapt2-inclink/source/`. Only tracked content was imported; the original checkout remains intact.

Use `git log b5b59b2ac` or `git show b5b59b2ac` to inspect the original history. See [the standalone build guide](../docs/ai/02_compile_aapt2_build.md) for pinned dependencies, macOS runtime smoke, and Linux/Windows target builds. The upstream AOSP route remains in [source/development.md](source/development.md). This repository's Gradle module continues to package prebuilt resources; it does not compile the C++ source. Source changes require a native build and an explicit update of the appropriate platform binary.

## develop/4.0 integration language review

The incoming changes were reviewed with `tools/check_english_first_diff.py` against the original main base `7786200f7` and candidate `7b01f744d`. The final review uses the develop base `afc8d569c`.

| Incoming item | Classification and final landing | Unresolved facts |
|---|---|---|
| `aapt2-inclink/README.md` | Current guidance rewritten in English on this page; history and build facts checked against Git, `build.gradle`, and `source/development.md`. | None |
| `source/.vscode/launch.json` | Editor comments and disabled configuration label translated into English; active configuration unchanged. Original content remains in imported history. | None |
| `source/ResourceParser_test.cpp` | Meaningful date parsing input and expected result; preserved as a test-input exception. | None |
| `source/StringPool_test.cpp` | Japanese Unicode string-pool input containing Han characters; preserved as a test-input exception. | None |
| `source/text/Utf8Iterator_test.cpp` | Unicode iteration input and expected code points; preserved as a test-input exception. | None |
| `docs/ai_knowledge/00_overview.md` | Source location and prebuilt-tool boundary integrated into existing English `docs/ai/00_overview.md`. | None |
| `docs/ai_knowledge/98_code_map.md` | Native command and build entry points integrated into existing English `docs/ai/98_code_map.md`. | None |
| `docs/ai_knowledge/02_compile_resource.md` | Source location and AOSP/prebuilt boundary integrated into existing English `docs/ai/02_compile_resource.md`. | None |

Verification: the import commit's subtree exactly matches the original Git tree (373 tracked files, including modes). All 29 original commits are reachable. The only subsequent source change translates the four editor-comment lines above. No new behavioral tests were added for this repository migration; no AOSP compilation was performed. User instructions and runtime behavior are unchanged, so no Wiki update is needed.
