# Compose Multiplatform 1.7.3 资源增量编译方案

> 背景：Jugg report `b5731090` 中，新增 Compose Resources 字符串后，资源文件未被识别，`Res.string.menu_engines_duel` accessor 也没有重新生成。本方案只解决 Compose Resources 增量编译，不扩展通用 KMP Kotlin 源码编译能力。

## 1. 目标与范围

### 1.1 目标

在已有 Gradle 全量构建基础上，Compose Resources 发生变化时由 Jugg 完成以下流程，增量阶段不执行 Compose Gradle task：

1. 识别默认目录和 `customDirectory` 中的 Compose 资源变化。
2. 将 values XML 转为 `.cvr version:0`，复制非 values 资源。
3. 调用项目使用的 Compose 插件生成版本一致的 `Res.kt`、accessor 和 collector。
4. 编译 generated Kotlin，并进入现有 class、D8、minify 和 deploy 链路。
5. 将 prepared resources 作为 Android assets 交给现有 `AssetOverlayCompiler`。

### 1.2 首版支持范围

- Compose Multiplatform：仅 `1.7.3`。
- Kotlin：`2.1.x`。
- 资源目录：默认 `composeResources` 和 `compose.resources.customDirectory(...)`。
- values：`string`、`string-array`、`plurals`。
- 非 values：`drawable`、`font`、`files`。
- qualifier：Compose `1.7.3` 支持的 language、region、theme、density。
- 操作：新增和修改。
- 配置：`packageOfResClass`、`publicResClass`。
- 目标：只生成和部署 Android target 产物。

### 1.3 明确不支持

- 普通 `commonMain` / `androidMain` KMP 业务源码的通用 expect/actual 增量编译。
- Compose Multiplatform `1.7.3` 之外的版本。
- Compose 资源删除。删除后需要执行一次 Gradle 全量构建。
- 在 Jugg 中解释或重建 KMP source-set `dependsOn` 图。
- 执行 Compose Gradle generation task。
- 将 `composeResources` 交给 AAPT2。
- 复制 Compose Kotlin 生成模板到 Jugg。

## 2. 已验证事实

### 2.1 当前存在两个独立问题

复现工程暴露了两个缺口：

1. Compose Resources 文件没有进入增量编译，新增 key 后 accessor 保持旧状态。
2. 普通 KMP common source 单独编译时缺少 expect/actual 输入闭包和参数。

本方案只解决第一个问题。第二个问题不属于本次实现范围。

### 2.2 Compose Resources 产物

Compose `1.7.3` 生成两类独立产物：

- Kotlin：`Res.kt`、source-set accessor、expect resource collector、Android actual resource collector。
- Assets：`.cvr`、drawable、font、files 等 prepared resources。

新增 key 会同时改变 asset 和 accessor。修改 value 一定改变 `.cvr`；内容长度变化还可能改变后续记录 offset，进而改变 accessor。

### 2.3 官方代码生成函数可隔离调用

`compose-gradle-plugin-1.7.3.jar` 中以下函数不依赖 Gradle API，可以在隔离 ClassLoader 中调用：

```text
GeneratedResClassSpecKt.getResFileSpec
GeneratedResClassSpecKt.getAccessorsSpecs
GeneratedResClassSpecKt.getExpectResourceCollectorsFileSpec
GeneratedResClassSpecKt.getActualResourceCollectorsFileSpec
```

隔离环境只提供插件 JAR和兼容 Kotlin stdlib 时，生成的 `Res.kt`、accessor 和 collector 与 Gradle task 输出一致。

### 2.4 资源准备逻辑不能直接隔离调用

Compose `1.7.3` 中以下实现是 Gradle Task 的 private 方法：

```text
XmlValuesConverterTask.convert
XmlValuesConverterTask.getItemRecord
GenerateResourceAccessorsTask.fileToResourceItems
GenerateResourceAccessorsTask.getValueResourceItems
```

相关 Task 继承 Gradle `IdeaImportTask`，实例化需要 Gradle services。因此首版由 Jugg 实现 XML 转换、prepared resource 扫描和文件复制，不实例化 Compose Gradle Task。

### 2.5 generated collector 需要 KMP 参数

Compose `1.7.3` 生成的 collector 包含 `expect val` 和 `actual val`。Kotlin `2.1.x` 下必须将 expect 与 actual 放入同一轮编译，并使用：

```text
-Xmulti-platform
-Xcommon-sources=<common generated Kotlin files>
```

所有 `-Xcommon-sources` 文件也必须同时出现在普通 source arguments 中。该要求只作用于 Compose generated Kotlin 编译，不扩展普通 KMP 源码增量能力。

## 3. 总体架构

新增独立 `ComposeResourceCompiler` 阶段，位于 asset、Android resource 和普通 source 编译之前：

```text
changed ComposeResource files
  -> ComposeResourceCompiler
       -> values XML -> .cvr
       -> drawable/font/files -> prepared assets
       -> 扫描当前完整资源集合
       -> Compose 1.7.3 官方生成函数 -> generated Kotlin
       -> KotlinCompilerInvoker -> generated classes
  -> AssetOverlayCompiler：prepared assets
  -> ResourceOverlayCompiler：普通 Android res/manifest
  -> SourceCompiler：用户源码 + generated classes
  -> D8/minify/deploy
```

不通过 Custom Compiler SPI 接入，也不把资源生成逻辑放入 `SourceCompiler`。

## 4. 项目元数据

### 4.1 数据模型

在 `ModuleInfo` 增加可空的 Compose Resources 配置：

```kotlin
data class ComposeResourceInfo(
    val generatorClasspath: List<File>,
    val packageName: String,
    val publicResClass: Boolean,
    val resourceDirectories: List<ComposeResourceDirectory>,
    val assetRelativePath: String,
)

data class ComposeResourceDirectory(
    val sourceSetName: String,
    val directory: File,
)
```

`sourceSetName` 只用于关联现有 source-set 所有权和调用 Compose 生成器，不表示 Jugg 新增 KMP source-set 图。Compose `1.7.3` 的资源类名固定为 `Res`，首版不增加 `resClassName` 字段。

### 4.2 Gradle 读取方式

`GradleProjectInfoReader` 只适配 Compose `1.7.3` 已验证的明确 Task class 和属性：

- `XmlValuesConverterTask`
- `GenerateResClassTask`
- `GenerateResourceAccessorsTask`
- `GenerateExpectResourceCollectorsTask`
- `GenerateActualResourceCollectorsTask`
- Android resource packaging/copy task

- 从 Task class `CodeSource` 获取准确插件 JAR。
- 从现有项目 Kotlin classpath 获取匹配的 Kotlin stdlib，与插件 JAR共同组成 `generatorClasspath`。
- 读取 `packageOfResClass` 和 `publicResClass`。
- 读取各 source set 默认目录或 `customDirectory` 解析后的真实目录。
- 读取 Android packaging task 的最终相对 asset placement。

不按属性形状猜测其他版本，不提供跨版本 fallback。任何必要字段缺失时不生成不完整的 `ComposeResourceInfo`。

### 4.3 序列化与合并

新增字段需要同步：

- `JuggProjectInfoSerialize`
- `ProjectInfoSerializerInGradle`
- `JuggProjectInfoMerger`
- `CmdLineContextManager`
- `LibrariesBackupHelper`

IDE/Gradle 已有 KMP source-set 读取和 module 合并保持不变。

## 5. 文件识别

新增 `CompileFile.Type.ComposeResource`。

`FileChangesHandler` 使用 `ComposeResourceInfo.resourceDirectories` 匹配变化文件，并将匹配到的资源根目录作为 `ChangedFile.baseDir`。匹配顺序必须早于 Android `Resource` 和 `Asset`，保证 Compose Resources 永远不进入 AAPT2。

现有 `ChangedFile` / `CompileFile` 的 `file`、`baseDir`、`module` 已足够表达输入，不使用 `extraInfo` 增加非标准标记。

首版沿用现有缺失文件处理，删除事件不进入 Compose 增量链路。

## 6. `ComposeResourceCompiler`

### 6.1 输入分组

按现有 module 和 `CompileFile.baseDir` 分组。`baseDir` 对应 `ComposeResourceDirectory.directory`，无需新增 source-set 关系模型。

### 6.2 changed-file 驱动

传入的 changed `CompileFile` 决定本轮实际生成和部署的 asset：

- values XML：转换该 XML 的完整内容，输出对应 `.cvr`。
- drawable/font/files：复制变化文件并保持 Compose `1.7.3` Android asset 相对路径。

为了调用 `getAccessorsSpecs`，编译器会读取所属资源目录的完整当前资源集合，构造完整 `ResourceItem` map。完整扫描只提供代码生成上下文，不会把未变化资源作为 asset 输入。

首版不增加持久化 generated cache，也不做 generated Kotlin 或 prepared asset 内容 diff。

### 6.3 XML 到 `.cvr`

使用结构化 XML parser 实现 Compose `1.7.3` 的 `.cvr version:0`：

```text
version:0
<type>|<key>|<Base64 content>
```

必须覆盖：

- `string`
- `string-array`
- `plurals`
- XML escape 和 Compose 特殊字符处理
- UTF-8 offset/size
- 记录排序
- duplicate key
- 非法 XML和非法资源名

输出必须与 Compose `1.7.3` Gradle task golden file 逐字节一致。

### 6.4 prepared resource 扫描

Jugg 扫描当前资源目录并构造 Compose ClassLoader 内的 `ResourceType` 和 `ResourceItem`：

- `DRAWABLE`
- `STRING`
- `STRING_ARRAY`
- `PLURAL_STRING`
- `FONT`

`files` 只复制，不生成 accessor。qualifier、资源名、path、offset 和 size 必须与 Compose `1.7.3` Gradle task 一致。

### 6.5 官方生成器桥接

`ComposeResourceGeneratorBridge`：

- 使用 `generatorClasspath` 创建隔离 `URLClassLoader`。
- parent 只暴露 JDK/platform 类，不暴露 Jugg 或 Gradle API。
- 只适配 Compose `1.7.3` 固定类名、构造函数和方法签名。
- `ResourceType`、`ResourceItem`、KotlinPoet `FileSpec` 不跨 ClassLoader 强转。
- 通过反射构造输入，并通过 `FileSpec.writeTo` 或 `toString` 输出 Kotlin。
- ClassLoader 按 classpath 缓存，在 compile context 更新或 compiler dispose 时关闭。

### 6.6 generated Kotlin 编译

generated Kotlin 由 `ComposeResourceCompiler` 内部调用 `KotlinCompilerInvoker` 编译，不通过 `CompileFile.extraInfo` 让普通 `SourceCompiler` 猜测 common source。

`KotlinCompilerInvoker.Options` 增加明确的：

```kotlin
val commonSourceFiles: List<File> = emptyList()
```

`ComposeResourceCompiler` 根据本轮官方生成函数的输出直接提供 common 文件列表：

- `Res.kt`
- common source-set accessors
- `ExpectResourceCollectors.kt`

Android `ActualResourceCollectors.kt` 只进入普通 source arguments。当 `commonSourceFiles` 非空时，invoker 增加 `-Xmulti-platform` 和 `-Xcommon-sources`。

generated classes 写入现有 Kotlin class output，并作为 `Class` 输入继续进入 `SourceCompiler` 的 D8/minify/deploy 阶段。用户本轮变化的源码随后可以从该 class output 解析新增 accessor。

## 7. Asset overlay

prepared resource 使用 `ComposeResourceInfo.assetRelativePath` 形成最终 Android asset 路径，并以正确根目录作为 `CompileFile.baseDir` 交给 `AssetOverlayCompiler`：

```text
values/strings.commonMain.cvr
  -> composeResources/<resource package>/values/strings.commonMain.cvr
  -> AssetOverlayCompiler
```

drawable、font、files 使用相同 placement 规则。普通 Android assets 和 native libraries 继续沿用原链路。AAPT2 不需要修改。

## 8. 失败与回退

### 8.1 用户资源错误

XML 格式错误、重复 key、非法资源名和非法 qualifier直接绑定原始 `ComposeResource CompileFile`，使用 `warn` 展示真实错误。不做内部重试，也不触发 Gradle fallback。

### 8.2 增量能力不可用

以下情况停止 Compose 增量编译并交给现有 Gradle fallback：

- Compose 版本不是 `1.7.3`。
- 必需 Task 元数据缺失。
- 插件 JAR或 Kotlin stdlib 不存在。
- 官方类、构造函数或方法签名不匹配。
- Android asset placement 缺失。
- `customDirectory` 无法解析。

不尝试按其他版本或目录规则猜测。

### 8.3 内部失败

反射异常、generated Kotlin 编译失败、asset 写入失败时记录详细 `debug` 日志，用户侧打印一条 `warn`，保留 Kotlin compiler 原始诊断，并允许现有 Gradle fallback。禁止使用 `JuggLogger.error`。

### 8.4 取消

取消时立即停止后续阶段、清理本轮临时生成目录、返回 cancel result，不打印失败 `warn`，不触发 Gradle fallback。

## 9. Android demo testcase

扩展 `android_demo_project/kmpCompose`，通过 `-PenableKmpComposeFixture=true` 条件启用，不影响普通 demo 构建。

建议 fixture：

```text
kmpCompose/
├── src/commonMain/composeResources/
│   ├── values/
│   ├── values-<language-region>/
│   ├── drawable/
│   ├── drawable-<density>/
│   ├── font/
│   └── files/
├── src/androidMain/customComposeResources/
├── src/commonMain/kotlin/.../KmpComposeResourceCase.kt
└── src/androidMain/kotlin/.../KmpComposeAndroidResourceCase.kt
```

- `commonMain` 使用默认 `composeResources`。
- `androidMain` 使用 `compose.resources.customDirectory(...)`。
- `KmpComposeResourceCase.kt` 真实引用 string、array、plurals、drawable、font 和 files。
- `KmpComposeAndroidResourceCase.kt` 真实引用 customDirectory 中的 Android resource。

testcase 必须消费 generated accessor，不能只断言生成文件存在。

## 10. TDD 执行清单

生产代码修改前先增加失败测试：

| 层级 | 测试文件 | 覆盖行为 |
|---|---|---|
| L1 | `ComposeValueResourceConverterTest.kt` | string、array、plural、escape、排序、UTF-8 offset、duplicate、非法 XML |
| L1 | `ComposeResourceScannerTest.kt` | drawable/font/files、qualifier、资源名、offset/size |
| L1 | `ComposeResourceGeneratorBridgeTest.kt` | 使用真实 `1.7.3` 插件 JAR，与 Gradle golden Kotlin 一致 |
| L1 | 现有 project info serializer 测试 | `ComposeResourceInfo` 序列化往返 |
| L2 | `idea/src/test/.../project/FileChangesHandlerTest.kt` | 默认目录/customDirectory 分类，Compose resource 不进 AAPT2 |
| L2 | `idea/src/test/.../manager/JuggCompilerTest.kt` 中 `KmpComposeFlowReproTest` | 真实 demo 全量基线后的资源生成、Kotlin、D8 和 asset staging |
| L3 | `idea/src/test/.../manager/TopLevelFlowTest.kt` 中 `KmpComposeDeployFlowTest` | 真机 compile、deploy、run、APK ownership 与运行时 accessor 消费 |

L2 + L3 场景合计至少覆盖：

1. 新增 string key 并修改 testcase 引用，Jugg 编译成功。
2. 修改 string value，生成并 staging 正确 `.cvr`。
3. string-array 和 plurals accessor 编译成功。
4. drawable、font、files 使用正确 asset 路径。
5. language/region/theme/density qualifier生成正确 `ResourceItem`。
6. `customDirectory` 资源能够被识别和编译。
7. generated expect/actual collector 使用正确参数编译。
8. 增量日志中没有 Gradle command 或 Compose Gradle task execution。
9. 普通 Android resource 仍进入 AAPT2，Compose resource 永远不进入 AAPT2。

测试运行必须使用 `--tests` 过滤，禁止无过滤的全量 `:main:test` / `:idea:test`。完成后执行 `./gradlew :idea:compileKotlin`。

## 11. 修改清单

| 区域 | 修改内容 |
|---|---|
| `JuggProjectInfo.kt` | 增加可空 `ComposeResourceInfo` 和目录配置 |
| project info 序列化/合并链 | 保留 Compose 配置 |
| `GradleProjectInfoReader.kt` | 读取 Compose `1.7.3` 明确 Task 属性和插件 CodeSource |
| `FileChangesHandler.kt` | 识别 `ComposeResource`，保留资源根 `baseDir` |
| `ICompiler.kt` / `CompilerExt.kt` | 只增加 `ComposeResource` 类型及标准类型映射，不使用 `extraInfo` 标记 |
| `JuggCompiler.kt` | 增加独立 Compose 阶段并传递 generated class/asset |
| `ComposeResourceCompiler.kt` | 编排转换、扫描、官方生成、generated Kotlin 编译 |
| `ComposeValueResourceConverter.kt` | Compose `1.7.3` `.cvr version:0` |
| `ComposeResourceScanner.kt` | 资源类型、qualifier、path、offset/size |
| `ComposeResourceGeneratorBridge.kt` | 隔离调用官方生成函数 |
| `KotlinCompilerInvoker.kt` | typed `commonSourceFiles` 参数 |
| `AssetOverlayCompiler.kt` | 原则上不修改，只验证 generated asset baseDir 和 APK 分流 |
| `android_demo_project/kmpCompose` | 完整资源 testcase、customDirectory 和 golden outputs |

## 12. 实施顺序

1. 完善 Android demo testcase 和 Compose `1.7.3` golden outputs。
2. 写 converter、scanner、generator bridge 的 L1 失败测试。
3. 写 project info 序列化和读取失败测试。
4. 写 FileChangesHandler 分类失败测试。
5. 将现有 KMP Compose 复现测试改为预期成功的 L2 编译协作场景，并增加真机 L3 部署运行场景。
6. 实现项目元数据读取与序列化。
7. 实现 converter、scanner 和 generator bridge。
8. 实现 `ComposeResourceCompiler` 与 generated Kotlin 编译。
9. 接入 `JuggCompiler` 和 asset overlay。
10. 执行定向测试、L3 Flow 和编译验证。
11. 同步 `02_compile_core.md`、`02_compile_source.md`、`02_compile_resource.md`、`04_engineering_project.md` 和 `98_code_map.md`。

## 13. 验收标准

- Compose Resources 增量编译期间不启动 Gradle。
- 默认目录和 `customDirectory` 都能被识别。
- 新增资源 key 后生成 accessor，用户源码可以同轮引用。
- 修改 value 后部署正确 `.cvr`。
- string、string-array、plurals、drawable、font、files 全部覆盖。
- language、region、theme、density qualifier路径和生成代码正确。
- generated Kotlin 与 Compose `1.7.3` Gradle task 输出一致。
- generated expect/actual collector 编译成功。
- Compose Resources 使用正确 Android asset 路径。
- Compose Resources 永远不进入 AAPT2。
- 普通 Android resource、asset、Kotlin、Compose 和既有 KMP 工程不回归。
- 非 `1.7.3` Compose 版本明确拒绝增量，不生成猜测产物。

## 14. License

Compose Multiplatform 使用 Apache License 2.0。本方案动态调用项目已有插件 JAR，不复制其 Kotlin 生成模板。Jugg 实现 `.cvr version:0` 转换和扫描逻辑时，需要在实现评审中确认是否增加来源说明。
