# AAPT2 inclink macOS 后续 TODO

Purpose: Complete the reproducible macOS inclink build and remove its AOSP checkout dependency.
Decision: Use the pinned Python + CMake path as the only macOS build path; keep the historical AOSP build as provenance evidence only.
Impact: The macOS build no longer requires `/Volumes/android`, AOSP `out/soong`, or Homebrew. Empty-cache x86_64, arm64 and universal Release builds now use the GitHub Release source pins.

## TODO

- [x] 收集并核对 AOSP 源码现场：完整 frameworks/base、相关 external/system 依赖、AOSP commit/tag、Soong/Ninja 实际编译命令、Xcode/macOS SDK、输出目录和构建产物。
- [x] 用旧构建现场确认原分发二进制的真实依赖版本、编译宏、优化级别、链接方式和 strip/dSYM 流程。（AOSP Soong 使用 `-O2`、`-g`、`-DNDEBUG`、`-UDEBUG`、`-fno-exceptions`、`-fno-rtti` 及 AAPT2 专用宏；Darwin 目标为 macOS 10.14、SDK 11.3、静态 libc++，最终执行 `strip -u -r`，不生成 dSYM。未剥离中间产物保留 UUID/调试信息；当前分发预编译与 stripped AOSP 产物逐字节一致。）
- [x] 将当前 CMake 依赖逐项对齐到 pinned source：protobuf、libpng、zlib、expat 和 Android host libraries；删除 Intel Homebrew、AOSP `out/soong` 与本地静态库 fallback。（当前 CMake 从依赖缓存构建第三方库并生成 protobuf 代码；平台 C++ runtime 由 Apple Clang 提供。）
- [x] 设计并实现 macOS Universal Binary 构建：x86_64 + arm64；分别构建两个 slice，再用 lipo 合并并检查两个 slice 的架构、运行结果和系统 runtime 依赖。
- [x] 从空的依赖缓存和空的 CMake 构建目录完整重跑下载、校验、配置、编译、dSYM 生成和 smoke，确认流程不依赖探索期间遗留文件。（Gitiles 每次生成的压缩包元数据时间戳不同，已为 13 份 Gitiles 归档增加稳定 content SHA-256；空缓存直接联网下载、校验、提取、x86_64 编译、dSYM 和 smoke 全部通过，并验证修改归档内容会被拒绝。）
- [x] 在 arm64 和 x86_64 环境分别运行 smoke；补充真实 APK 的 inclink load/连续增量回归样本。（使用 `android_demo_project/app/build/outputs/apk/debug/app-debug.apk`，两个 slice 均完成 load + 两次连续 increment；Universal Binary 也通过同一 smoke。）
- [x] 完成 LLDB 实际断点命中验证，覆盖 `aapt::IncLinkCommand::Action` 和 daemon 的 load/increment 调用。（x86_64 Debug standalone 已实际命中 `Action`、`LoadFromApk` 和 `IncLinkAdv`，并保留 backtrace 证据。）
- [x] 决定依赖归档的长期保存位置；将当前 `build/deps14` 缓存迁移到稳定制品镜像或仓库外归档，保持提交 ID、归档 SHA-256 和文件清单三重校验。（已归档到 `/Volumes/android/jugg-aapt2-deps14-archive`。）
- [x] 对比 standalone 产物与当前分发 prebuilt，确认架构、动态依赖、符号、行为和版本元数据；新的 prebuilt 替换仍需按发布 build number 单独复跑门禁。
- [x] 为构建入口增加显式的 `debug` / `release` 构建模式参数；调用时必须传入，禁止通过默认值推断模式，并分别固定优化级别、宏、strip 和 dSYM 行为。Debug 使用 `-O0 -g -gdwarf-4` 并生成 dSYM；Release 使用 `-O2 -g -DNDEBUG -UDEBUG`，执行 `strip -u -r` 且不生成 dSYM。Release 使用显式 build number `eng.wormch.20260126.211344`，通过版本元数据、Universal、依赖和行为门禁后已替换分发预编译。
- [x] 删除 AOSP 与 Homebrew fallback：`build_macos.py` 不再接受 `--aosp-root`，CMake 不再读取 AOSP `out/soong`，protobuf 代码由本地 pinned compiler 生成，arm64/Universal 使用与 x86_64 相同的 pinned source graph。

## 当前边界

- 已验证：独立 CMake 入口支持 x86_64、arm64 和 Universal Binary；两个架构分别完成全量编译、dSYM 和 smoke，Universal Binary 与 universal dSYM 均已生成。smoke 覆盖普通 link、同一 daemon 的两次增量、资源 ID 保持、新资源和缺少缓存失败。
- 已固定：AOSP 依赖 URL 使用完整提交 ID；当前构建清单包含 17 份归档，其中 16 份 Gitiles 归档另有稳定 content SHA-256，当前空缓存中的归档均通过校验。
- 已核对：`/Volumes/android` 仅作为迁移期 AOSP provenance；当前构建不再读取它。历史 AOSP commit 和 prebuilt SHA-256 仍保留用于来源追溯。
- 当前边界：macOS 构建使用 Apple Clang toolchain 的 platform C++ runtime；历史 AOSP libc++ archive 未启用，因为其 Android-era runtime fallback 在当前 macOS 上会 abort。真实 APK 两次连续增量和 x86_64 Debug 的 LLDB 断点命中仍是发布替换前的验证要求。
- 构建入口已完成：`build_macos.py` 支持 `--architecture x86_64|arm64|universal`，Universal 模式分别构建两个 slice、执行 `lipo -create` 并生成 universal dSYM；所有架构共享 GitHub Release source pins。
- 已完成依赖对齐：standalone CMake 从 pinned archives 构建 libpng、zlib、expat 和 protobuf，并从本地 `.proto` 生成代码；构建不再读取 AOSP、Homebrew 或本地静态库。
- 已完成隔离重建：使用全新网络空缓存完成 GitHub Release 归档下载、内容校验、提取、x86_64、arm64 和 Universal Release 编译；版本命令和动态依赖检查通过。
- 已完成依赖归档：GitHub Release 保存构建所需 archive bytes；`/Volumes/android/jugg-aapt2-deps14-archive` 仅为迁移期副本。
- 已验证 Release 入口：standalone Universal Release 不依赖 `/Volumes/android`，架构为 `x86_64 arm64`，无 dSYM，动态依赖仅为 platform `libSystem`/`libc++`；发布替换前需使用目标 build number 重跑 smoke 和真实 APK 增量验证。
- 已完成分发替换：迁移前预编译 SHA-256 为 `18c2b4041e526e928948e3e77ad5391e343ff9a61d39a93e33684ae5fa7d7d48`，无 AOSP 构建的新 Universal Release SHA-256 为 `d60b83e8736d77c8990450eff8f700e3ce2ba6d99a7b00929bde0aba758d7413`；替换后再次核对架构、平台 C++ runtime、版本输出和 smoke。
- 约束：本 TODO 不把当前 Debug 产物当作发布产物；不自动接受新的依赖 SHA-256。

## 下一会话输入

AOSP fallback removal and the checked-in macOS prebuilt replacement are complete. The Linux and Windows standalone build path, cross-toolchain entry point and packaged prebuilt replacement are recorded in [aapt2-cross-platform-build-plan.md](aapt2-cross-platform-build-plan.md). Future prebuilt changes still require the target-format, version metadata and appropriate native-host evidence; Linux and Windows runtime smoke remains an environment-dependent follow-up.
