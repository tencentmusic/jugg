# 超大 Native Library 增量部署方案

## 1. 背景

Jugg report `a85cd5a3` 中，用户修改 C++ 源码后生成：

- `lib/arm64-v8a/libmp_appcommon.so`
- 解压大小：`2,951,316,672` bytes，约 2.75 GiB
- APK 内压缩大小：约 700 MiB
- APK 总大小：约 1 GiB

项目在本地构建时配置：

```groovy
if (gradle.ext.local) {
    // don't strip so, for debug
    jniLibs.keepDebugSymbols.add("**/*.so")
}
```

因此 Jugg 按 APK owner 的 `keepDebugSymbols` 语义保留完整调试符号，不执行 strip。当前 collector 能正确找到与 APK 对应的 SO，但在进入部署数据前因文件超过 `Int.MAX_VALUE` 而失败：

```text
Native library lib/arm64-v8a/libmp_appcommon.so is 2951316672 bytes,
exceeding the 2147483647 bytes deploy limit.
```

此前的 selective strip 修复解决了“误用未 strip SO 导致 JVM OOM”的问题；本问题不同：用户明确要求保留调试符号，需要让合法的 2～4 GiB SO 在不整体载入 JVM heap 的情况下通过增量部署链路。

## 2. 已确认事实

### 2.1 当前限制 owner

当前 2 GiB 限制来自内存数据模型，不是本次 APK 的压缩大小：

1. `CompileOutput.toDeployItem()` 调用 `file.readBytes()`。
2. `DeployItem.content` 固定为 `ByteArray`，单对象无法表示超过 `Int.MAX_VALUE` 的内容。
3. `ApkFileModifier` 的替换输入是 `Pair<String, ByteArray>`。
4. `NativeSandboxWriter` 会把 `DeployItem.content` 再写入临时文件后执行 `adb push`。
5. collector 为避免后续 `readBytes()` OOM，提前拒绝超过 `Int.MAX_VALUE` 的 NativeLib。

单纯删除 collector 门禁或增大 IDE heap 不能解决问题，只会把失败推迟到 `ByteArray` 分配阶段。

### 2.2 APK 压缩口径

用户看到的约 700 MiB 是 APK ZIP entry 的压缩大小。将该 entry 解压后仍是约 2.7 GiB，与 Jugg 收集到的文件一致，不存在选错 SO 的问题。

当前正常 Gradle APK 中：

- `resources.arsc` 为 `STORED`。
- `lib/arm64-v8a/libmp_appcommon.so` 为 `DEFLATED`。
- 该 APK 能正常安装，说明该项目允许压缩 native library，PackageManager 会在安装阶段解压 SO。

`STORED` 与 `DEFLATED` 并不矛盾。`ApkFileModifier.insertFileJvm14()` 的 ZipFS 全局 `STORED` 配置是为了避免更新 `resources.arsc` 时将其压缩，否则可能触发：

```text
INSTALL_PARSE_FAILED_RESOURCES_ARSC_COMPRESSED
```

该兼容保护必须保留，但不能据此把 APK 中所有 entry 都视为必须 `STORED`。

真正的问题是 ZipFS 的 `compressionMethod` 为文件系统级配置，无法在同一次更新中表达：

```text
resources.arsc                          -> STORED
lib/arm64-v8a/libmp_appcommon.so       -> DEFLATED
```

若继续用全局 `STORED` 写入 2.7 GiB SO，APK 会膨胀数 GiB；若把全局配置改成 `DEFLATED`，又可能破坏 `resources.arsc` 安装兼容。因此大 SO 支持必须按 entry 继承压缩方式，而不是删除或反转现有 ZipFS 全局配置。

### 2.3 现有流式 ZIP 路径

`ApkFileModifier.insertFileUnderJvm14()` 已具备需要的流式 ZIP 重写主体：

```text
ZipInputStream 逐条读取旧 APK
  -> ZipOutputStream 逐条写入新 APK
  -> 命中待替换 entry 时写入新内容
```

替换已有 entry 时已有 `ZipEntry(entry)` 复制逻辑，可以继承旧 entry 的 compression method：

- 原 SO 为 `DEFLATED`，替换后继续 `DEFLATED`。
- 原 `resources.arsc` 为 `STORED`，替换后继续 `STORED`。

该路径当前不能支持 2.7 GiB SO 的原因不是 ZIP 读取方式，而是待替换内容仍固定为 `ByteArray`：

```kotlin
private val insertFiles = mutableListOf<Pair<String, ByteArray>>()
```

以及：

```kotlin
newApkStream.write(replaceContent)
```

因此应优先增强并复用 `insertFileUnderJvm14()`，不重新设计一套独立 ZIP 实现。

### 2.4 工具链初步可行性

已用本机 Android Build Tools 36.0.0 做初步实验：

1. 构造解压大小 `2,214,592,512` bytes、压缩大小约 2 MiB 的 APK entry。
2. `zipalign` 成功。
3. `apksigner sign` 成功。
4. `apksigner verify` 成功。

该实验确认“单 entry 解压大小超过 `Int.MAX_VALUE`”本身不会阻止压缩 APK 完成对齐、签名和校验。实验未覆盖真实 2.7 GiB ELF、旧版 Build Tools 和设备安装，因此这些仍是实施验证门禁。

## 3. 目标

1. 支持解压大小超过 2 GiB、但仍处于经典 ZIP entry 范围内的 NativeLib 增量部署。
2. NativeLib 从采集到 APK 写入或 sandbox push 的过程不得整体载入 JVM heap。
3. 命中 `keepDebugSymbols` 时保持源 SO 字节不变。
4. 更新 APK 时继承已有 SO entry 的压缩方式，使最终 APK 大小与正常 Gradle 打包语义一致。
5. 保留现有 `zipalign -> sign -> verify -> 原子替换`、SO hot update 开关和失败回退契约。
6. 非 NativeLib 的 Dex、资源、Asset 部署行为保持不变。

## 4. 非目标

1. 不自动覆盖或忽略用户的 `keepDebugSymbols` 配置。
2. 不通过提高 Android Studio/Jugg JVM heap 规避问题。
3. 不删除或弱化现有 `resources.arsc` 的 `STORED` 安装兼容保护。
4. 不支持单个 APK entry 大于等于经典 ZIP 4 GiB 边界的场景。
5. 不承诺最终物理 APK 接近或超过 4 GiB 时仍能签名、安装。
6. 不支持基线 APK 中不存在、且无法可靠判断 native packaging 语义的超大新增 SO。
7. 首版不引入二进制 diff、分块 SO 格式或设备端解压协议。
8. 首版不以压缩、CRC 或 ADB 传输耗时优化为主要目标。

## 5. 设计原则

- 仅大小超过 `Int.MAX_VALUE` 的 NativeLib 使用文件承载；普通 NativeLib 和其它产物保持现有 byte-backed 路径。
- 文件大小、传输长度和日志统一使用 `Long`。
- 所有文件处理使用 bounded buffer 流式读写。
- 不允许在失败时回退到 `readBytes()` 或伪造成功。
- 保留普通小文件更新的 JVM 14+ ZipFS 快速路径及全局 `STORED` 配置。
- 文件承载的大 NativeLib 触发增强后的现有流式 ZIP 路径。
- file-backed 大 NativeLib 必须继承基线 APK 中同路径 entry 的压缩方式；同批 byte-backed entry 在 JVM 14+ 继续使用现有 `STORED` 语义。
- 大文件能力失败时局部收口，保留普通小文件部署能力和既有失败原因。

## 6. 方案设计

### 6.1 DeployItem 支持文件承载

调整 `DeployItem`，使其能够表示两种 payload：

- byte-backed：保持当前 Dex、资源、Asset 行为。
- file-backed：仅用于大小超过 `Int.MAX_VALUE` 的 NativeLib。

对调用方提供统一能力：

- `size: Long`
- 打开输入流
- 判断是否为文件承载
- 仅在确认内容能够进入 `ByteArray` 的场景获取 bytes

NativeLib 的 `content` 访问不得隐式读取整个文件；误用时应明确失败，避免后续新增调用重新引入 OOM。

`CompileOutput.toDeployItem()` 对大小超过 `Int.MAX_VALUE` 的 NativeLib：

1. 校验文件存在、可读且非空。
2. 使用流式 CRC32 生成现有 checksum。
3. 保存源文件引用，不调用 `readBytes()`。

大小未超过 `Int.MAX_VALUE` 的 NativeLib 以及其他类型继续使用现有 byte-backed 路径和大小限制；其他类型超过该限制时仍明确失败。

### 6.2 collector 移除 ByteArray 边界

`GradleProjectInfoReaderManager.stripExternalNativeOutput()` 保留：

- 空文件检查。
- strip/keepDebugSymbols 语义。
- 临时文件与原子发布。
- 缺少 strip tool 时按 AGP 语义 package-as-is。

移除基于 `Int.MAX_VALUE` 的 deploy-data 门禁。collector 只负责产出有效文件；最终 ZIP/APK 边界由实际 APK transport 校验。

### 6.3 ApkFileModifier 流式替换文件

`ApkFileModifier` 增加文件输入能力，内部替换项不再只保存 `ByteArray`，至少能够表示：

- ByteArray 内容。
- File/InputStream 内容及其 `Long` size。

该表示只作为 APK modifier 的最小内部数据结构，不扩展为通用 ZIP 框架。

路径选择：

- 普通 Manifest、resources、Dex 等小型 byte-backed 更新，继续使用 JVM 14+ `insertFileJvm14()` ZipFS 快速路径。
- 该快速路径继续保留全局 `compressionMethod=STORED`，不改变 `resources.arsc` 的既有兼容行为。
- 存在 file-backed NativeLib 时，转入增强后的 `insertFileUnderJvm14()` 流式 ZIP 重写路径；因此普通小型 NativeLib 仍走现有 JVM 14+ ZipFS 快速路径。方法名后续可保持不变，避免引入无关重命名。

流式重写要求：

1. 逐 entry 读取旧 APK，不能将 entry 整体读入内存。
2. 复用现有 `ZipInputStream -> ZipOutputStream` 主体和 `ZipEntry(entry)` metadata 复制逻辑。
3. 替换 file-backed entry 时继承原 entry 的 compression method；byte-backed entry 在 JVM 14+ 保持 ZipFS 快路径的 `STORED` 语义，旧 JVM 保持原流式路径行为。
4. 本案例的 `libmp_appcommon.so` 必须继承 `DEFLATED`，通过固定大小 buffer 从 File/InputStream 流式压缩写入。
5. 同轮若更新 `resources.arsc`，必须继续使用 `STORED` method，不能因存在 DEFLATED SO 而改变。
6. `STORED` entry 写入前提供正确的 `Long size` 和 CRC32；CRC 使用流式计算，禁止 `readBytes()`。
7. `DEFLATED` entry 直接流式写入，不创建与 entry 解压大小相当的内存对象。
8. 未被替换的 entry 保留原有压缩方式和必要 metadata。
9. 同轮 byte-backed 和 file-backed 替换必须能够共同完成。
10. 更新完成后继续执行既有流程：`zipalign -> apksigner/custom sign script -> apksigner verify -> 原子替换原 APK`。

首版只承诺“基线 APK 中已经存在同路径超大 SO”的更新。新增 entry 没有 compression method 可继承：

- 普通小 entry（包括小型 NativeLib）继续沿用现有 byte-backed 行为。
- 超大 NativeLib 必须根据基线 APK 的 native packaging 语义决定。
- 若不能可靠判断，明确失败；不得无依据地全局选择 `STORED` 或 `DEFLATED`。

### 6.4 IncrementalDeployHelper 传递文件 payload

`IncrementalDeployHelper.updateApk()` 根据 payload 类型调用 APK modifier：

- byte-backed：沿用 `addFile(path, bytes)`。
- file-backed：调用文件输入入口。

`exportIncrementalApk()` 在复制目标 APK path 时必须保留原 payload 类型，不得将文件 payload 转换为 bytes。

### 6.5 Native Sandbox 直接 push 源文件

`NativeSandboxWriter` 对超过 `Int.MAX_VALUE` 的 file-backed NativeLib：

1. 直接执行 `adb.push(sourceFile, stagingPath)`。
2. 不再创建并写入与源 SO 等大的本地临时文件。
3. `StagedFile.size` 改为 `Long`。
4. 设备侧继续通过 `wc -c` 校验完整大小。
5. copy/SELinux 失败时继续只清理本轮目标文件，并回退 APK 更新链路。

普通 byte-backed NativeLib 继续使用现有本地临时文件路径。SO 很大时仍不自动启用 sandbox 部署。是否进入该路径继续由现有设置、API、ABI 和 sandbox 能力决定。

### 6.6 边界与错误信息

新增或调整以下失败边界：

- 源文件在部署前消失、不可读或长度变化。
- 单 entry 大于等于 4 GiB，需要超出首版范围的 ZIP64 语义。
- 流式写入后的 APK 物理大小无法由当前 APK 工具链安全处理。
- 原 entry 为 STORED，替换后 APK 会超过安全边界。
- 超大新增 NativeLib 不存在可继承 entry，且无法确定 native packaging 语义。
- zipalign、签名、verify 或设备安装拒绝超大 entry。

错误信息必须包含：

- SO 路径。
- 解压大小。
- 原 entry 压缩方式。
- 当前失败阶段。
- 是否可以改用 SO hot update 或正常 Gradle install。

## 7. 预计代码变更

| 文件 | 责任 |
|------|------|
| `main/src/main/java/com/sickworm/intellij/jugg/deploy/run/JuggDeployData.kt` | 让 `DeployItem` 支持 byte-backed 与 file-backed payload，并提供统一 size/stream 能力 |
| `main/src/main/java/com/sickworm/intellij/jugg/deploy/DeployFilePathExt.kt` | NativeLib 生成 file-backed item，流式计算 checksum；其他类型保持原行为 |
| `main/src/main/java/com/sickworm/intellij/jugg/gradle/script/GradleProjectInfoReaderManager.kt` | 移除 NativeLib 的 `Int.MAX_VALUE` 门禁，保留产物完整性校验 |
| `main/src/main/java/com/sickworm/intellij/jugg/apk/ApkFileModifier.kt` | 接收 ByteArray/File 输入；保留 ZipFS 小文件快路径；增强并复用 `insertFileUnderJvm14()`，file-backed entry 继承压缩方式，JVM 14+ byte-backed entry 保持 `STORED` |
| `main/src/main/java/com/sickworm/intellij/jugg/compiler/IncrementalDeployHelper.kt` | 将 payload 按 bytes/file 分派给 APK modifier，并保持增量 APK 导出契约 |
| `main/src/main/java/com/sickworm/intellij/jugg/deploy/nativesandbox/NativeSandboxWriter.kt` | 直接 push 源文件，使用 `Long` 长度完成设备侧校验 |

`JuggException.kt` 无需修改：非 NativeLib 超限继续复用现有错误，NativeLib 例外在 `DeployFilePathExt.kt` 的类型分支内收口。

预计不新增生产类；若实现中发现 `DeployItem` 内部双承载导致状态无法形成清晰不变量，需要先回到评审，再决定是否引入最小 payload 类型。

## 8. 测试价值与验证设计

### 8.1 失败证据

- report：`a85cd5a3`
- 文件：`lib/arm64-v8a/libmp_appcommon.so`
- 大小：`2,951,316,672` bytes
- 失败 owner：collector 的 deploy-data 2 GiB 门禁
- 用户可观察结果：C++ 编译成功，但增量部署失败并回退下一次 Gradle build

### 8.2 自动化测试价值判断

该能力保护稳定且用户可观察的行为，能够形成确定性断言，通过测试价值门禁。

预计测试 owner：

| 测试 | 层级 | 断言 |
|------|------|------|
| `DeployFilePathExtTest` | L1 | 普通 NativeLib 与资源仍使用 bytes；file-backed payload 的 checksum 与目标 APK 信息保持正确 |
| `GradleProjectInfoReaderManagerNativeStripTest` | L1 | 删除“超大 NativeLib 必须失败”的旧契约，保留 keepDebugSymbols 与 package-as-is 行为保护 |
| `ApkFileModifierTest` | L1/真实产物 | 小文件仍走原 ZipFS 语义；文件流替换已有 SO 并继承 DEFLATED；同轮 STORED entry 不被压缩；解压内容 SHA-256 一致；签名失败不覆盖原 APK |
| `NativeSandboxWriterTest` | L1 | 直接 push 原文件；设备校验脚本使用 Long 长度；失败清理契约不变 |

普通测试不通过 `toDeployItem()` 扫描真实 2.7 GiB fixture，避免长期占用磁盘和测试时间；流式 ZIP 算法使用小文件验证，sandbox 直接 push 可使用不实际传输的 sparse file。超过 `Int.MAX_VALUE` 的真实阈值切换使用独立产物验证和手工回归矩阵证明，不为测试增加生产 seam。

### 8.3 定向验证

实施后执行：

1. 定向运行上述测试 owner，不运行无 `--tests` 过滤的全量 `:main:test` / `:idea:test`。
2. `./gradlew :main:compileKotlin`。
3. `./gradlew :idea:compileKotlin`。
4. 使用大于 `Int.MAX_VALUE` 的可压缩 fixture 验证 ZIP 流式写入、zipalign、签名和 verify。
5. 使用用户真实 2.7 GiB ELF 完成最终验证。

## 9. 真实环境验证矩阵

| 场景 | 操作 | 通过标准 |
|------|------|----------|
| 真实大 SO 收集 | 使用真实 2.7 GiB `libmp_appcommon.so` 执行 incremental C++ build | collector 成功，未发生 `Int.MAX_VALUE`/OOM 失败 |
| APK 体积 | 用该 SO 更新约 1 GiB 基线 APK | 更新后 APK 与正常 Gradle APK 同量级，不能膨胀到约 3 GiB |
| entry method | 对更新后 APK 执行 `zipinfo` | `resources.arsc` 为 `STORED`；`lib/arm64-v8a/libmp_appcommon.so` 为 `DEFLATED` |
| 内容一致性 | 从更新后 APK 解压 SO | 长度与源文件一致；SHA-256 与源文件一致 |
| APK 工具链 | 执行 zipalign、默认签名或自定义签名、`apksigner verify` | 每一步成功，失败不覆盖原 APK |
| 真机安装 | 安装更新后的 APK | PackageManager 安装成功，无 `RESOURCES_ARSC_COMPRESSED` 等解析错误 |
| App 运行 | 启动 App 并加载该 SO | App 正常启动，目标 native library 加载成功 |
| JVM 内存 | 记录部署前后 IDE/Jugg JVM 峰值 heap | heap 不随 2.7 GiB SO 大小线性增长，不出现同量级 byte array |
| 小文件回归 | 更新 Manifest、resources、Dex 等普通小文件 | JVM 14+ ZipFS 快速路径行为保持不变，`resources.arsc` 兼容保护仍成立 |
| 混合更新 | 同轮更新 file-backed SO 与 byte-backed APK entry | 两类内容均正确写入，各自 compression method 正确 |
| SO hot update | 开启开关后部署真实 2.7 GiB SO | 直接 push 源文件；无额外 2.7 GiB 本地临时副本；设备长度校验成功 |
| 失败恢复 | 人为制造签名、磁盘或 push 失败 | 原 APK 保留；本轮临时文件按现有策略清理；最终异常保留真实阶段和原因 |

以下条件全部满足后才可认为功能完成：

1. 上述矩阵中真实 2.7 GiB 主路径全部通过。
2. 未命中大文件条件的普通 Dex、资源、小型 SO 部署结果保持不变。
3. 没有通过提高 JVM heap、强制 strip 或移除 `resources.arsc` 保护绕过问题。

## 10. 风险与降级

### 10.1 性能与磁盘空间

2.7 GiB SO 的 CRC、压缩和设备传输必然耗时。APK 更新还需要 working、updated、aligned 等临时 APK，磁盘空间消耗可能达到数 GiB。

首版记录每个阶段的输入大小、输出大小和耗时；若磁盘不足或外部工具失败，保留原 APK 并明确失败，不删除用户基线。

### 10.2 Build Tools 与设备兼容

初步实验只覆盖 Build Tools 36.0.0。实现验证至少补充当前项目实际使用的 Build Tools 和目标设备。若旧工具链不支持，按当前环境 Best-effort 失败，不使用未经验证的 ZIP64 或自定义 APK 格式绕过。

### 10.3 文件生命周期

file-backed `DeployItem` 依赖 staging 文件在部署结束前保持存在且不被修改。写入 APK或 push 前应重新校验存在性和长度；最终异常不得被包装成泛化的部署失败。

### 10.4 压缩方式

错误地将原本 DEFLATED 的大 SO 改为 STORED 会显著放大 APK；错误地将 `resources.arsc` 改为 DEFLATED 会直接导致安装失败；错误地改变其它 native entry 的 method 也可能改变安装/加载语义。因此首版让 file-backed 大 NativeLib 严格继承已有 entry method，同时让 JVM 14+ byte-backed entry 继续使用 ZipFS 小文件快路径的 `STORED` 语义，不做全局压缩策略重写。

## 11. 文档同步

功能实现后同步：

- `docs/ai_knowledge/02_compile_core.md`
- `docs/ai_knowledge/03_deploy_core.md`
- `docs/ai_knowledge/05_utilities.md`
- `docs/ai_knowledge/98_code_map.md` 中相关职责描述
- `docs/wiki/zh/capabilities/compile/so-update.md`
- `docs/wiki/capabilities/compile/so-update.md`

中英文 Wiki 保持严格镜像，并明确大文件、压缩方式、磁盘空间和工具链边界。

## 12. 实施顺序

1. 用真实 2.7 GiB ELF 完成 zipalign、签名、verify 的可行性复核。
2. 先为 file-backed NativeLib 行为补充失败测试或现有门禁测试。
3. 修改 `DeployItem` 与 `toDeployItem()`，仅为超过 `Int.MAX_VALUE` 的 NativeLib 打通文件承载。
4. 扩展 `ApkFileModifier` 的插入内容模型，支持 ByteArray 与 File/InputStream。
5. 增强并复用 `insertFileUnderJvm14()`，验证 SO 继承 DEFLATED、`resources.arsc` 继承 STORED。
6. 修改 IncrementalDeployHelper 和增量 APK 导出。
7. 修改 Native Sandbox 直接 push。
8. 执行定向测试、编译和真实环境验证矩阵。
9. 同步知识库与中英文 Wiki。
10. 进行只读实现审查，修复有效问题后重新验证。

## 13. 本次评审范围外

- 自动打开 SO hot update。
- 对 2.7 GiB SO 做增量 binary diff。
- 将 SO 分片后修改 App runtime loader。
- 将 NativeLib 上传到远端或对象存储后由设备下载。
- 修改用户 `keepDebugSymbols` 配置。
- 删除 `resources.arsc` 的 STORED 安装兼容保护。
- 把所有 APK entry 全局改成 DEFLATED 或 STORED。
- 为大 SO 新建一套与 `insertFileUnderJvm14()` 平行的 ZIP 重写框架。
- 无关的 APK modifier 重构或 ZIP 工具替换。

## 14. 实施结果

已按批准范围完成最小适配：仅大小超过 `Int.MAX_VALUE` 的 NativeLib 使用 file-backed payload；普通 NativeLib 和其它产物继续使用原 byte-backed 路径。file-backed payload 已接入 APK 流式替换和 Native Sandbox 直接 push，并保留源文件状态、CRC、基线 entry 压缩方式、经典 ZIP 4 GiB 边界和原 APK 失败保护。

自动化验证已覆盖小 SO 原路径、非 NativeLib 超限失败、file-backed 禁止读取 `content`、混合 `DEFLATED` / `STORED` 更新、缺失源文件、基线缺少大型 entry、4 GiB 边界和 sandbox 直接 push。真实 2.7 GiB ELF 的 zipalign、签名、安装、运行与 JVM 峰值验证仍需按第 9 节矩阵在用户工程和目标设备完成，不能由 sparse fixture 代替。
