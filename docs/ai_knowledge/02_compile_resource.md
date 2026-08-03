# 编译系统：资源编译链（res/assets/arsc/Compose resource）

> 最后核对：2026-07-28
> 一致性规则：文档与代码冲突时，以代码为准。

---

## 1. 文档定位

本页覆盖资源相关增量编译：`res/`、`assets/`、Compose Multiplatform resource、native lib 和 manifest 如何变成可部署 overlay。它重点说明 APK-scoped 编译、aapt2 `inclink` 状态、Compose accessor/asset 分流、DataBinding/ViewBinding 产物交接和资源过滤边界。

Manifest diff 见 `02_compile_manifest.md`；release 混淆见 `02_compile_obfuscation.md`；DataBinding 详细策略见 `02_compile_databinding.md`；部署如何消费资源 overlay 见 `03_deploy_core.md`。

---

## 2. 核心源码索引

| 类/接口 | 文件 | 作用 |
|---|---|---|
| `ResourceOverlayCompiler` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/overlay/ResourceOverlayCompiler.kt` | 资源主协调器；按 APK scoped 任务串联 manifest、flat compile、arsc link，并过滤最终 overlay |
| `ResourceCompiler` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/overlay/ResourceCompiler.kt` | 将资源文件或资源目录编译为 `.flat`；先处理 ViewBinding/DataBinding split XML 和生成源码 |
| `ArscCompiler` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/overlay/ArscCompiler.kt` | 使用 aapt2 `inclink` 载入当前 APK 资源表并 link 出 `resources.arsc`、compiled res、`R.java` |
| `AssetOverlayCompiler` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/overlay/AssetOverlayCompiler.kt` | 处理普通 `Asset`、APK 根目录 `ClasspathResource` 和 native lib 等非 res overlay |
| `ComposeResourceCompiler` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/compose/ComposeResourceCompiler.kt` | 按 Gradle 元数据选择 legacy XML 或现代 CVR 资源模型，组织完整资源上下文，并编译 generated Kotlin |
| `ComposeResourceGeneratorBridge` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/compose/ComposeResourceGeneratorBridge.kt` | 隔离加载项目的 Compose plugin JAR，按 generator API 形态调用 legacy 或现代官方 Kotlin generator |
| `ComposeResourceScanner` / `ComposeValueResourceConverter` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/compose/` | 扫描 legacy XML 或现代 drawable/font/value 描述；现代管线生成 CVR version 0，`files/` 不产生 accessor |
| `AndroidManifestCompiler` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/manifest/AndroidManifestCompiler.kt` | Manifest 增量合并，产物作为 `ArscCompiler` 输入 |
| `DataBindingGenBaseClassesCompiler` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/databinding/DataBindingGenBaseClassesCompiler.kt` | layout 资源进入 aapt2 前生成 ViewBinding/DataBinding 基础类与 split XML |
| `RJavaFixer` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/overlay/RJavaFixer.kt` | 修正 aapt2 生成的 `R.java`，供后续源码编译消费 |
| `StyleableFileGenerator` / `ResGuardMappingFileGenerator` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/overlay/` | 为 `inclink --load` 提供 styleable 与资源混淆映射输入 |

---

## 3. 核心数据流

| 数据 | 生产者 | 消费者 | 关键约束 |
|---|---|---|---|
| `CompileFile.Type.Resource` | 变更扫描 / 上游编译任务 | `ResourceOverlayCompiler` | 可能是单个文件，也可能是目录；目录会展开成真实资源文件集合 |
| split layout XML | `DataBindingGenBaseClassesCompiler` | `ResourceCompiler.aapt2Compile()` | 原 layout 会在 aapt2 compile 前被 split XML 替换 |
| generated Java/Kotlin | `ResourceCompiler` | `SourceCompiler` | ViewBinding/DataBinding 生成源码不部署，必须回流源码编译阶段 |
| `.flat` | `ResourceCompiler` | `ArscCompiler` | 只作为 link 输入，不直接部署 |
| latest res APK | `ArscCompiler.getResApk()` | aapt2 `inclink --load` | 若当前 APK 曾部署过 `resources.arsc`，会用已部署 arsc + manifest 组成临时 res APK，避免从原始 APK 旧资源表继续 link |
| `resources.arsc` / compiled res / manifest | `ArscCompiler` | 部署数据转换 | `CompileOutput.apkPath` 绑定当前 APK；多 APK 归属不能丢 |
| `targetApkPaths` | `CompileOutput` / 下游 deploy item | 部署分流 | class/dex 可归属多个 APK；资源/manifest 仍按 APK scoped 输出 |
| `CompileFile.Type.ComposeResource` | `FileChangesHandler` | `ComposeResourceCompiler` | `baseDir` 是命中的默认或自定义 Compose resource 根目录，不能改成 module root |
| `CompileFile.Type.ClasspathResource` | `JuggCompiler` | `AssetOverlayCompiler` | 表示必须保持 classpath 相对路径并写入 APK 根目录的 classpath resource；当前用于 legacy Compose resource |
| Compose task metadata | `GradleProjectInfoReader` / project info | `ComposeResourceCompiler` | 包含 generator API 形态、classpath、package、Res 类名、public/content-hash flag、source-set 目录和 asset 相对路径；按任务属性校验，不按 Kotlin/Compose 版本号拦截 |
| prepared CVR + generated Kotlin | `ComposeResourceCompiler` | generator bridge / Kotlin compiler | accessor 生成读取所有已知资源目录的完整 values/资源上下文，避免只看 changed file 丢失既有 key |
| changed Compose resource file | `ComposeResourceCompiler` | `AssetOverlayCompiler` | 现代管线以 `Asset` 复制本轮新增/修改的 CVR、drawable、font、`files/` 到 `assets/`；legacy 管线转为 `ClasspathResource` 并保持 APK 根目录 classpath resource 路径；两者都不进入 AAPT2 |

---

## 4. 核心调用链路

```text
JuggCompiler 资源阶段
  -> ResourceOverlayCompiler.splitApkAndCompile()
  -> BaseCompiler 按 moduleBelongsApkMap 把同一 module 输入拆给每个归属 APK
  -> AndroidManifestCompiler.doApkCompile() 只在 manifest 有真实 diff 时输出 manifest overlay
  -> ResourceCompiler.doModuleCompile() 处理 layout split / generated source，再 aapt2 compile 为 .flat
  -> ArscCompiler.doApkCompile() 为当前 APK loadTable，再 inclink flat + 可选 manifest
  -> ResourceOverlayCompiler.filterResources() 删除不应部署的 aapt2 额外产物和无变更 manifest
  -> 输出资源 overlay，同时把 generated Java/Kotlin 留给 SourceCompiler
```

多 APK 场景下，资源链路不是“同一份输出复制到多个 APK”。`splitApkAndCompile()` 会为每个 `ApkFileUnit` 单独调用 `doApkCompile()`，因为每个 APK 的资源表、package id、manifest 和 dynamic feature 依赖关系都可能不同。

### 4.1 Compose Multiplatform resource 链路

```text
FileChangesHandler
  -> 命中 ComposeResourceInfo.resourceDirectories
  -> ChangedFile(Type.ComposeResource, file, resourceDirectory, module)
JuggCompiler（早于 asset/resource/source）
  -> ComposeResourceCompiler
     -> legacy 管线直接扫描 XML；现代管线把全部 values XML 转为 CVR
     -> 扫描全部已知 source set 的 values/drawable/font；files 只作为 asset
     -> ComposeResourceGeneratorBridge 按 API 形态调用项目 plugin JAR 的官方 generator
     -> 将 generated Kotlin 同步到模块 Compose generated source 路径，供 IDE 索引、高亮和自动 import
     -> 一次 Kotlin invocation 编译 generated source；现代管线显式标注 expect/actual common sources
  -> 仅将本轮 changed CVR/drawable/font/files 作为资源输出
  -> JuggCompiler：legacy 输出转为 ClasspathResource，现代输出保持 Asset
  -> AssetOverlayCompiler：现代资源复制到 overlays/assets；legacy 资源保持 values/drawable/font 等 APK 根路径
  -> generated class 继续进入 source/dex
DeployDataPlanner
  -> 从 DeployFileStateTracker.compiledFiles 识别本轮 ComposeResource compile
  -> 写入 JuggDeployData.isComposeResourceCompiled，部署完成后重启 App 进程
```

这里“完整上下文”和“changed-only 输出”是两层语义：accessor 必须看见项目快照列出的全部资源目录，部署 overlay 只包含本轮新增/修改文件。配置时尚不存在的默认/自定义根也会持久化，扫描时按空目录处理，因此首次创建资源仍能被识别。Compose asset 不经过 `ResourceOverlayCompiler`、`ResourceCompiler`、`ArscCompiler` 或 AAPT2。

Compose resource 的重启判断使用本轮编译输入，不从最终 `CompileOutput.Type.Asset` 反推来源。`compiledFiles` 在最后一个设备成功 commit 前保留，因此正常部署和 retry 重建 `JuggDeployData` 时都能恢复该标记；warm-up 不设置标记。现代资源虽然位于 `assets/**`，`AssetManager` / Compose runtime 仍可能缓存已读取内容，Activity restart 不足以保证刷新，所以与 legacy APK 根目录资源一样需要进程重启。

Compose generated source 路径由 `ModuleBuildPathInfo.composeResourceGeneratedSourcePath` 从模块 build directory 直接派生，不进入 Gradle project info。Jugg 生成 accessor 后直接覆盖该目录，使 Android Studio 能索引新增资源并提供高亮和自动 import；同步失败只舍弃 IDE 辅助能力，不影响已经生成的增量编译产物。文件监听或影响传播上报这些 build directory 路径时，`FileChangesHandler` 会在类型识别前统一过滤，因此同轮 Compose resource 编译只使用 `ComposeResourceCompiler` 自己生成的 accessor class，不会再从 Gradle build 输出重复编译同名 Kotlin source。

---

## 5. 隐形约束 / 设计思路 / 已知边界

- `ArscCompiler` 为每个 APK 缓存一个 `Aapt2DaemonInvoker`；invoker 死亡或 link 失败会 release，下一轮重新 `loadTable`。
- `Aapt2DaemonInvoker` 使用结构化参数列表写入 daemon 协议，每个参数独占一行，APK、资源和输出路径允许包含空格。
- `loadTable()` 失败时会立即 release invoker 并返回失败，禁止缓存未加载资源表的 daemon，避免后续 inclink 退化为 `no cache data found`。
- dynamic feature 编译依赖 base APK：base arsc 更新后，`ArscCompiler` 会把 base 本轮 flat 文件加入 feature 的 link 输入，以同步资源 ID。
- `getResApk()` 会优先使用已部署的 `resources.arsc` 和 manifest 组成临时资源 APK；只看原始 APK 会漏掉上轮 Jugg 资源增量。
- `ResourceOverlayCompiler.filterResources()` 会删除根 `Manifest.java`，并在 manifest 无真实变更时删除根 `AndroidManifest.xml`，避免触发 APK repackage。
- aapt2 可能为一个资源生成多个配置目录产物；如果额外产物对应的 override XML 已存在，过滤逻辑会移除该额外产物，避免覆盖用户显式资源。
- `ResourceCompiler` 对目录输入使用目录路径 MD5 建子输出目录，避免不同资源目录 flat 文件名冲突。
- DataBinding mapper 生成不在资源阶段完成；资源阶段只处理 base class / split XML，mapper 交给 `SourceCompiler` 在源码编译前处理。
- Compose preparation 由 Jugg 实现，不执行 Gradle Compose resource task；Kotlin 文件生成调用项目 Compose plugin JAR 的官方 generator API。当前兼容 legacy 单任务 API，以及带 converter/accessor/collector 的现代 API；API 缺失时按结构化原因回退 unsupported。
- legacy Android runtime 通过 classloader 读取 `values/...`、`drawable/...` 等 APK 根目录资源，增量 overlay 必须使用显式的 `CompileFile.Type.ClasspathResource` 保持同名根路径；不能套用普通 Android asset 的 `assets/` 前缀。现代 Compose resource 继续使用 `CompileFile.Type.Asset` 和 Gradle metadata 提供的 asset relative path。
- Compose resource compile 只在本轮实际存在非空部署数据时触发进程重启；普通 Android asset 不因位于 `assets/**` 自动升级为 App restart。
- 现代管线支持 string、string-array、plurals、drawable、font，并透传 Res 类名与 content hash；legacy 管线按上游能力支持 string、drawable、font。`files/` 会复制到 asset，但不会生成 typed accessor。
- 只支持新增和修改。删除文件无法由当前文件事件恢复资源类型和 `baseDir`，必须完整 Gradle build；当前没有 deletion 图、generated source/cache 复用或完整 source-set 依赖图。

### 5.1 测试落点

- L1：`ComposeValueResourceConverterTest`、`ComposeResourceScannerTest`、`ComposeResourceGeneratorBridgeTest` 验证 CVR/扫描结果、缺失根、diagnostic 回映射、source-set 身份和官方 golden Kotlin 输出。
- L2：`FileChangesHandlerTest` 验证默认/自定义/unsupported/首次创建目录映射为 `ComposeResource` 且保留正确 `baseDir`，并覆盖传统/集中式 build directory 的文件与目录事件过滤；`KmpComposeFlowReproTest` 验证 Kotlin 1.9/2.1/2.3 对应 Compose generator 的真实 Gradle metadata、编译、D8、staging 与 generated accessor 回写，并覆盖资源与 Gradle generated accessor 同轮上报时不产生重复 class。Kotlin 1.7 demo profile 保留用于非 Compose Multiplatform 回归，并显式排除 `kmpCompose`。
- L3：`KmpComposeDeployFlowTest` 通过代表性 Compose profile 的真实 demo full install、基线资源缓存预热、仅资源增量 compile/deploy/run 和 logcat 覆盖进程重启后的 accessor 实际消费、目标 APK 与无增量 Gradle Compose task；多版本产物路径矩阵由 L2 覆盖，不在 L3 重复展开。

### 5.2 Android Studio E2E 验证口径

Android Studio E2E 应分别验证三层证据：首次 Jugg Run 完成 Gradle baseline，新增资源 key 后 Jugg 增量生成并编译 accessor，再次只修改 value 后运行时读取到新内容。验证 value 更新前应先在基线进程读取同一资源形成缓存；Compose resource 非空增量会在 overlay 完成后重启 App 进程，不需要打开 `Always restart app after deployment`。1.9 使用 `src/commonMain/composeResources`；2.1/2.3 使用 `composeResourcesExtended`，并额外覆盖 `src/androidMain/customComposeResources`。每次 profile 切换后必须 Gradle Sync，结束后恢复 1.9。自动化中 `KmpComposeFlowReproTest`（L2）负责 1.9/2.1/2.3 编译与产物路径矩阵，`KmpComposeDeployFlowTest`（L3）只验证代表性 profile 的真实运行链路。

---

## 6. 排查入口

| 现象 | 优先入口 |
|---|---|
| aapt2 compile 失败 | `ResourceCompiler.aapt2Compile()`：看 `compile --legacy` 命令与 flat 输出是否存在 |
| aapt2 link / arsc 失败 | `ArscCompiler.doApkCompile()` 和 `incLinkCompile()`：看 `loadTable`、`inclink` errorOutput、invoker 是否重建 |
| `multiply apk load not supported` | 检查是否仍有调用方把整条命令按空格拆参；所有路径参数必须作为 `Aapt2DaemonInvoker.invoke(List<String>)` 的独立元素传入 |
| `no cache data found, run with --load first` | 先找同一 invoker 的 `loadTable failed`；失败实例不应进入 `aapt2InvokerMap` |
| dynamic feature 资源 ID 异常 | `ArscCompiler.isBaseApkArscUpdate` / `baseApkUpdateFlatFiles`：确认 base 更新是否参与 feature link |
| 资源 overlay 输出到错误 APK | `BaseCompiler.splitApkAndCompile()` 与 `CompileOutput.apkPath`：确认 module 到 APK 的归属和输出 apkPath |
| manifest 无变更却触发重打包 | `ResourceOverlayCompiler.filterResources(...)`：确认 `isNeedOutputManifest=false` 时根 manifest 是否被过滤 |
| layout 相关 generated source 未参与源码编译 | `ResourceCompiler.processViewBinding()` 和 `SourceCompiler.prepareSourceCompile()` |
| R 引用异常 | `ArscCompiler.incLinkCompile()` 生成的 `R.java` 与 `RJavaFixer.fixIfNeeded()` |

---

## 7. 关联文档

- Manifest 增量合并：`02_compile_manifest.md`
- 混淆映射：`02_compile_obfuscation.md`
- 源码编译：`02_compile_source.md`
- DataBinding：`02_compile_databinding.md`
- 部署核心：`03_deploy_core.md`
