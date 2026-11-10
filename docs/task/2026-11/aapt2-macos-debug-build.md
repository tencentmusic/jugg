# macOS AAPT2 inclink 构建

Purpose: Build maintained inclink source on macOS and add a repeatable smoke.
Decision: Use checksum-locked Android 14 host dependencies and standalone CMake.
Impact: Adds a local Debug build; distributed prebuilts and linking behavior are unchanged.

## 实施

- 读取 Downloads/编译 AAPT2.md；旧 /Volumes/android 未挂载，本机也未找到对应镜像。
- Android 11 依赖失败：ResTable_entry 缺失 full/map_entry；据实际源码改用 android-14.0.0_r75。
- 当前 libc++ 拒绝 vector<const StringPiece>，仅调整 Files.h/Files.cpp 的容器元素类型。未修改字符可变性或增量算法。
- CMake 直接构建维护源码；依赖源码保留在忽略的 build/deps14，下载归档以 SHA-256 校验。protobuf 编译器及运行库固定为 3.20.3。

## 验证证据

- 环境：macOS 26.6.2、Apple Silicon、Xcode 27.2 (27B52)、CMake 4.3、Python 3.14、Intel Homebrew libpng。
- build/link.log：目标 aapt2-inclink 构建成功，Mach-O x86_64，使用 -O0 -g -gdwarf-4。
- scripts/smoke.py 对实际二进制和 SDK 34 android.jar 执行 PASS：compile/link、同一 daemon load + 两次增量、ID 稳定、新增资源、资源值更新、未变化布局不输出、全新进程无缓存明确失败。该测试保护独立 CLI/产物契约，通过 test-value gate；没有为容器类型调整添加实现细节测试，实际编译错误和最终构建是替代验证。
- dsymutil 成功；dwarfdump UUID：136E507E-7045-4A8E-B0B2-21D26A7182F5，二进制与 dSYM 一致。
- 二进制 SHA-256：12472283f1300d1f78639df769033c3275fd8fd49bc8e83ffbb96566387c0ed6。
- LLDB 成功解析 IncLinkCommand::Action 到 IncLink.cpp:786；执行 run 时阻塞，未证实实际命中。随后多个翻译进程进入 U 状态，已向本任务相关构建/调试进程发送终止信号。未改动其他 Jugg daemon。
- 最后一次统一依赖缓存目录后的重建受上述宿主状态影响中断，因此不声称最终构建脚本已完整重跑。正常 Rosetta 环境中仍需运行文档中的两条 build/smoke 命令并验证实际断点。

## 文档与范围

英文 docs/ai/02_compile_aapt2_build.md 是当前构建知识入口，已同步算法文档、00/98/99 导航和模块 README。未改变 Jugg 用户功能与架构，无需 Wiki 更新。历史构建预留说明由现行构建文档取代，跨平台发布仍保留为独立工作。
