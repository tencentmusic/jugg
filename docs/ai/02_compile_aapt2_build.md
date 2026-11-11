# Building AAPT2 inclink on macOS

> Last verified: 2026-11-11
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

### Native resource-link regression tests

The optional target reuses GoogleTest/GoogleMock from the pinned protobuf archive, without additional downloads. The test builder uses Android 14's `inputFieldPad0` field. The bundled GoogleMock requires libc++'s removed type-traits compatibility switch when included by C++20 tests; its own libraries compile as C++17.

```bash
cmake -S aapt2-inclink -B aapt2-inclink/build/macos-debug -DAAPT2_BUILD_TESTS=ON -DCMAKE_POLICY_VERSION_MINIMUM=3.5
cmake --build aapt2-inclink/build/macos-debug --target aapt2_link_tests aapt2-inclink -j 8
ctest --test-dir aapt2-inclink/build/macos-debug -R '^aapt2_link_tests$' --output-on-failure
python3 aapt2-inclink/scripts/test_retained_resources.py aapt2-inclink/build/macos-debug/aapt2-inclink "$HOME/Library/Android/sdk/platforms/android-34/android.jar"
```

Use the named CTest selection to avoid unrelated third-party test registrations. The native owner covers strict current inputs and retained baseline values; the Python regression checks deliberately patched APK references, output tables and daemon reload. The artificial APK establishes the incremental reuse contract, not the provenance of a user's malformed resource ID.

## 5. Linux and Windows targets

Linux and Windows use the same pinned source graph and the same CMake entry point. Build on the native target host when possible. A cross build must provide a target compiler toolchain and a host `protoc`; `protoc` runs during the build and must never be the target executable.

The repository entry point is `scripts/build_host.py`. It accepts explicit `--target linux|windows` and `--mode debug|release` arguments and reuses archive validation in `build_macos.py`. Explicit compiler, archiver, ranlib and host-protoc paths remain supported. On POSIX build hosts, `--zig /path/to/zig` generates wrappers in the build directory and requires Zig **0.16.0**. It fixes the full toolchain target, including Zig's own C++ runtime, to `x86_64-linux-gnu.2.14` or `x86_64-windows.vista-gnu`, with `-mcpu=baseline`. Zig is an optional cross compiler, not a replacement for the pinned AOSP source dependencies.

```bash
python3 aapt2-inclink/scripts/build_host.py \
  --target linux --mode release --zig /path/to/zig \
  --protoc /path/to/host/protoc --strip /path/to/llvm-strip \
  --objdump /path/to/llvm-objdump \
  --build-number eng.wormch.20260126.211344
```

Use `--target windows` for the Windows x64 output. Use clean build directories when changing compiler targets; the generated wrappers must not be retargeted underneath an existing CMake cache. Both targets remain x86-64 only, and packaging/runtime selection is unchanged.

The compatibility baseline is the pre-migration binary at commit `39669cb164147e5912b3732b69a2fce3e0911feb`, recorded in `scripts/host_compatibility.json`. Linux must not require GLIBC newer than **2.14** or additional shared libraries. Windows must retain PE system/subsystem 6.0, the existing system/UCRT DLL contract and only old or individually reviewed imports. The Vista target makes libc++ query `GetSystemTimePreciseAsFileTime` dynamically and fall back to `GetSystemTimeAsFileTime`; the precise-time function must not become a mandatory import. A PE version alone does not establish the oldest supported Windows installation, which also needs the existing UCRT.

Unqualified Zig targets are unsafe for compatibility refreshes: Zig 0.16.0 defaults used here selected glibc 2.31 and Windows 10, producing `pow@GLIBC_2.29` and a mandatory precise-time import. Applying a macro only to AAPT2 does not change Zig's separately built libc++. Pin the complete compiler target and inspect the resulting binary.

Windows source selection follows AOSP host configuration: omit Unix-only `cmsg.cpp`, select `errors_windows.cpp` and `utf8.cpp`, use the MinGW thread-safe time API and `-mno-ms-bitfields`, and link the executable statically. Static linkage still leaves system/UCRT DLL imports.

Release builds require target-compatible stripping (`--strip` is mandatory for cross builds), then automatically run `scripts/check_host_candidate.py`. The gate checks architecture and dependencies, GLIBC versions or Windows imports/PE versions, and the requested build number when supplied. It can also inspect candidates independently without executing them:

```bash
python3 aapt2-inclink/scripts/check_host_candidate.py /path/to/aapt2-inclink --target linux
python3 aapt2-inclink/scripts/check_host_candidate.py /path/to/aapt2-inclink.exe --target windows
```

Artifact compatibility inspection and runtime correctness are separate evidence. Run `scripts/smoke.py` on each target host for compile/link, daemon load and consecutive increments. macOS smoke cannot establish Linux or Windows runtime behavior. Pending native-host verification must be recorded explicitly with the candidate hashes in the task record.

## 6. Historical AOSP route

External maintainer notes used `source build/envsetup.sh`, `lunch sdk-trunk_staging-eng`, and `make aapt2 -j8`; the mounted checkout confirms the output at `out/host/darwin-x86/bin/aapt2`. The checked-in AOSP source has no local diff. Historical SDK whitelist/Info.plist workarounds apply to the old AOSP toolchain. It remains a provenance and comparison route only; it is not required by the standalone Linux, Windows or macOS source-archive builds.
