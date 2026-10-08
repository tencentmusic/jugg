# 超大 SO 差分传输可行性调研

> 日期：2026-09-28
>
> 范围：已开启 SO hot update、API ≥ 26、超过 `Int.MAX_VALUE` 的 file-backed NativeLib
>
> 结论：同尺寸且变化局部的 SO 可以显著缩短传输；先评估 ADB 压缩，再以固定分块差分做受限试点。尚未用真实业务 SO 完成端到端回归。

## 1. 现状与目标

当前 `NativeSandboxWriter.stage()` 将完整 SO 通过 `IDeviceAdb.push()` 发送到 `/data/local/tmp/jugg/nativeLib/<session>/`，再复制到应用的 `code_cache/.jugg_native_stage`；通用 overlay swap 成功后才发布到 `.overlay/<apk>/lib/<abi>/`，并在成功后提交 deployment cache。`IdeaDeviceAdb.push()` 最终调用 ddmlib 的 `device.pushFile()`。大型 SO 不进入 `ByteArray` 或通用 `ByteString`。

演示工程 `shared-native-incremental-demo` 的 SO 为 **3,221,426,168 B（3.0 GiB）**。`build/jugg/log/compile_latest.log` 的 2026-09-28 20:26:01.604～20:27:54.707 区间对应完整文件推送，约 **113 秒**；随后 sandbox 复制与大小校验约 **3.55 秒**，整次设备部署日志为 **117.31 秒**。该计时来自一轮真实部署，但日志未单独给 `pushFile()` 打点，113 秒是相邻阶段时间戳的近似差值。

## 2. 已完成的可行性实验

| 实验 | 结果 |
|---|---|
| 三组相邻构建产物逐字节比较，每个 SO 均为 3,221,426,168 B | 每组仅首尾两个 1 MiB 区块不同，实际差异分别为 22、23、23 B。样本为固定随机载荷的演示 SO，不能推断业务 SO 同样稳定。 |
| 其中一组按 64 KiB 固定分块 | 仅第 0、49153 块变化；原始补丁 **131,072 B**，约为整文件的 **0.0041%**。 |
| 本机用旧文件复制、按偏移写入两块、校验 SHA-256 | 重建的 3.0 GiB 文件与新文件 SHA-256 完全一致；扫描差异约 0.8 秒，本机复制约 1.0 秒，重建文件哈希约 7.3 秒。该本机复制速度不能代替设备测量。 |
| 当前设备读取已部署 SO 的 SHA-256 | 约 **3.08 秒**，与本机部署快照的 SHA-256 一致，证明这轮具备可验证的基线。 |
| ADB CLI `push -z zstd` 完整推送到设备临时目录 | **42.34 秒**；设备端 SHA-256 与源文件一致，临时文件已删除。另一次 `push -n -z zstd` 无落盘传输为 28.5 秒。该 CLI 路径与现有 ddmlib 路径不同，尚不能直接把差值全部归因于压缩。 |
| 设备端隔离差分重建 | 将当时的 `.overlay` SO 复制到应用临时目录，推送反向 128 KiB 补丁，用设备 `dd` 写入第 0、49153 块；设备端复制、写入、完整 SHA-256 共 **6.98 秒**，哈希与目标旧版本一致。PoC 未写生效中的 `.overlay`；ADB 和应用临时文件均已清理。 |

设备为 Xiaomi 2509FPN0BC、API 36，ADB 35.0.2。设备 Toybox `dd` 支持 `seek`、`skip`、`conv=notrunc`，本次 PoC 已验证超过 2 GiB 的写入偏移。其它 Android 版本与设备尚未验证。

演示工程在测量期间又发生一轮 Jugg 部署（日志 20:35～20:37），之后生效中的 SO 哈希也随之改变。传输和设备计算耗时可能受到并发任务影响；上述计时用于判断量级，正式性能结论需独占设备重测。

## 3. 建议的最小落地路径

### 3.1 先验证压缩传输

在相同设备、同一 SO 和同一 staging 位置对比现有 `device.pushFile()` 与 ADB CLI `push -z zstd` 的传输、CPU、内存及失败表现。压缩方式不改变 SO 内容、overlay 格式或设备发布逻辑，本次实推送已从约 113 秒区间降至 42 秒。若工具链/设备不支持该压缩模式，沿用当前完整 push。

### 3.2 差分只覆盖有可靠基线的场景

首版只考虑**同尺寸**、设备已有目标 APK/ABI 的旧 `.overlay` SO、且本机保存了对应旧 SO 快照的 file-backed NativeLib：

1. 按 64 KiB 固定分块比较本机旧、新 SO，合并连续变化块，生成单个补丁文件和块索引；保留新文件 SHA-256。若旧文件缺失、尺寸变化或变化块过多，走现有完整 push。切换门槛以真实样本和传输计时确定。
2. 在目标设备确认旧 overlay 文件大小、SHA-256 与本机旧快照相同。各设备可能处于不同版本，不能仅凭项目级 `deployed/native` 快照或 overlay ID 假设每台设备的 SO 字节相同。基线不匹配时完整 push。
3. 一次性 push 补丁到现有 `/data/local/tmp/jugg/` 暂存区。在应用 sandbox 的非生效 pending 目录复制旧 SO，用设备 `dd` 按块索引覆盖，校验新文件大小和 SHA-256。
4. 复用 `NativeSandboxWriter` 的 publish、backup、rollback、cleanup 和 deployment cache 提交时机。补丁生成、基线校验、pending 重建失败时，清理临时文件并尝试原有完整 push；发布后失败继续使用现有回滚与 overlay ID recover 契约。SO 仍要求完整重启 App。

64 KiB 固定分块没有处理内容整体移位的能力。真实链接产生广泛偏移、section 重排或较大尺寸变化时，变化块可能接近整文件；此时完整 push 是正确结果。先用真实用户连续构建样本验证命中率，再考虑滚动块匹配或 VCDIFF，不为推测的布局变化增加首版依赖。

## 4. 其它算法的边界

- `bsdiff` 官方文档给出的生成内存为 `max(17*n, 9*n+m)+O(1)`。对 3.0 GiB 基线约需 **51 GiB** 以上，且设备应用补丁也需 `n+m` 量级内存，不适合作为当前超大 SO 的首选。见 [bsdiff 官方说明](https://www.daemonology.net/bsdiff/)。
- Zstandard 的 `--patch-from` 是以旧文件作字典的压缩。其 CLI 文档说明默认字典内存上限为 **128 MiB**，要覆盖 3 GiB 基线需调高 `-M` 并评估主机/设备内存与设备解码能力；当前设备没有 `zstd` 命令。见 [Zstandard CLI 文档](https://github.com/facebook/zstd/blob/dev/programs/zstd.1.md)。
- ADB CLI 的 `-z zstd` 是完整文件传输压缩，不需要设备持有旧 SO；本次验证能明显缩短传输，是差分试点的性能参照。Jugg 当前 `device.pushFile()` 的压缩行为尚未单独确认，正式修改前应在相同条件下打点比较。

## 5. 实施门禁

1. 用真实业务 SO 的连续至少三次构建测量：尺寸、变化块比例、压缩后完整推送耗时、差分生成/传输/校验/发布总耗时。若收益不稳定，优先采用压缩完整传输。
2. 在 API 26、30、当前设备及 `run-as`、Direct sandbox 支持范围内验证 `dd` 大偏移、文件权限、SELinux、可用空间和完整 SO 加载；同时检查 base/split、连续更新、多设备不同基线。
3. 验证基础文件消失/损坏、补丁损坏、传输中断、设备空间不足与发布失败。任何失败不得提交 deployment cache 或部署历史；前置失败可回到完整 push，已修改发布目录的失败必须执行现有 rollback/recover。
4. 真实 App 冷启动后用 Build ID、`findLibrary()` 和 `/proc/<pid>/maps` 确认加载的是重建后的 SO。仅 SHA-256 正确不足以证明运行时选中了新库。

本次仅完成只读分析、临时目录 PoC 和方案记录，未修改 Jugg 部署行为，也未新增自动化测试。
