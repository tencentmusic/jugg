# KMP + Compose Resources 脱离 Gradle 增量编译方案

> 背景：Jugg report `b5731090` 中，新增 Compose Resources 字符串后，生成的 `Res.string.menu_engines_duel` 未参与增量编译。目标是在已有全量构建基础上，让后续资源生成、Kotlin 编译和 asset 部署全部脱离 Gradle。

## 0. 已确认事实与技术决策

### 0.1 JOOX Kuikly 对照结论

`JOOX_Android_2/module_features/joox_kuikly_source/kuikly-local-shell` 不是 Kotlin Multiplatform module，而是应用了 `com.android.library` 和 `org.jetbrains.kotlin.android` 的普通 Android Library。它把 `JXB` 和多个 feature 的 `src/commonMain/kotlin` 目录直接加入 Android `main.java.srcDirs`。

当前纳入编译的 Kotlin 源码中没有 `expect`、`actual` 或 `OptionalExpectation` 声明，因此 `commonMain` 只表示物理目录名，编译器会把文件当成普通 Kotlin/JVM 源码。

虽然 Gradle task 配置了：

```text
-Xmulti-platform
```

Jugg 读取到的 `kuikly-local-shell.kotlinFreeCompilerArgs` 实际为空。历史增量编译日志也证明，Jugg 直接调用 `kotlinc` 编译这些 `commonMain` 文件时没有携带 `-Xmulti-platform`，最终结果仍为 `OK`。

因此 JOOX 证明的是“Jugg 可以编译放在 commonMain 目录中的普通 JVM Kotlin”，不能证明 Jugg 已支持真正的 expect/actual linking。

### 0.2 KMP 编译器参数验证

Kotlin 2.1 实测结果：

| 输入和参数 | 结果 |
|---|---|
| common expect + Android actual，无 KMP 参数 | 失败：expect/actual 只能用于 multiplatform project |
| common expect + Android actual，仅 `-Xmulti-platform` | 失败：actual 找不到对应 expect |
| common expect + Android actual，增加 `-Xcommon-sources` | 成功 |
| 只有 common expect，两个参数都有 | 失败：没有平台 actual |

结论：

- 缺失 `-Xmulti-platform` 在出现 expect/actual 时是编译错误，不是警告。
- `-Xcommon-sources` 的文件必须同时出现在普通 source arguments 中。
- expect 和对应 Android actual 必须进入同一轮编译，参数不能代替输入文件闭包。
- Kotlin 1.7 暴露 `-Xexpect-actual-linker`，但 common/actual 同轮编译时不依赖它；Kotlin 2.1 已不再暴露该参数。
- `-Xexpect-actual-classes` 只控制 Beta 警告，不参与链接。

### 0.3 Compose Resources 已确认输出

Compose Resources 每次生成两类独立产物：

1. Kotlin：`Res.kt`、source-set accessor、expect/actual collectors。
2. Assets：`.cvr`、drawable、font、files 等 prepared resources。

新增 key 会同时改变 accessor 和 asset；仅修改字符串值通常只改变 `.cvr`，但值长度变化会改变后续记录 offset，对应 accessor 也可能变化。因此不能只解决 unresolved reference，也不能只 overlay asset。

### 0.4 已确定不采用的方向

- 不执行 Compose Gradle generation task。
- 不把 KMP Kotlin 编译整体委托给 Gradle。
- 不把 `composeResources` 交给 AAPT2。
- 不在 Jugg 中复制多版本 Kotlin accessor 模板。
- 不根据目录名看到 `commonMain` 就无条件启用 KMP。

### 0.5 已确定采用的方向

- 项目初始化阶段只读取一次 Gradle 已配置好的 Compose 元数据。
- 增量阶段不启动 Gradle。
- Jugg 实现稳定的 `.cvr version:0` 转换和 prepared resource 扫描。
- 隔离调用项目自己的 Compose 插件 JAR 生成版本匹配的 Kotlin。
- generated Kotlin 进入 Jugg Kotlin compiler，generated assets 进入现有 asset overlay。

## 1. 结论

方案可落地，首版不需要执行任何 Compose Gradle task，也不需要重新实现完整 Compose Resources 代码生成器。

推荐实现：

1. 全量构建或项目初始化阶段，通过现有 Gradle 项目信息读取记录 Compose Resources 配置和插件 JAR 路径。
2. 增量编译阶段由 Jugg 自己完成 XML 到 `.cvr` 的转换和普通资源复制。
3. 使用隔离 ClassLoader 加载项目实际使用的 `compose-gradle-plugin` JAR，反射调用其中不依赖 Gradle API 的纯代码生成函数。
4. 生成的 Kotlin 继续交给 Jugg `KotlinCompilerInvoker`，增加 KMP common/actual 编译参数。
5. 生成的 `.cvr`、图片、字体和文件继续交给现有 `AssetOverlayCompiler`。

增量链路：

```text
composeResources 变更
  -> Jugg ComposeResourceCompiler
       -> XML -> CVR / 非 values 文件复制
       -> 项目 Compose 插件 JAR 纯代码生成器 -> Res/accessor/collector Kotlin
       -> KotlinCompilerInvoker -> class -> D8/minify/deploy
       -> AssetOverlayCompiler -> composeResources/... assets
```

Gradle 只负责初次项目建模和全量构建，不进入每次增量编译。

## 2. 可行性验证

### 2.1 官方 Task 只是薄封装

Compose 1.7.3 和 1.9.3 的实现中：

- `XmlValuesConverterTask` 负责 XML 到 `.cvr`。
- `GenerateResClassTask` 调用 `getResFileSpec`。
- `GenerateResourceAccessorsTask` 调用 `getAccessorsSpecs`。
- collector Task 调用 `getExpectResourceCollectorsFileSpec` 和 `getActualResourceCollectorsFileSpec`。

真正的 Kotlin 代码生成函数位于 `GeneratedResClassSpecKt`，不使用 `Project`、Task graph 或 Gradle Service。

### 2.2 已验证隔离调用

将 `compose-gradle-plugin-1.7.3.jar` 放入只包含该 JAR 和 Kotlin stdlib 的隔离 ClassLoader，并明确不提供 `org.gradle.api.Project`：

- `GeneratedResClassSpecKt` 加载成功。
- `getResFileSpec` 调用成功。
- `getAccessorsSpecs` 调用成功。
- 生成的 `Res.kt` 和 `String0.commonMain.kt` 与 Gradle Task 生成结果逐字一致。

Compose 1.9.3 的相同调用也验证成功。

### 2.3 已验证直接 Kotlin 编译

将以下官方生成文件直接交给 Kotlin 2.1 compiler：

- `Res.kt`
- `String0.commonMain.kt`
- `ExpectResourceCollectors.kt`
- `ActualResourceCollectors.kt`

使用：

```text
-Xmulti-platform
-Xcommon-sources=<Res.kt,String0.commonMain.kt,ExpectResourceCollectors.kt>
```

编译成功并生成 `Res.class`、accessor class 和 `ActualResourceCollectorsKt.class`，过程中没有启动 Gradle。

### 2.4 Asset 路径可以直接复用

fixture 中以下三个文件 SHA-256 完全相同：

- prepared `.cvr`
- Compose 插件复制到 generated assets 的 `.cvr`
- AGP 合并后的 `.cvr`

说明 Android 阶段不需要额外转换，只需要按 accessor 中记录的相对路径复制资源。现有 `AssetOverlayCompiler` 已具备该能力。

### 2.5 性能可接受

隔离调用插件代码生成器时，内存中生成 1000 个字符串 accessor 的单次耗时约 12ms。实际主要耗时仍是 Kotlin 编译和 D8，不会引入 Gradle configuration 和 task graph 开销。

## 3. Compose Resources 最小协议

### 3.1 `.cvr` 格式

Compose 1.6.10 至 1.9.3 的 value 转换算法保持稳定：

```text
version:0
<type>|<key>|<Base64 content>
```

支持：

- `string`
- `string-array`
- `plurals`

记录按字符串排序。生成 accessor 时按 UTF-8 字节计算每条记录的 offset 和 size。

### 3.2 非 value 资源

以下文件不进入 AAPT2，只复制到 prepared resources：

- drawable
- font
- files
- 其他 Compose Resources 支持的原始文件

目录名中的 language、region、theme、density qualifier 由生成器写入 `ResourceItem`。

### 3.3 Android asset 路径

多模块资源模式通常使用：

```text
composeResources/<resource package>/<resource relative path>
```

旧单模块模式可能直接使用 resource relative path。Jugg 不自行根据版本猜测，而是在项目初始化时读取 Compose Task 已配置的 `packagingDir` / `relativeResourcePlacement`。

## 4. 版本兼容策略

不要复制 Compose 生成模板到 Jugg。模板已经在不同版本发生变化：

| Compose 版本 | 主要差异 |
|---|---|
| 1.6.x | 没有 expect/actual resource collectors |
| 1.7.x | 增加 expect/actual collectors |
| 1.8.x+ | 支持自定义 Res class name，生成函数签名增加 `resClassName` |
| 1.9.x | accessor 结构和每文件资源数量继续调整 |

Jugg 使用能力检测，不以版本字符串作为唯一依据：

1. 检测 `GeneratedResClassSpecKt` 是否存在。
2. 检测 `getAccessorsSpecs` 参数数量。
3. 检测 expect/actual collector 生成函数是否存在。
4. 检测 `ResourceItem` 构造函数和 `ResourceType` 枚举。
5. 未识别签名时停止 Compose Resources 增量编译，提示当前插件版本暂不支持，不生成可能不兼容的代码。

首版建议声明支持 Compose Multiplatform `1.6.10` 至 `1.9.3` 已验证接口范围。

## 5. 项目模型

在 `ModuleInfo` 增加可空的 `ComposeResourceInfo`：

```kotlin
data class ComposeResourceInfo(
    val pluginClasspath: List<File>,
    val packageName: String,
    val resClassName: String,
    val publicResClass: Boolean,
    val packagingDir: String?,
    val sourceSets: List<ComposeResourceSourceSetInfo>,
    val androidSourceSetName: String,
    val hasExpectActualCollectors: Boolean,
)

data class ComposeResourceSourceSetInfo(
    val name: String,
    val resourceDir: File,
    val isCommon: Boolean,
)
```

项目初始化阶段优先直接读取已经配置好的 Compose Task 属性：

- Task class 的 CodeSource：获取准确插件 JAR。
- `packageName`
- `resClassName`，旧版本默认 `Res`
- `makeAccessorsPublic`
- `sourceSetName`
- `originalResourcesDir` / `resDir`
- `packagingDir`
- actual collector 对应的 accessor source sets
- Android copy task 的 `relativeResourcePlacement`

这样可以覆盖 `customDirectory`、默认 package、单模块/多模块模式，不在 Jugg 中重复解释 Compose Gradle DSL。

## 6. 编译器设计

### 6.1 文件识别

新增 `CompileFile.Type.ComposeResource`。

`FileChangesHandler` 在 Android res/assets 判断前匹配 `ComposeResourceInfo.sourceSets.resourceDir`。Compose resource 不能进入 `ResourceOverlayCompiler` 或 AAPT2。

### 6.2 `ComposeResourceCompiler`

新增一个编译阶段，位于 asset 和 source 编译之前：

1. 按 module 和 source set 分组变更文件。
2. 重新生成受影响 source set 的 prepared resources。
3. 扫描完整 prepared resource 目录，构造全部 `ResourceItem`。
4. 调用项目 Compose 插件代码生成器。
5. 与上次 Jugg 生成结果比较，只输出变化的 Kotlin 和 asset 文件。
6. 将生成结果加入本轮普通 asset/source 输入。

不能只处理变化的 XML 节点。`.cvr` 中一条记录长度变化会改变后续记录的 offset，必须重新扫描该 `.cvr` 并更新所有受影响 accessor。

### 6.3 ClassLoader 隔离

新增 `ComposeResourceGeneratorBridge`：

- 每个 Compose 插件 JAR 建立可缓存的隔离 ClassLoader。
- ClassLoader 包含 Compose 插件 JAR和项目兼容的 Kotlin stdlib。
- 通过反射传递 Java `String`、`Map`、`List`、`Path` 等 bootstrap/JDK 类型。
- `ResourceType`、`ResourceItem` 和 KotlinPoet `FileSpec` 不跨 ClassLoader 强转。
- 对返回的 `FileSpec` 只反射调用 `writeTo` 或 `toString`。
- 项目模型更新或插件 JAR变化时销毁缓存。

不加载任何 Gradle API，也不实例化 Compose Gradle Task。

### 6.4 Kotlin 编译

Compose 1.7+ 生成 collector 时，本轮 Kotlin 输入至少包含：

- 变化的 common accessor 文件
- `Res.kt`
- common expect collector
- Android actual collector

参数：

```text
-Xmulti-platform
-Xcommon-sources=<本轮所有 common generated Kotlin files>
```

所有 `-Xcommon-sources` 文件也必须同时出现在普通 source file arguments 中。

Compose 1.6.x 没有 collector 时，根据生成文件实际内容决定是否需要 KMP 参数，不无条件添加。

### 6.5 Asset overlay

prepared resource 输出以最终 Android asset 根目录作为 `CompileFile.baseDir`，直接交给 `AssetOverlayCompiler`：

```text
prepared values/*.cvr
  -> composeResources/<package>/values/*.cvr
  -> AssetOverlayCompiler
```

不需要修改 AAPT2 编译器。

## 7. 删除场景

删除资源不必立即删除基础 APK 中的旧 asset：

- accessor 和 collector 更新后不会再引用旧资源。
- 新 `.cvr` overlay 会覆盖同路径旧文件。
- 整个资源文件被删除时，旧基础 asset 没有引用，不影响运行正确性。

必须正确处理生成 class：

- accessor 文件仍存在：用新 class 覆盖旧 class。
- 某个 chunk 整体消失：actual collector 不再调用其 collector function，旧 class 可留在基础 APK中但不可达。
- 删除后仍有源码引用：Kotlin 编译应正常报 unresolved reference。

因此删除场景不需要首版强制回退 Gradle。

## 8. 失败和回退边界

以下情况停止增量并提示重新全量构建：

- 项目刚新增或修改 Compose Gradle 插件配置。
- 没有缓存到 Compose 插件 JAR或必要 Task 元数据。
- 插件代码生成函数签名无法识别。
- `.cvr` format version 不是 Jugg 支持的版本。
- Android target/source-set 关系不完整。
- 生成代码编译失败且属于 Jugg 参数或生成器兼容错误。

用户资源 XML错误、重复 key、非法 qualifier 和源码 unresolved reference 应直接展示真实错误，不通过 Gradle重试。

## 9. TDD 执行计划

实现前先增加失败测试：

| 层级 | 测试文件 | 覆盖行为 |
|---|---|---|
| L1 | `ComposeValueResourceConverterTest.kt` | string、array、plural、escape、duplicate、offset、排序 |
| L1 | `ComposeResourceGeneratorBridgeTest.kt` | 1.6/1.7/1.8+ 签名检测和生成结果 |
| L1 | `ComposeResourceScannerTest.kt` | qualifier、资源类型、非法目录、资源名转换 |
| L1 | `GradleProjectInfoReaderKmpComposeTest.kt` | 从 Task 属性读取插件 JAR、package、source set、packagingDir |
| L2 | 扩展现有 compiler 协作测试 | ComposeResource 不进 AAPT2，生成 Kotlin 和 asset 分别进入正确阶段 |
| L3 | `KmpComposeFlowReproTest` | 新增 key、修改 value、多语言、删除 key 后真实编译部署 |

L3 至少验证：

1. 新增 `menu_engines_duel` 并在源码引用，Jugg 编译成功。
2. 只修改字符串值，不修改源码，运行时读取新值。
3. 新增语言 qualifier 后切换语言读取正确值。
4. 删除 key 后仍引用，显示 unresolved reference。
5. 编译日志中没有 Gradle command 或 Gradle task execution。

## 10. 修改清单

| 区域 | 修改内容 |
|---|---|
| `JuggProjectInfo.kt` | 增加 ComposeResourceInfo/source-set 元数据 |
| `GradleProjectInfoReader.kt` | 读取 Compose Task 配置和插件 CodeSource |
| `ProjectInfoSerializerInGradle.kt` | 新字段序列化 |
| `readProjectInfo.gradle.kts` | 通过生成任务同步 |
| `FileChangesHandler.kt` | 识别 Compose Resources |
| `ICompiler.kt` / `CompilerExt.kt` | 新增 ComposeResource 类型分支 |
| `JuggCompiler.kt` | 在 asset/source 前加入生成阶段并传递生成输入 |
| `ComposeResourceCompiler.kt` | 新增；编排转换、生成、diff |
| `ComposeValueResourceConverter.kt` | 新增；实现 format version 0 |
| `ComposeResourceScanner.kt` | 新增；prepared resources 到 ResourceItem 描述 |
| `ComposeResourceGeneratorBridge.kt` | 新增；隔离调用项目插件代码生成函数 |
| `KotlinCompilerInvoker.kt` | 支持 common sources 和 expect/actual 同轮编译 |
| `AssetOverlayCompiler.kt` | 原则上无需修改，仅验证 generated baseDir |
| `android_demo_project/kmpCompose` | 完善多语言、图片和删除场景 fixture |

## 11. 风险评估

| 风险 | 等级 | 控制方式 |
|---|---|---|
| 调用 Compose internal JVM API | 中 | 能力检测、隔离 ClassLoader、已验证版本白名单 |
| `.cvr` 格式将来升级 | 中 | 检查 `version:`，未知版本拒绝增量 |
| Kotlin/Compose 类加载冲突 | 低到中 | child ClassLoader + 项目 Kotlin stdlib，不跨 loader 强转 |
| 生成模板版本差异 | 低 | 直接调用项目自己的插件 JAR，不在 Jugg 复制模板 |
| 大型资源集性能 | 低 | 生成 1000 accessor 约 12ms，使用 hash diff 减少 Kotlin 输入 |
| 多模块/动态特性 asset 归属 | 中 | 复用 moduleBelongsApkMap 和 AssetOverlayCompiler 多 APK 分发 |

Compose Multiplatform 项目采用 Apache License 2.0。推荐动态调用项目已有插件 JAR，不将其源码复制进 Jugg；Jugg 自己实现 `.cvr` version 0 时仍需在实现评审中确认是否加入来源说明。

## 12. 验收标准

- Compose Resources 增量编译期间不启动 Gradle。
- 新增资源 key 后生成 accessor 并完成源码编译。
- 修改 value 后仅生成和部署必要 class/asset。
- generated Kotlin 与项目 Compose 插件版本一致。
- `.cvr` 和非 value resources 使用正确 Android asset 路径。
- ComposeResource 永远不进入 AAPT2。
- Kotlin 1.7/2.x 以及 Compose 1.6.10、1.7.3、1.8.2、1.9.3 的已知生成器签名有明确适配。
- 普通 Android Compose、Android resources 和 JOOX Kuikly 编译链路不回归。

## 13. 推荐实施顺序

1. 增加 fixture 和 L1/L3 失败测试。
2. 项目模型读取 Compose Task 元数据和插件 JAR。
3. 实现 `.cvr` converter、prepared resource scanner 和官方生成器 bridge。
4. 接入 `JuggCompiler`，输出 generated Kotlin 和 assets。
5. 实现 KMP generated source 编译参数。
6. 完成 asset overlay 和真实运行验证。
7. 更新 `02_compile_source.md`、`02_compile_resource.md`、`02_compile_core.md`、`04_engineering_project.md` 和 `98_code_map.md`。

## 14. 下一会话交接入口

### 14.1 无需重复调研的结论

下一会话可以直接以以下结论作为前提：

1. 脱离 Gradle 生成 Compose Resources 已完成技术验证。
2. 项目 Compose 插件 JAR 中的纯生成函数可以在没有 Gradle API 的 ClassLoader 中调用。
3. 生成结果可以用 Jugg 当前使用方式的 Kotlin compiler 直接编译。
4. Android 资源产物只需要保持正确相对路径并进入 `AssetOverlayCompiler`。
5. 首版版本适配采用能力检测，已调研接口范围为 Compose 1.6.10、1.7.3、1.8.2、1.9.3。

### 14.2 实现前需要最终确定的细节

这些是设计细节，不是可行性阻塞：

- `ComposeResourceInfo` 的最小持久化字段和序列化版本。
- Compose Task 属性读取使用明确 class name 还是结构化反射。
- `ComposeResourceCompiler` 直接加入 `JuggCompiler`，还是扩展 compiler stage 的输入传递能力。
- generated 文件缓存和 hash diff 的目录位置。
- Kotlin 编译时 common generated sources 的最小闭包策略。
- 插件 ClassLoader 缓存的生命周期和 Kotlin stdlib 选择。
- 首版是否同时支持自定义 `nameOfResClass` 和 `customDirectory`；技术上均可支持。

### 14.3 推荐从这里开始实施

严格按 TDD 顺序：

1. 先补 `ComposeValueResourceConverterTest`、`ComposeResourceScannerTest` 和 `ComposeResourceGeneratorBridgeTest`。
2. 在 `android_demo_project/kmpCompose` 固化官方 1.7.3 golden outputs。
3. 再讨论并确定 `ComposeResourceInfo` 数据结构。
4. 完成项目模型测试后实现 Gradle reader。
5. 最后接入 `JuggCompiler` 和 L3 Flow，避免项目模型、生成器和部署链路同时修改。

实现前应重新读取：

- `docs/ai_knowledge/06_testing.md`
- `docs/ai_knowledge/02_compile_source.md`
- `docs/ai_knowledge/02_compile_resource.md`
- `docs/ai_knowledge/02_compile_core.md`
- `docs/ai_knowledge/04_engineering_project.md`
- `docs/ai_knowledge/98_code_map.md`
