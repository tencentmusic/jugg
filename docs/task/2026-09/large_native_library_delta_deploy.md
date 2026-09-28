# 大 SO 差分部署落地记录

## 范围与执行路径

2026-09-28：在已有大 SO 文件部署路径上接入 HDiffPatch 5.1.3。仅处理 `isFileBacked` NativeLib（当前为达到 256 MiB、含边界的 SO），沿用现有 SO hot update 开关、API 26 门禁、重启生效以及发布/回滚流程。

1. `NativeLibraryDelta` 通过通用历史接口复用 `CompileContextDb.getDeployedData()`，延迟读取一次上次成功部署保存的产物列表，筛选 NativeLib 并按 APK 作用域提供 SO 快照。helper 经构造依赖传递到 `NativeSandboxWriter`，通用部署 data 和 native request 不保存基线；不读取可被下一次编译覆盖的暂存产物。
2. 本地基线缺失时直接完整传输，并打印一行 info：`No large SO delta baseline for ...; transferring the full file may take longer.` 不从 APK 解压或从设备拉取基线。
3. 检查设备 overlay 基线和 Android patcher 的实际 sandbox 执行权限。本地 `hdiffz` 流式生成补丁，计算旧文件与新文件 SHA-256；设备旧文件 SHA-256 一致才推送补丁。
4. 独立 Android `hpatchz` 将结果写入 pending 文件，验证新文件 SHA-256 后复用已有发布逻辑。过程中不原地修改旧 SO。
5. 工具不可用、基线不一致、补丁不够小、生成失败及可恢复的 patch/校验失败，局部回退一次完整传输。取消、传输异常、设备空间或文件 I/O 错误直接失败，避免重复推送大文件。

完整传输原因及耗时可能较长的等待提示使用 info；阶段进度及生成、本地校验、设备基线校验、传输、设备合成与校验、完整拷贝和发布耗时均记录为 debug。大 SO 部署成功后以一行 info 输出 stage、publish、缓存记录和 discard 的总耗时，失败不打印成功总耗时，异常警告保持 warn。端到端耗时还包含已有编译、部署编排、成功快照保存及重启成本，不能只用补丁传输时间代替。

日志级别调整属于普通日志 L0，不新增绑定文案的测试；复用 writer、部署 Flow 与 overlay owner 的 49 项定向回归，全部通过（`/tmp/jugg-native-log-tests.log`）。Wiki 校验与生产构建通过。

## 工具来源与可复现构建

桌面工具来自固定版本官方发行包，支持 macOS Intel/Apple Silicon、Linux x64/arm64、Windows x64。Android patcher 从未修改的官方源码交叉编译，覆盖 arm64-v8a、armeabi-v7a、x86_64、x86，API 26，PIE 与 16 KiB LOAD 对齐。Android 版本仅启用 zlib，动态依赖设备 `libz.so`、`libdl.so`、`libc.so`。

```bash
python3 tools/build_native_delta.py --ndk "$ANDROID_HOME/ndk/28.2.13676358"
```

源码、官方 ZIP 的 SHA-256 固定在脚本中。插件内置工具，无运行时下载。`main/src/main/resources/tools/hdiffpatch/5.1.3/manifest.json` 保存版本、构建条件、来源和产物摘要；许可证、第三方清单、NOTICE 与 SPDX SBOM 同步维护。

## 依据

- 知识库：[00_overview](../../ai_knowledge/00_overview.md)、[99_index](../../ai_knowledge/99_index.md)、[98_code_map](../../ai_knowledge/98_code_map.md)、[03_deploy_core](../../ai_knowledge/03_deploy_core.md)、[03_deploy_complete](../../ai_knowledge/03_deploy_complete.md)、[09_plugin_runtime_debug](../../ai_knowledge/09_plugin_runtime_debug.md)、[06_testing](../../ai_knowledge/06_testing.md)。
- 既有方案：[超大 Native Library 增量部署](large_native_library_deploy_plan.md)。
- Wiki 写作：[wiki-writer](../../../.agents/skills/wiki-writer/SKILL.md)、[10_wiki_authoring](../../ai_knowledge/10_wiki_authoring.md)、[10_wiki_architecture](../../ai_knowledge/10_wiki_architecture.md)；SO 能力页、Assets/Native 原理页及其最近目录索引的中英文版本。
- 行为 owner：`NativeLibraryDelta`、`NativeSandboxWriter`、`CompileContextDb`、`DeployHistoryManager`、`JuggDeployerHelper.routeNativeLibraries` 与 `JuggDeployer`。
- 本地 demo `shared-native-incremental-demo` 的已有日志显示大 SO 部署约 117.3 秒；按阶段时间推断约 113 秒用于推送，约 4 秒用于 sandbox 拷贝。该拆分是日志推断，不是本次新设备测速。

## 验证证据

优化前先运行既有 writer 回归确认原路径；新增真实 diff/patch 行为测试在实现类加入前编译失败，随后通过。测试保护文件字节正确性、APK 基线隔离、传输选择、故障边界与已有发布回滚，不为简单字段透传单独增加测试。

| 验证 owner | 范围 | 结果 |
| --- | --- | --- |
| `NativeLibraryDeltaTest` | 实际桌面二进制生成/还原并校验字节；取消生成 | 2 通过 |
| `NativeSandboxWriterTest` | 原完整传输与回滚；仅发补丁；基线不匹配；结果损坏；空间不足；生成失败 | 9 通过 |
| `DeployHistoryManagerTest.nativeLibraryIsRecoveredForItsApkAfterProjectRestart` | 重启恢复、APK 独立基线、源产物覆盖不污染快照、基线丢失 | 1 通过 |
| `JuggDeployerHelperDeployFlowTest` | 既有部署 Flow 回归 | 35 通过 |
| `OverlayUpdateBuilderTest` | 既有 overlay 构造回归 | 3 通过 |

定向执行合计 50 项，未运行未过滤的全量测试。日志保存在本机 `/tmp/jugg-native-flow-tests.log`；最终 writer/tool 回归与插件构建见 `/tmp/jugg-native-final-build.log`。Wiki 校验脚本及 VitePress 生产构建通过；插件打包及第三方合规校验通过；核对 ZIP 内 14 个工具/许可证文件的 SHA-256 与 manifest 一致（另含 manifest 文件本身）。Android ELF 的 ABI、PIE、动态依赖与 16 KiB 对齐经 NDK readelf 检查。

### 超过 2 GiB 的真实文件实验

Apple M3 Pro/macOS 上读取 demo 两次编译得到的 `libnative_primary.so`，每个 3,221,426,168 bytes：旧产物 UUID `47fa73aa-27ed-4447-b0b2-89bc0fe4981f`，新产物 UUID `14fa5e79-1a91-4e4d-962b-a35c4537e940`。

- 补丁 8,089 bytes；生成 5.998 秒；桌面还原 3.194 秒。
- 还原后的完整文件 SHA-256 与新产物一致。
- 数据见本机 `/tmp/jugg-large-delta-result.json`。这些 demo SO 包含大量零值区域，该补丁比例不能外推到生产 ELF，桌面还原耗时也不能视作 Android 耗时。

## 用户设备验收

本次未执行设备部署，以下由用户安装本次插件包后实测：

1. 缺少成功快照时，确认输出窗口出现基线缺失 info 提示，完整传输与原部署成功。
2. 再改少量 C++ 内容，确认 debug 日志中出现 `Large SO delta transfer`，记录生成、校验、传输、`patch and verification` 及总体部署耗时。
3. 若出现 `Native patcher cannot execute in ...`，记录 sandbox 模式和命令输出；本次应回退完整传输。权限是否允许执行必须由真实设备结果判断。
4. 重启后的功能结果和完整传输一致，失败时旧 overlay 可恢复。系统 app 仍使用现有 sandbox 身份解析，不另行改变其权限策略。

同步文档：部署知识库、代码地图、SO 更新能力页与 Assets/Native 原理页的中英文镜像。

## 基线接口与传递方式收敛

2026-09-28 review 后，将原有 native 专用历史 getter 替换为 `IDeployHistoryManager.getDeployedData(): List<CompileOutput>?`，直接委托已有 DB 查询。删除 DB 新增的专用 getter、`JuggDeployData.nativeLibraryBaselines` 和 request 的基线 map。native helper 持有历史依赖，并在大 SO 分支首次使用时构建本轮索引；普通部署不扫描历史目录。

external compile 的 CRC 比较继续用于判断 SO 是否变化。其内存 `deployedFiles` 可能引用随后被覆盖的 staging 输出，部署阶段生成差分仍需使用成功部署后的持久化快照。现有快照存储和提交时序保持不变。

本轮按 refactor 验证：沿用真实二进制差分、writer 回退、历史恢复与部署 Flow owner；在真实差分测试内验证通用列表中的 NativeLib 类型筛选、同名 SO 的 APK 隔离和文件丢失处理，不为字段透传另增测试。定向回归另包含 `JuggDeployerInstallTest`，验证构造依赖调整后原安装流程，共 60 项通过。日志：`/tmp/jugg-native-history-refactor-tests.log`。本次仅调整内部依赖与查询接口，核对现有中英文 Wiki 的用户行为描述无需修改。

## RUN_AS 使用 App 私有目录中的 patcher

用户现场与 demo `build/jugg/log/compile_latest.log` 均确认：2026-09-28 22:05:57.590，RUN_AS 执行 `/data/local/tmp/jugg/hdiffpatch/5.1.3/arm64-v8a/hpatchz -v` 返回 `Permission denied`。随后 3,221,426,168 bytes 的完整传输耗时 89,218 ms。该日志直接证明 shell 缓存路径在这次 sandbox 身份下不能执行；日志不足以独立区分 DAC、SELinux 或其他设备策略，也不能证明私有目录一定可执行。

`NativeSandboxWriter.prepareDeviceTool()` 保留版本/ABI 隔离的 shell 缓存，再由 sandbox 执行 `mkdir -> cp -> chmod 700`，在 App 内创建 `code_cache/.jugg_native_stage/<sessionId>/hpatchz`。后续版本探测和 patch 命令均使用这个私有副本；普通清理/失败 discard 删除同一个 session，因此不新增缓存清理机制。复制失败按 COPY 错误结束，不因设备空间或文件 I/O 错误追加全量推送；私有副本仍不能执行时沿用完整传输回退。

测试先模拟 RUN_AS 拒绝执行 shell 路径，断言应该只发送补丁，旧实现实际发送完整源文件而失败（`/tmp/jugg-native-private-patcher-red.log`）。修复后沿用 writer、部署 Flow 与 overlay owner，并覆盖私有副本执行被拒、复制失败两种边界，49 项定向回归通过；回归日志为 `/tmp/jugg-native-private-patcher-tests.log`。本机 shell 使用真实桌面 hpatchz 验证“缓存不可执行、复制并 chmod 后可执行、session 清理移除副本”（`/tmp/jugg-native-private-patcher-shell.log`），仅证明 shell 流程，不代表 Android SELinux 验证。

本次未操作用户设备，实际 Android 私有副本执行权限和差分耗时仍由用户复测。已核对中英文 Wiki：既有“工具不可用时完整传输”及“空间不足结束本轮”描述保持一致，无需增加内部路径细节。

## File-backed 门槛调整为 256 MiB

2026-09-28：按用户要求，NativeLib 大小达到 256 MiB（268,435,456 bytes，含边界）即走 file-backed。大小判断统一收归 `DeployItem.shouldUseFileBackedNativeLib(size)`，产物转换、file-backed 工厂校验和 APK 基线 entry 保护共用；后续部署仍依据 `isFileBacked` 分流。非 NativeLib 的 `ByteArray` 上限与经典 ZIP 单 entry 上限保持原义。

验证 owner 为 `DeployFilePathExtTest`、`ApkFileModifierStreamTest`、`NativeSandboxWriterTest`、`JuggDeployerHelperDeployFlowTest` 和 `OverlayUpdateBuilderTest`。先将工厂回归改为 256 MiB，旧实现因仍要求达到 1 GiB 失败，证据见 `/tmp/jugg-native-256m-red.log`；随后覆盖恰好 256 MiB、上下一字节、真实稀疏 SO 转换、缺失 APK entry 时保留原包、小 SO 和超大非 SO 原有行为。测试只断言产物和失败契约，不为阈值常量另建测试。最终 62 项定向回归通过，日志见 `/tmp/jugg-native-256m-tests.log`；Wiki 校验、生产构建及中英文产物阈值核对通过。
