# Issue 77：保留未触及的基线资源值

Purpose: Allow unrelated incremental resource updates when compiled baseline references cannot resolve in the current context.
Decision: Link current input values only; retain existing styleable processing and strict ordinary linking.
Impact: Changes native inclink source and regression coverage; packaged platform prebuilts are not replaced.

## 决策与实现

资源选择依据引用所在的资源值，不能依据引用目标 ID 是否属于本轮。TableMerger 在处理覆盖碰撞前记录目标 ResourceConfigValue，包括返回 keep-original 的 style 原地合并；configuration、product 和目标资源表各自隔离。每轮创建新的 merger，重新确定输入集合。ReferenceLinker 只访问本轮输入涉及的值，styleable 保持原有处理，普通 link 继续校验完整资源表。

未触及的基线普通引用、style parent、style items 和 array 等复合值保留已编译结果。本轮重新声明的值，即使内容相同，仍执行正常链接。values XML 中一起编译的声明都属于本轮输入。本次不跟踪字段来源、不修正 ID、不改变部署流程。

独立 CMake 构建增加可选 aapt2_link_tests，复用锁定 protobuf 源码包内的 GoogleTest；测试构造器适配 Android 14 的 inputFieldPad0。共享 native 静态库供命令行程序和测试复用，避免重复维护源文件列表。

## 验证结果

- 生产逻辑修改前，新增行为断言失败：旧普通引用 0x01049999 和旧 parent 0x01830480 均报 not found。
- 修复后 46 个 native 单元测试全部通过，其中新增 15 个，覆盖旧引用、当前输入、style 原地合并、parent 修复、array、configuration/product、资源表隔离、连续调用、普通 link 和 styleable。
- 使用原有 darwin 预编译工具运行同一组 APK 回归：load 测试通过，两个涉及后续增量的测试失败，错误为旧引用 not found / failed linking references。
- 使用本次 Debug 构建运行 APK 回归：3 项全部通过。验证 load、无关字符串和布局的连续增量、daemon 重建后重载、输出原始 ID 保留，以及成功后重新声明同一坏 parent 仍失败。
- 原有 scripts/smoke.py 通过，验证普通资源变更、稳定 ID、当前文件输出和缺少缓存的失败路径。
- 中英文资源编译 Wiki 已同步；镜像/链接校验、生产构建、生成 HTML 内容检查及 git diff --check 通过。

## 交付边界

单元测试使用 AAPT2_BUILD_TESTS=ON；执行 ctest 时选择 aapt2_link_tests，避免第三方测试注册。命令及验证入口见 docs/ai/02_compile_aapt2_build.md。坏 APK 是人工构造的兼容输入，用于验证基线复用契约。

本次已构建并验证本地 macOS x86_64 Debug 可执行文件，未替换插件打包的各平台预编译工具；发布工具后插件用户才会获得该行为。
