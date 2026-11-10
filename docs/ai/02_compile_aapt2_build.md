# Building AAPT2 inclink on macOS

> Last verified: 2026-11-10
> Consistency rule: If documentation conflicts with code, code takes precedence.

## 1. Ownership and prerequisites

`aapt2-inclink/CMakeLists.txt` compiles maintained `source/` directly without a complete AOSP checkout. `scripts/build_macos.py` downloads SHA-256-locked archives from `scripts/dependencies.json`, builds the executable and generates its dSYM. Paths below are relative to `aapt2-inclink/`.

Dependencies must match **android-14.0.0_r75**: current BinaryResourceParser/TableFlattener use `ResTable_entry.full`, which Android 11 lacks. Import history does not establish the current dependency ABI. Each AOSP archive URL pins the full commit ID resolved from this release tag, so later tag movement does not change the selected source. The build uses the pinned protobuf 3.20.3 compiler and runtime together, and generates the AAPT2 protobuf sources from the checked-in `.proto` files. The compression libraries are built from pinned source archives in the same CMake graph.

Verified environment: macOS 26.6.2 on Apple Silicon, Xcode 27.2 (27B52), CMake 4.3 and Python 3.14. The standalone entry point supports x86_64, arm64 and a reproducible `universal` build without an AOSP checkout. No SDK copying or Xcode Info.plist editing is needed.

The historical AOSP checkout and prebuilt remain provenance references only. The current build downloads all source archives from the immutable GitHub Release URLs in `scripts/dependencies.json`; it does not read `/Volumes/android`, AOSP `out/soong`, Homebrew, or a local system library for libpng, zlib, expat, protobuf, or generated protobuf sources. The executable uses the platform C++ runtime supplied by the selected target toolchain.

Before replacing the prebuilt with a Release output, run `python3 aapt2-inclink/scripts/check_release_candidate.py <binary>`. The gate requires `x86_64 arm64`, no dSYM beside the binary, and only the expected macOS system runtimes (`libSystem` and the platform `libc++`); it prints candidate and reference SHA-256 values for the final review.

## 2. Commands and outputs

From the repository root:

```bash
python3 aapt2-inclink/scripts/build_macos.py --mode debug --jobs 8
python3 aapt2-inclink/scripts/build_macos.py --mode release --jobs 8
python3 aapt2-inclink/scripts/smoke.py aapt2-inclink/build/macos-debug/aapt2-inclink "$HOME/Library/Android/sdk/platforms/android-34/android.jar"
```

Pass `--architecture arm64` for a native arm64 slice, or `--architecture universal` to build both slices and merge them with `lipo`. Both slices use the same pinned source inputs and CMake graph.

The build script accepts `--deps-root` and `--build-root` for isolated rebuilds. Gitiles regenerates its tar metadata on each request, so its compressed archive SHA-256 is not stable even when every file is unchanged. `scripts/dependencies.json` therefore keeps the archived byte-level SHA-256 and a stable content SHA-256 for each Gitiles archive; the downloader accepts a changed archive hash only when the canonical entry/content hash matches, and rejects changed file content. A fresh network download into an empty cache completes extraction, CMake configuration and full compilation without `/Volumes/android`.

There is no AOSP or Homebrew fallback in the build entry point. Removing or renaming an archive, changing its bytes, or pointing `--deps-root` at an incomplete cache fails during checksum or dependency validation.

Outputs are mode-specific: `--mode debug` writes `build/macos-debug/aapt2-inclink` with `-O0 -g -gdwarf-4` and a matching dSYM; `--mode release` writes `build/macos-release/aapt2-inclink` with `-O2 -g -DNDEBUG -UDEBUG`, then runs `strip -u -r` and does not generate a dSYM. `--mode` is required and has no default, so a build cannot silently infer release metadata from a stale directory. A universal build applies the same mode contract after lipo. Archives and extracted dependencies live in ignored `build/deps14/`. `.inclink-ready` marks completed extraction. `/Volumes/android/jugg-aapt2-deps14-archive` is a migration-era archive copy; normal builds use the GitHub Release assets. A changed archive is accepted only through its locked stable content hash; changed file content still fails explicitly.

The generated protobuf files are build-directory outputs, and proto import prefixes are rewritten only in those copies. `IncLink.cpp` includes `Link.cpp`; compiling both separately duplicates definitions. The CMake tool list follows Android.bp. Third-party archives retain upstream license files. Gradle continues packaging `src/main/resources/tools/` and does not invoke this build or replace prebuilts.

## 3. Compatibility decisions

Android 14 host headers require C++20, including `std::span`. Its fmt headers need `FMT_CONSTEVAL=` with current Apple Clang; formatting checks run at runtime. `FMT_HEADER_ONLY` avoids another binary dependency. Host libraries retain the AOSP NDEBUG contract; inclink keeps Debug assertions enabled.

Current libc++ rejects const vector elements. `util/Files.h` and Files.cpp use `vector<StringPiece>` instead of `vector<const StringPiece>`. Views still reference immutable characters. This is the only maintained native source adjustment.

## 4. Smoke and debugger evidence

`scripts/smoke.py` owns CLI integration smoke. It generates temporary Manifest, values and layout, invokes real compile/link, then loads the APK and processes two increments in one daemon. Assertions cover changed values, preserved IDs, added resources, Manifest/table output, omitted unchanged layout, three successful daemon completions and missing-cache failure in a fresh process. Subprocesses have timeouts. No device, Gradle baseline or existing APK is needed.

The x86_64 and arm64 standalone slices, and the merged universal executable, pass smoke against SDK 34. Each slice also loaded the repository's `android_demo_project/app/build/outputs/apk/debug/app-debug.apk` and processed two consecutive increments in one daemon. dsymutil generated matching universal executable/dSYM UUIDs. LLDB resolved and actually hit `aapt::IncLinkCommand::Action` at `source/cmd/IncLink.cpp:786` in the x86_64 Debug standalone binary. A daemon protocol run also hit `aapt::IncLinker::LoadFromApk` at line 355 and `aapt::IncLinker::IncLinkAdv` at line 576, with backtraces through `IncLinkCommand::Action` and `DaemonCommand::Action`.

```bash
lldb --batch -o "breakpoint set -n aapt::IncLinkCommand::Action" aapt2-inclink/build/macos-debug/aapt2-inclink
dwarfdump --uuid aapt2-inclink/build/macos-debug/aapt2-inclink aapt2-inclink/build/macos-debug/aapt2-inclink.dSYM
```

For interactive debugging, set the same breakpoint, then run `inclink -o /tmp/inclink-unused.apk --manifest /tmp/inclink-unused.xml`. Continuing intentionally reports missing cache. To inspect retained state, launch daemon with LLDB `settings set target.input-path /absolute/commands.txt`; framing is in [02_compile_aapt2_inclink.md](02_compile_aapt2_inclink.md). Algorithm changes still need relevant native regressions or device evidence.

## 5. Linux and Windows targets

Linux and Windows use the same pinned source graph and the same CMake entry point. Build on the native target host when possible. A cross build must provide a target compiler toolchain and a host `protoc`; `protoc` runs during the build and must never be the target executable.

The repository entry point is `scripts/build_host.py`. It accepts explicit `--target linux|windows` and `--mode debug|release` arguments, reuses the archive validation in `build_macos.py`, and forwards compiler, archiver, ranlib and host-protoc paths to CMake. A Linux cross build with Zig is representative:

```bash
python3 aapt2-inclink/scripts/build_host.py \
  --target linux --mode release \
  --cc /path/to/zig-linux-cc --cxx /path/to/zig-linux-cxx \
  --ar /path/to/zig-linux-ar --ranlib /path/to/zig-linux-ranlib \
  --protoc /path/to/host/protoc --strip /path/to/target-strip
```

Windows follows the same form and produces `aapt2-inclink.exe`. The Windows graph follows AOSP host selection: it omits Unix-only `cmsg.cpp`, selects `errors_windows.cpp` and `utf8.cpp`, uses the MinGW thread-safe time API and `-mno-ms-bitfields`, and links the executable statically. The platform compiler and system runtime are toolchain inputs; they are not AOSP dependencies and are not stored in the repository.

Release builds strip debug sections with the target-compatible `--strip` command; cross builds must provide it explicitly. The verified x86-64 Linux and Windows outputs are the packaged prebuilts under `src/main/resources/tools/`. Cross-build verification is limited to successful compilation, target-format inspection (`file` reports ELF or PE32+), and archive/protobuf integrity. Runtime smoke remains a native-host check. The macOS universal build and smoke provide the runtime evidence for this change; Linux and Windows runtime smoke requires their respective hosts.

## 6. Historical AOSP route

External maintainer notes used `source build/envsetup.sh`, `lunch sdk-trunk_staging-eng`, and `make aapt2 -j8`; the mounted checkout confirms the output at `out/host/darwin-x86/bin/aapt2`. The checked-in AOSP source has no local diff. Historical SDK whitelist/Info.plist workarounds apply to the old AOSP toolchain. It remains a provenance and comparison route only; it is not required by the standalone Linux, Windows or macOS source-archive builds.
