# C++ 增量产物按需 Strip 方案

创建日期：2026-09-15。状态：已实施（`bec637dd7`），实施偏差与验证结果见第 13 节。

## 1. 结论

不直接执行或消费 app 的 `strip<Variant>DebugSymbols` task/artifact。AGP 将该 task 的输入绑定到 app 的 `MERGED_NATIVE_LIBS`，无论通过 task path 还是 `STRIPPED_NATIVE_LIBS` artifact provider 接入，都会把 app `merge<Variant>NativeLibs` 及其 native producer 依赖带入任务图，无法满足“只构建本轮受影响 C++ module”的目标。

推荐扩展现有 `juggCollectExternalBuildInfo` 收集阶段：

1. 外部构建仍只执行本轮受影响 module 的 `merge<Variant>NativeLibs`。
2. collector 读取 APK owner（base app 或 dynamic feature）的 `strip<Variant>DebugSymbols` 配置，但不执行该 task。
3. collector 对已选 module 的 `.so` 复现 AGP 的单文件 strip 行为，输出到本轮 Jugg 临时目录。
4. `ExternalBuildCompiler` 只收集 collector 返回的 stripped 输出，不再直接收集 module 的 unstripped merge 输出。

核心原则是：**复用 app strip task 的配置和工具选择，不复用它的 Gradle 依赖关系。**

## 2. 问题证据

Jugg report `49e45fa8` 中，本轮增量只执行了：

```text
:mp:dtmp:mergeDebugNativeLibs
:mp:appcommon:mergeDebugNativeLibs
```

随后 `ExternalBuildCompiler` 从 module merge 输出收集了未 strip 的 `libmp_appcommon.so`。该文件进入 staging 后大小为 `2908898504` bytes，超过 JVM `ByteArray` 的 `Int.MAX_VALUE` 上限，最终在 `DeployFilePathExt.kt` 的 `file.readBytes()` 处失败：

```text
java.lang.OutOfMemoryError: File .../libmp_appcommon.so is too big
(2908898504 bytes) to fit in memory.
```

同一报告的完整 Gradle 构建执行过 `:app:stripDebugDebugSymbols`，并产生：

```text
app/build/intermediates/stripped_native_libs/debug/
    stripDebugDebugSymbols/out/lib/arm64-v8a/libmp_appcommon.so
```

该构建及安装成功，说明本轮增量需要消费的是 app 打包语义下的 stripped native library，而不是 module merge 目录中的 unstripped 文件。

当前行为 owner：

- [GradleProjectInfoReader.kt](../../../main/src/main/java/com/sickworm/intellij/jugg/gradle/script/GradleProjectInfoReader.kt)：C++ metadata 记录 module `merge<Variant>NativeLibs` 及其输出目录。
- [ExternalBuildTaskRunner.kt](../../../main/src/main/java/com/sickworm/intellij/jugg/compiler/external/ExternalBuildTaskRunner.kt)：仅执行受影响的 external build tasks，并追加 `juggCollectExternalBuildInfo`。
- [ExternalBuildCompiler.kt](../../../main/src/main/java/com/sickworm/intellij/jugg/compiler/external/ExternalBuildCompiler.kt)：从 module merge 输出复制全部 `.so`，再按 APK CRC 过滤。
- [GradleProjectInfoReaderManager.kt](../../../main/src/main/java/com/sickworm/intellij/jugg/gradle/script/GradleProjectInfoReaderManager.kt)：collector 在 external tasks 之后重读 invocation-scoped metadata。

## 3. AGP strip 契约

已核对本地官方 AGP sources JAR：4.2.2、7.3.1、8.7.3、8.9.2、8.11.1。上述版本的 `StripDebugSymbolsTask` 核心行为一致：

1. 输入为当前 APK variant 的 `MERGED_NATIVE_LIBS`。
2. `keepDebugSymbols` 来自 `packaging.jniLibs.keepDebugSymbols`；旧版来自 `packagingOptions.jniLibs.keepDebugSymbols`。
3. 未命中 keep pattern 时执行：

   ```text
   <strip executable> --strip-unneeded -o <output> <input>
   ```

4. 命中 keep pattern、找不到对应 ABI 的 strip tool，或 strip 进程返回非 0 时，AGP 复制原文件作为输出。
5. 输出相对路径保持 `lib/<abi>/<name>.so`。

AGP 8.11.1 的 strip tool map key 已从内部 `Abi` 类型变为 ABI 字符串。Jugg 不能绑定具体 AGP 类签名，应通过现有反射读取工具 map，并将 key 归一化为 `arm64-v8a` 等 tag。

## 4. 不采用的方案

### 4.1 直接执行 `:app:stripDebugDebugSymbols`

不采用。它的输入 artifact 由 app `merge<Variant>NativeLibs` 构建，后者会解析并可能执行 app 中全部 native producer。即使多数 task 最后显示 `UP-TO-DATE`，仍扩大了配置、任务图和失效范围；缓存未命中时会实际重建无关 SO。

### 4.2 直接读取 `SingleArtifact.STRIPPED_NATIVE_LIBS`

不采用。artifact provider 带有 `builtBy(strip<Variant>DebugSymbols)` 关系；将其声明为 Jugg task 输入仍会恢复完整依赖链，并没有绕开 app merge。

### 4.3 对 app strip task 使用 `-x merge<Variant>NativeLibs`

不采用。app strip 会读取一个由被排除 task 所拥有的旧目录，结果依赖历史残留；主动覆盖该目录还会破坏 Gradle 的 output ownership 和 up-to-date 判断。该路径不能形成可靠的增量契约。

### 4.4 继续部署 module unstripped 输出，仅增加 IDE heap

不采用。报告中的文件已经超过 `ByteArray` 的结构上限，增加 `-Xmx` 不能使 `readBytes()` 接受超过 `Int.MAX_VALUE` 的文件。即使文件略小于该上限，部署链上的数组和 protobuf 转换仍会放大峰值内存。

## 5. 推荐流程

```text
changed C/C++ source
  -> selected module merge<Variant>NativeLibs
  -> juggCollectExternalBuildInfo
       -> locate APK owner strip<Variant>DebugSymbols
       -> read keepDebugSymbols + ABI strip executables
       -> strip/copy only selected module native outputs
       -> write invocation result + stripped output directory
  -> ExternalBuildCompiler collects stripped outputs
  -> existing APK CRC filtering
  -> existing NativeLib deploy path
```

collector 只通过 `mustRunAfter` 约束本轮已明确请求的 local external tasks；included build 继续沿用现有子 collector 汇总关系。不得对 app strip、app merge 或其它 native task 增加 `dependsOn`。

## 6. 数据契约修改

### 6.1 Request 增加 APK owner

给 `ExternalBuildInfoRequestItem` 增加可空字段：

```kotlin
val apkOwnerModuleRootDir: File?
val apkOwnerBuildVariant: String?
```

仅 C++ request 必填。`ExternalBuildCompiler` 使用现有 `resolveApkOwnerModule(module)` 解析 base app 或 dynamic feature owner，不能默认所有 SO 都属于 application module。Flutter 保持现有字段为空，不进入本方案。

旧 request JSON 缺少字段时按 null 读取；collector 将该 C++ invocation 标记为不支持并明确失败，不能继续部署 unstripped 输出。后续由用户执行正常 Gradle build 建立新基线；本方案不为罕见的旧 request 临时增加第二次自动构建。

### 6.2 Result 返回临时 stripped 输出

给 `ExternalBuildInfoUpdate` 增加：

```kotlin
val strippedNativeOutput: File? = null
```

该字段只描述本次 invocation 的 collector 结果，目录位于现有 `external_build_info/<invocation>/output` 下，不写入持久化 `ExternalBuildInfo`。这样不会让 project-info 引用一次性临时目录，也不需要给 `ModuleInfo` 增加全局 strip 配置。

建议目录：

```text
<collector-output>/native/<request-key>/<abi>/<name>.so
```

`request-key` 使用 module root、variant、task path、APK owner 的稳定摘要，避免 composite build 或同名 module 相互覆盖。实际落地时去掉了中间的 `lib/` 一级：`CompileOutput` 的 `baseDir` 约定是“直接包含 `<abi>` 目录”，AGP 的 `merge<Variant>NativeLibs` 输出目录本身包含 `lib/<abi>`，`ExternalBuildCompiler` 只会把它规范化成 `<abi>/<name>.so`，所以 `strippedNativeOutput` 返回的目录也必须直接包含 `<abi>`。keep pattern 匹配仍按 AGP 语义使用相对 strip 输入目录的 `lib/<abi>/<name>.so`。

## 7. Collector 实现

在 `GradleProjectInfoReaderManager.collectExternalBuildInfo()` 中，对 C++ update 增加以下局部步骤；保持现有 collector，不再注册第二个 Gradle task。

### 7.1 定位 strip task

1. 按 `apkOwnerModuleRootDir` 在当前 build root 中找到 APK owner project。
2. 查找精确名称 `strip${apkOwnerBuildVariant.camelCompat}DebugSymbols`。
3. 校验存在可读的 `keepDebugSymbols`、NDK handler 和 strip executable finder 能力。
4. 仅读取 task 属性。不得读取其 `inputDir` artifact provider 作为 collector task input，也不得调用 task actions。

task 缺失或关键属性无法读取时，输出明确的 unsupported reason 并使本轮 external incremental 失败。不能在 external tasks 已执行后静默改跑完整 build，也不能回退收集 unstripped 文件。

### 7.2 读取 strip 配置

复用 `readProperty()` 和 Provider 解包边界读取：

- `keepDebugSymbols`：归一化为 `Set<String>`。
- `sdkBuildService` 与 `ndkHandlerInput`：调用对应 AGP 版本的 `versionedNdkHandler(...).stripExecutableFinderProvider`。
- `stripExecutables`：转成 `Map<String, File>`。旧 `Abi` key 读取 `tag`，新版 String key 直接使用。

这些内部能力只留在 Gradle 进程中使用，不序列化 AGP 对象，也不在 IDE 进程加载 AGP classes。

### 7.3 匹配 keepDebugSymbols

保持 AGP 的相对路径和 glob 规则，匹配路径为 `lib/<abi>/<name>.so`。匹配器复现 AGP 当前实现：pattern 不以 `/` 或 `*` 开头时补 `/`，再使用 JVM `glob:` matcher。

该方法保持 private，并用英文注释说明其来源和兼容目的；不新增通用 packaging pattern 抽象。

### 7.4 逐文件输出

对本轮 C++ `ExternalBuildInfo.nativeOutput` 下合法的 `<abi>/*.so`：

1. 输出到 invocation-scoped 临时目录，先写同目录 `.tmp` 文件。
2. 命中 keep pattern 时使用文件流复制，不调用 `readBytes()`。
3. 有对应 ABI 工具时执行：

   ```text
   llvm-strip --strip-unneeded -o <tmp-output> <source>
   ```

4. strip tool 缺失或返回非 0 时，按照 AGP 契约记录 `warn` 并流式复制原文件；不吞掉原因。
5. 校验输出存在、为普通文件且非空，再原子移动为最终文件。
6. 输出超过 `Int.MAX_VALUE` 时立即失败并说明是“strip 后仍过大 / debug symbols 被保留 / strip tool 不可用”，禁止继续进入 `DeployItem.content: ByteArray` 后再抛 OOM。

本阶段可以遍历并 strip 已选 module merge 输出中的全部 SO，但不会触发其它 module 的 native build。第一版不增加源码到最终 SO 的映射、构建日志解析或跨 invocation strip cache；这些都不是解决当前任务图问题的必要条件。

### 7.5 失败隔离

- 任一被请求 C++ target strip/复制失败：该 external compile 整体失败，不部署同轮的部分 native 输出。
- Flutter collector 不受 C++ strip 能力影响。
- collector JSON 仍使用临时文件加原子移动；只有全部 update 和 stripped outputs 完整时才发布 result。
- 进程取消或 Gradle 失败时，runner 不读取旧 invocation 目录。

## 8. ExternalBuildCompiler 修改

`ExternalBuildCompiler` 在 runner 成功后保留完整 `ExternalBuildInfoUpdate`，不要只提取持久化 metadata：

1. Flutter 继续使用更新后的 `ExternalBuildInfo` 收集 assets/native archive。
2. C++ 必须使用对应 update 的 `strippedNativeOutput`。
3. C++ update 缺少该目录时明确失败，不能回退 `buildInfo.nativeOutput`。
4. `collectNativeArtifacts()` 保留现有 ABI/path 校验和 APK CRC 过滤，但移除从 unstripped module output 复制到 `cpp-native` 的中间层；collector 目录已经是 Jugg 所有的稳定本轮输出。
5. 同一 APK 路径出现重复库时继续按现有 contract 处理，本方案不混入 native packaging 冲突 owner 的重构。

这样大体积 unstripped 文件只作为外部 `llvm-strip` 的文件路径参数，不会先复制到 staging，也不会进入 JVM byte array。

## 9. 兼容与回退

| 场景 | 行为 |
|---|---|
| AGP strip task、keep 配置和 tool finder 均可读取 | 进入 selective strip |
| 命中 `keepDebugSymbols` | 按 AGP 语义复制；若最终文件超过部署上限则明确失败 |
| 单个 ABI strip tool 不存在 | 按 AGP 语义 warn + 复制；若最终文件过大则失败 |
| strip 进程返回非 0 | 最多执行一次，不重试相同命令；warn + 复制并执行大小门禁 |
| app strip contract 无法读取 | 本轮 external incremental 明确失败，提示执行正常 Gradle build |
| C++ module 属于 dynamic feature APK | 使用 `resolveApkOwnerModule()` 选择 feature 的 variant strip 配置 |
| Flutter external build | 保持现状，不经过 C++ selective strip |
| 未涉及 external C++ 的普通增量 | 原路径完全不变 |

不按 AGP 版本建立硬编码白名单。已核对版本仅作为兼容证据；运行时按 task/property 能力决定是否支持。

## 10. 验证先行与测试落点

本修改改变 compile/deploy 编排，必须保留 L3 或等价 Flow 证据。测试只保护用户可观察的任务选择、strip 产物和失败边界，不测试 private helper 的调用次数。

### 10.1 修改前失败证据

- 保存 report `49e45fa8`：unstripped `libmp_appcommon.so` 为 `2908898504` bytes，部署在 `readBytes()` 处失败。
- 建立 Gradle fixture：app strip task 依赖 app merge，app merge 再依赖 native A/B；请求只包含 native A。直接执行 app strip 时 native B marker 出现，证明该方案扩大任务图。
- fixture 中让 native A merge 输出包含带 debug section 的 SO；当前 `ExternalBuildCompiler` 收到 unstripped 文件，和 full Gradle stripped 输出 hash 不一致。

### 10.2 自动化测试

| 层级 | owner / 落点 | 断言 |
|---|---|---|
| L1 | 新增 `GradleProjectInfoReaderManagerNativeStripTest` | 能读取 fake app strip task 的 keep patterns 与 ABI tool map；旧 Abi key 和 String key 均归一化；缺能力时返回明确 unsupported |
| L1 | `ProjectInfoSerializerInGradleAndroidTestTest`、`JuggProjectInfoSerializerAndroidTestTest` | request owner 字段、result 临时输出字段支持新旧 JSON 和显式 null |
| L1 | `ExternalBuildTaskRunnerTest` | 派生命令只包含 selected module merge 和 collector，不包含 app strip/app merge；result 与 request key 完整匹配 |
| Internal Flow | `ExternalBuildFlowTest` | C++ 只收集 `strippedNativeOutput`；缺失、损坏、超限时失败；不会回读 unstripped `nativeOutput`；APK CRC 未变化时无部署输出 |
| Gradle 脚本 | `ReadProjectInfoScriptContentTest` + 适用的 Gradle compat tests | 生成 init script 可加载，collector 在 selected tasks 后运行，未形成 app strip/app merge 依赖 |
| L3/等价 Flow | `TopLevelFlowTest` 与真实双 native module 工程 | 修改 native A 后仅 A 的 external native build/merge 与 collector 执行；native B、app merge、app strip 不执行；Jugg 输出与随后 full Gradle app stripped output hash 一致并可安装运行 |

若真实大库不适合纳入仓库，使用报告作为失败证据，并在本地 fixture 创建 sparse 大文件验证 collector 不调用 `readBytes()`、超限在进入 deploy 前被拒绝。不要提交数 GB fixture。

定向验证命令按实际测试类使用 `--tests` 过滤；禁止运行无过滤的 `:main:test` / `:idea:test`。最后至少执行 `./gradlew :idea:compileKotlin`。

## 11. 实施顺序

1. 先增加任务图 fixture 和 `ExternalBuildFlowTest` 失败断言，固定“不能执行 app strip/app merge”和“不能收集 unstripped output”两个行为。
2. 扩展 request/result JSON，并完成新旧数据兼容测试。
3. 在 collector 内实现 APK owner strip contract 发现及 selective strip。
4. 修改 `ExternalBuildCompiler` 仅消费 stripped collector output。
5. 执行定向 L1/Internal Flow/Gradle compat 测试。
6. 使用双 native module 实际工程完成 L3：核对任务列表、产物 hash、安装和运行结果。
7. 同步 [02_compile_core.md](../../ai_knowledge/02_compile_core.md)、[02_compile_resource.md](../../ai_knowledge/02_compile_resource.md)、[03_deploy_core.md](../../ai_knowledge/03_deploy_core.md)，并检查中英文 Wiki 的 SO 更新页面是否需要同步。

## 12. 范围边界

本方案解决的是“C++ 增量构建产物未经 app strip，导致体积异常及部署失败”，并避免为获得 stripped 结果触发 app 全量 native task graph。

本方案不修改 `DeployItem.content: ByteArray` 契约。selective strip 后的最终 SO 通常会显著缩小，但任意最终部署文件仍可能造成高堆峰值；如果需要支持本身就很大的 stripped SO，应另立任务将 APK 更新、ADB 传输及 Apply Changes protobuf 链路改为流式或文件引用模式，不能把本方案表述为通用大文件部署改造。

## 13. 实施记录（2026-09-15）

实现提交：`bec637dd7 [bugfix] fix deploy failing with Java heap space after an incremental C++ build`。

### 13.1 落地内容

| 方案条目 | 落地情况 |
|---|---|
| §6.1 request 增加 APK owner | `ExternalBuildInfoRequestItem` 增加可空 `apkOwnerModuleRootDir` / `apkOwnerBuildVariant`；`ExternalBuildCompiler` 用现有 `resolveApkOwnerModule()` 解析 base app 或 dynamic feature，仅 C++ 填写，Flutter 保持 null |
| §6.2 result 增加 stripped 输出 | `ExternalBuildInfoUpdate` 增加可空 `strippedNativeOutput`，只描述本轮 invocation，不写入 `ExternalBuildInfo`/`ModuleInfo` |
| §7 collector 内复现 strip | `GradleProjectInfoReaderManager.stripExternalNativeOutput()`：只读 `strip<Variant>DebugSymbols` 的 `keepDebugSymbols`、`ndkHandlerInput`、`sdkBuildService`，通过 `versionedNdkHandler(...).stripExecutableFinderProvider` 取工具表；不执行 task action、不解析 `inputDir` provider |
| §7.3 keepDebugSymbols 匹配 | 复现 AGP 的 `compileGlob`（不以 `/` 或 `*` 开头时补 `/`，再走 JVM `glob:` matcher），匹配路径为相对 strip 输入目录的 `lib/<abi>/<name>.so` |
| §7.4 逐文件输出 | 先写同目录 `.tmp`，命中 keep／工具缺失／strip 非 0 时流式复制，校验存在、可读、非空、不超 `Int.MAX_VALUE` 后原子发布 |
| §8 ExternalBuildCompiler | C++ 只消费 `strippedNativeOutput`，缺少时明确失败；`collectNativeArtifacts()` 保留 ABI/path 校验与 APK CRC 过滤，并移除了复制到 `cpp-native` 的中间层 |
| §9 兼容与回退 | 旧 request/result JSON 缺字段按 null 读取，C++ 本轮明确失败，不回退 unstripped 输出、不自动跑完整 build |

### 13.2 与方案的必要偏差

1. **去掉 `lib/` 一级**：见 §6.2 的说明。`CompileOutput` 的 baseDir 必须直接包含 `<abi>`，否则 `isChangedNativeLib()` 会拼出 `lib/lib/<abi>/...` 而永远判定为“有变化”，`AssetOverlayCompiler` 也会写错路径。keep pattern 匹配路径不受影响。
2. **额外增加部署边界的大小门禁**：除 collector 的校验外，`DeployFilePathExt.toDeployItem()` 在 `file.readBytes()` 之前检查文件长度。collector 只保护 C++ 外部构建路径，而 `DeployItem.content: ByteArray` 是所有产物的共同结构上限；报告中的失败正是发生在这个边界。
3. **`Reflector.invoke` 不能用于 `versionedNdkHandler`**：`Reflector` 用 `getMethod(name, argTypes)` 精确匹配参数类型，而 Gradle 传入的是 `NdkHandlerInput` 的生成实现类，实测抛 `NoSuchMethodException`。改为按方法名 + 参数个数查找的私有 `invokeWithArg()`。
4. **strip 进程异常也按 AGP 语义降级为复制**：AGP 只在“找不到工具”时复制；本实现额外把工具文件不存在和进程启动失败也降级为复制并打印原因，仍由后续大小门禁保证不把超大文件带进部署数据。

### 13.3 实际验证结果

失败证据：report `49e45fa8` 的 `compile_2026-09-15_14-49-11.0.log`，`staging/overlays/lib/arm64-v8a/libmp_appcommon.so is too big (2908898504 bytes)`；同一报告的完整构建确认 `:app:stripDebugDebugSymbols` 已产出 stripped 文件。

自动化（全部通过）：

| 层级 | owner | 覆盖 |
|---|---|---|
| L1 | `GradleProjectInfoReaderManagerNativeStripTest` | fake AGP strip task 的 keep pattern 与 ABI 工具表读取；旧 `Abi` key 与 String key 归一化；keep/工具缺失/工具失败语义与「不重试」；缺 APK owner、缺 strip task、能力不可读明确失败；超限拒绝；collector `dependsOn` 本轮 module task，但不依赖 APK owner strip task |
| L2/Internal Flow | `ExternalBuildFlowTest`（17 例） | C++ 只部署 stripped 输出、不回退 unstripped；缺 stripped 输出整轮失败；派生命令只含 selected module merge + collector，不含 app strip/app merge；Flutter 路径不变 |
| L1 | `ExternalBuildTaskRunnerTest`（8 例） | request JSON 用 JsonSlurper 可读到 APK owner 字段；旧 result JSON 的 `strippedNativeOutput` 解析为 null |
| L1 | `DeployFilePathExtTest`（5 例） | 超限在 `readBytes()` 之前失败 |
| L1/生成物 | `ReadProjectInfoScriptContentTest` + Gradle 5/6/7/9 compat tests（20 例） | 生成 init script 在 Kotlin 1.3/1.5/2.x 上可编译可运行 |
| L3 等价 Flow | `ReadProjectInfoGradle9CompatTest#generatedScript_shouldBuildBeforeStrippingSelectedNativeOutput` | 真实 AGP 8.7.2 + NDK 27 + CMake：修改 C++ 后单独请求 collector，先执行选中的 app merge、但不执行 app strip；Jugg stripped 输出与 AGP `:app:stripDebugDebugSymbols` 输出 SHA-256 完全一致 |

手工等价验证（同一 fixture，AGP 8.7.2）：设置 `packaging.jniLibs.keepDebugSymbols += '**/libjuggfixture.so'` 后，Jugg 输出与 AGP 的 keep 语义输出同样 SHA-256 一致（24032 bytes 原样保留）。

### 13.4 未验证项

- 未在真实双 native module 工程 + 真机上执行完整 L3（本机没有该工程，也没有可用的 C++ Android 工程与设备）。真实 AGP 的 strip 语义已由上面的 Gradle 级 Flow 覆盖，但「native A 变化时 native B 的 merge task 不被执行」只在 L2 由派生命令断言覆盖，没有在真实双模块工程上复核。
- 未验证 release/minify 变体与 dynamic feature 的 APK owner 分支在真实工程中的表现；`resolveApkOwnerModule()` 是既有实现，本次只改调用方。
