# External Build 输入目录过滤规则方案

创建日期：2026-09-16。状态：待实施。

## 1. 结论

为 `ExternalBuildInfo.inputDirs` 增加目录级文件类型规则，避免 C++ include root、CMake 配置根或 Flutter 工程根覆盖无关文件后，把日志、PID、daemon 缓存等内容误判为 `ExternalBuildSource`。

本方案采用以下约束：

1. 不引入 `inputFiles`，继续使用目录级输入模型。
2. 输入目录只按“规范化路径相同且规则集合相同”去重。
3. 不再进行父目录覆盖子目录的压缩。
4. 不合并同目录下不同的规则集合。
5. 不提供无来源约束的通用 `AllFiles` 规则；Flutter 任意扩展名输入使用 `FlutterAsset`，Native 任意扩展名输入只使用 metadata 确认的 `NativeDirectory`。
6. 不兼容旧 `inputDirs: List<File>` 快照。旧快照读取失败后复用现有 project info 不可用路径，降级一次完整 Gradle 并生成新格式。
7. 不提升 project info schema 版本；新旧 `inputDirs` 元素形态由读取边界显式校验。

该模型优先解决真实误判，同时保持结构简单，不引入 glob、正则、黑名单或文件级输入集合。

## 2. 问题证据

Jugg report `bd7e6ffe` 中，以下文件被识别为 `mp.dtmp` 的 external build source：

```text
<worktree>/DTMP/tools/daemon/tmp/.wapt_dtmp_pid
<worktree>/DTMP/tools/daemon/logs/wapt-dtmp-daemon-2026-09-16.log
```

后续执行了 C++ Gradle task，收集 `libdtmp.so`，并进入 APK 更新与重签名流程。两个文件既不是 C/C++ 源码、头文件，也不是 CMake 配置输入，属于目录递归匹配造成的误触发。

当前实现把以下不同语义的目录放入同一个 `List<File>`：

- externalNativeBuild 配置根；
- CMake File API / `android_gradle_build.json` 返回的源码父目录；
- native include root；
- Flutter 工程根与 local package 根；
- Flutter task 暴露的 asset、font、shader、ARB 等输入目录。

随后 `compactInputDirs()` 优先保留浅层祖先目录，而 `resolveExternalBuilds()` 将目录下任意非排除文件视为 external build source。目录来源语义在压缩后丢失，是本次误判的 behavior owner。

关键实现：

- [JuggProjectInfo.kt](../../../main/src/main/java/com/sickworm/intellij/jugg/project/data/JuggProjectInfo.kt)
- [GradleProjectInfoReader.kt](../../../main/src/main/java/com/sickworm/intellij/jugg/gradle/script/GradleProjectInfoReader.kt)
- [ExternalBuildSource.kt](../../../main/src/main/java/com/sickworm/intellij/jugg/compiler/external/ExternalBuildSource.kt)
- [FileChangesHandler.kt](../../../main/src/main/java/com/sickworm/intellij/jugg/project/FileChangesHandler.kt)

## 3. 数据模型

新增目录描述：

```kotlin
/** One recursive external build input root and the file kinds accepted below it. */
data class ExternalBuildInputDir(
    val directory: File,
    val filterRules: Set<ExternalBuildInputFilterRule>,
)

/** File kinds supported by recursive external build input roots. */
enum class ExternalBuildInputFilterRule {
    Dart,
    FlutterAsset,
    CppSource,
    CppHeader,
    NativeDirectory,
}
```

`ExternalBuildInfo` 修改为：

```kotlin
val inputDirs: List<ExternalBuildInputDir>
```

约束：

- `filterRules` 不允许为空。
- 多条规则之间是 OR 关系。
- `configFiles` 保持精确文件匹配，不转换为目录规则。
- `excludedDirs` 保持最高优先级，命中后不再检查配置文件和目录规则。

第一版不增加规则接口、sealed class、用户配置或可扩展表达式。规则集合只表达当前已确认的 C++ 与 Flutter 输入类型。

## 4. 规则语义

### 4.1 Dart

匹配 `.dart` 文件，扩展名比较忽略大小写。用于 Flutter 工程源码根和可识别的 local path package 源码根。

### 4.2 FlutterAsset

匹配目录下任意普通文件，但只能由 `pubspec.yaml` / `l10n.yaml` 明确声明的资源路径，或 Flutter Gradle task 明确暴露的非 Dart 输入文件或输入目录产生。

典型内容包括：

```text
png, webp, json, yaml, arb, ttf, otf, frag, 自定义二进制资源
```

该规则不能用于 Flutter package 根，也不能作为元数据读取失败时的通用降级。`pubspec.yaml`、`pubspec.lock` 和 `l10n.yaml` 必须先识别为 `configFiles`；task 暴露的根级非 Dart 文件、父目录等于 package 根的单文件输入，以及指向 package 根的目录声明均不能生成根目录 `FlutterAsset`。Flutter package 根始终只保留 `Dart`。

只解析 `flutter.assets` 中的目录声明，以及 `l10n.yaml` 的 `arb-dir`。单文件 asset、font 和 shader 继续依赖 Flutter task inputs；新增这些精确输入时 `pubspec.yaml` 自身会作为配置文件触发 external build。主 Flutter 工程与从 task inputs 识别出的 local path package 使用相同规则。

YAML 读取复用已验证的最小字段解析，不实现通用 YAML 解析器，也不依赖 Gradle 内部可能随版本变化的 YAML 库。解析支持目标字段所需的缩进、引号、注释和 map `path` 形式；复杂 YAML 无法识别时只舍弃当前配置文件提供的目录增强，保留 task inputs、Dart 和其他 package 的有效结果，并聚合打印一条 debug 日志。

声明路径以所属 package 根为基准规范化。目录通过尾部路径分隔符或当前文件系统中的目录状态识别；只接受规范化后仍位于 package 根内、且不位于 `excludedDirs` 的路径，拒绝 `..` 越界、绝对路径和经过符号链接目录的声明。这样空的已声明目录仍能进入监控，而配置不能把任意工程外目录扩大为 `FlutterAsset`。

`FlutterAsset` 继续递归匹配声明目录或 task input 父目录下的任意普通文件。该策略可能把 Flutter 实际未声明的嵌套文件识别为输入，是为了避免旧 depfile 和首次新增目录文件造成漏检而接受的已知误触发边界，本次不增加直接子文件或分辨率变体模型。

### 4.3 CppSource

第一版支持：

```text
c, cc, cpp, cxx, c++, m, mm, s, asm, cppm, ixx, cu
```

扩展名比较忽略大小写，因此 `.C`、`.S` 等形式无需单独建模。

### 4.4 CppHeader

第一版支持：

```text
h, hh, hpp, hxx, inc, inl, ipp, tpp
```

只按以上明确后缀匹配。无扩展名文件和隐藏文件均不按 Header 处理，因此 `.wapt_dtmp_pid`、`LICENSE`、`OWNERS` 等文件不会命中。

### 4.5 NativeDirectory

匹配目录下任意非隐藏普通文件，用于 metadata 已明确确认的具体 Native source 目录。`.proto`、`.fbs`、`.def`、`.a`、`.so`、`.metal`、`.rs`、`.zig` 等非标准输入只有位于该目录时才会命中，不为这些类型维护额外后缀白名单。

`NativeDirectory` 必须满足以下来源约束：

- metadata 返回的是非 Header source，且其父目录严格位于 externalNativeBuild 配置根之下；或
- metadata 返回的是非 Header source，且其父目录位于配置根之外，由 metadata 明确返回。

source 父目录与 externalNativeBuild 配置根相同时不能生成 `NativeDirectory`，继续使用配置根的 `CppSource + CppHeader`。metadata `sources` 中显式列出的 Header 只为父目录生成 `CppHeader`；include root 也不能生成 `NativeDirectory`，只使用 `CppHeader`。如果同一目录还包含 metadata 确认的非 Header source，才允许由该 source 额外生成 `NativeDirectory`。

匹配时只接受当前存在的普通文件。文件名或相对路径任一目录段以 `.` 开头时忽略；符号链接文件不作为输入，目录事件递归时不跟随符号链接目录。未知 metadata source 如果因位于配置根本身而无法生成 `NativeDirectory`，直接忽略，不设置 `unsupportedReason`，并与其他被忽略类型按 external build 聚合为一条 debug 日志。

该日志使用 Gradle debug logger，每个 external build 每次采集最多一条，包含被忽略输入的规范化路径，不写入普通用户输出，也不保存到 project info。日志失败属于辅助能力失败，不影响其余 metadata 结果。

## 5. 输入采集

### 5.1 C++

| 来源 | 生成规则 |
|---|---|
| externalNativeBuild 配置根 | `CppSource + CppHeader` |
| metadata 非 Header source 父目录严格位于配置根之下 | `NativeDirectory` |
| metadata 非 Header source 父目录位于配置根之外 | `NativeDirectory` |
| metadata Header source 的父目录 | `CppHeader` |
| metadata source 父目录等于配置根 | 不新增规则，保留配置根白名单 |
| metadata 中 include root | `CppHeader` |
| `CMakeLists.txt`、递归发现的 `.cmake` | `configFiles` 精确匹配 |
| `Android.mk`、`Application.mk` | `configFiles` 精确匹配 |
| Android SDK、NDK、CMake toolchain、Gradle cache | `excludedDirs` |
| `.cxx`、`.externalNativeBuild`、module build、staging directory | `excludedDirs` |

配置根只接受低风险 C/C++ 源码和 Header 后缀，负责发现尚未进入 metadata 的常规新文件。具体非 Header source 父目录由 metadata 确认后允许任意非隐藏普通文件，因此能够覆盖共址 Header 和非标准生成输入。显式 Header source 和 include root 保持 Header 后缀过滤，避免 NDK/system include 或宽共享目录扩大为任意文件输入。

这里的 metadata 指 native 工具链已生成的结构化构建信息，而不是 Jugg 自行解析 CMake/Make 脚本：

- CMake File API：从 `.cxx` 等 staging 目录下的 `api/v1/reply/codemodel-v2*.json` 及 target JSON 读取 `sources[].path` 和 `compileGroups[].includes[].path`；
- ndk-build：从 AGP 生成的 `android_gradle_build.json` 读取 `files[].src` 和编译参数中的 `-I` include root。

这些 metadata 能提供 target source 和 include root，但通常不包含完整的传递 Header 或链接输入依赖。因此 Header 的“精确匹配”是指在 include root 内只接受明确 Header 后缀，不是逐个 Header 文件建模。预编译 `.a/.so` 的链接目录采集情况复杂，本次不解析 link command fragments；只有 metadata 已作为 source 暴露、并使其父目录形成 `NativeDirectory` 时才会被监控。

SDK、NDK、CMake toolchain 和 Gradle cache 路径通过已知工具链位置 best-effort 加入 `excludedDirs`。辅助路径识别失败时只舍弃对应排除增强；include root 仍受 `CppHeader` 限制，不能因此升级为 `NativeDirectory`。

### 5.2 Flutter

| 来源 | 生成规则 |
|---|---|
| Flutter 工程源码根 | `Dart` |
| local path package 根 | `Dart` |
| task 暴露的 Dart 输入父目录 | `Dart` |
| task 暴露的非 Dart 输入父目录 | 严格位于 package 根之下时生成 `FlutterAsset` |
| task 直接暴露的输入目录 | package 根只生成 `Dart`；严格子目录按来源生成 `Dart` 或 `FlutterAsset` |
| `pubspec.yaml` 的 `flutter.assets` 目录声明 | `FlutterAsset` |
| `l10n.yaml` 声明的 `arb-dir` | `FlutterAsset` |
| `pubspec.yaml`、`pubspec.lock`、`l10n.yaml` | `configFiles` 精确匹配 |
| Flutter SDK、pub cache、`.dart_tool`、module build | `excludedDirs` |

读取发生在成功 Gradle build 生成 project info 时，本方案以当前 variant Flutter task inputs 可用为前提，不设计 task inputs 不可用时的替代发现路径。local path package 只从 task inputs 中的工程外 Dart 文件向上定位，不读取 `.dart_tool/package_config.json` 兜底，也不递归扫描磁盘。对每个已识别 package 读取其 `pubspec.yaml`、`pubspec.lock` 和可选 `l10n.yaml`，配置文件加入 `configFiles`，目录声明生成对应规则。

## 6. 目录去重

`compactInputDirs()` 改为只执行规范化和完全相同项去重：

```kotlin
private fun compactInputDirs(inputs: List<ExternalBuildInputDir>): List<ExternalBuildInputDir> {
    return inputs
        .map { input ->
            input.copy(
                directory = input.directory.absoluteFile.normalize(),
                filterRules = input.filterRules.toSet(),
            )
        }
        .distinctBy { it.directory.path to it.filterRules }
}
```

不再按路径深度排序，不再调用 `isUnderPath()` 删除子目录。

持久化前按规范化目录路径和规则枚举声明顺序稳定排序，`filterRules` 也按枚举声明顺序写入 JSON，避免 `Set` 迭代顺序造成 project info 和生成脚本快照不稳定。

以下记录必须全部保留：

```text
<worktree>/DTMP                 [CppHeader]
<worktree>/DTMP                 [CppSource, CppHeader]
<worktree>/DTMP/DTMP            [NativeDirectory]
<worktree>/DTMP/DTMP/include    [CppHeader]
```

同目录不同规则不合并，只用于保留各输入来源的采集语义和 report 可解释性。文件匹配仍对同一目录的多条规则执行 OR，因此行为上等价于规则并集；不能依赖“不合并”形成更严格的匹配范围。

## 7. 文件匹配

`resolveExternalBuilds()` 按以下顺序判断：

```text
toolchain cache directory
  -> excludedDirs
  -> deleted or missing path: ignore
  -> configFiles 精确路径
  -> inputDirs 路径覆盖 + filterRules 匹配
  -> ignore
```

目录规则只处理当前存在的普通文件。删除文件、删除目录以及从监控范围移出的旧路径均忽略，不尝试根据历史状态恢复类型。移动到监控目录或在监控目录内移动时，由新路径的新增事件按正常规则识别。目录新增或移动事件递归展开时不跟随符号链接目录；隐藏路径拒绝仅属于 `NativeDirectory` 规则，其他规则继续按各自语义判断。

`ExternalBuildTarget` 增加实际命中的输入目录：

```kotlin
data class ExternalBuildTarget(
    val module: ModuleInfo,
    val buildInfo: ExternalBuildInfo,
    val matchedInputDir: ExternalBuildInputDir?,
)
```

`configFiles` 精确命中时 `matchedInputDir` 为 null。目录规则命中时，如果同一个 build info 下有多个候选目录，选择路径最深的实际命中项作为 `matchedInputDir`。这只用于确定 `ChangedFile.baseDir`，不修改、不合并持久化元数据。

同一个物理文件即使命中同一 build info 的多个目录规则，也只能生成一个 module-specific target，不能导致同一个 external Gradle task 重复执行。

`FileChangesHandler.checkExternalBuildSource()` 使用 `matchedInputDir.directory` 作为 `baseDir`；配置文件继续使用其父目录。禁止重新从全部 `inputDirs` 中选择第一个路径祖先，否则可能选中规则没有实际命中的宽父目录。

## 8. 扫描与性能边界

Jugg 不根据 `inputDirs` 主动周期性遍历目录。正常输入来自：

- Android Studio VFS 已产生的文件或目录事件；
- Git 已计算出的 changed file 列表。

`FileChangesHandler.filter()` 只有在候选本身是目录时才递归调用 `listFiles()`；普通文件变化只进行分类。因此取消父子目录压缩后，日常新增开销主要是：

```text
候选变更文件数 × external input dir 规则项数量
```

`scanRoots` 仅约束目录事件允许展开的范围，不代表 Jugg 会主动扫描全部 root。目录项增长主要影响：

- project info JSON 体积；
- 内存中的目录描述数量；
- 单个候选文件的路径和扩展名匹配次数；
- 少量目录创建、移动事件的递归范围判断。

第一版不引入目录 trie、路径索引或规则缓存。只有真实项目证据表明匹配成为性能热点时再单独优化。

## 9. 序列化与一次性降级

这是中间版本，不兼容旧 external build input schema，但不提升 project info 顶层版本号。

两条读取链都只接受新的对象数组：

```json
{
  "inputDirs": [
    {
      "directory": "/project/native/include",
      "filterRules": ["CppHeader"]
    }
  ]
}
```

旧格式：

```json
{
  "inputDirs": ["/project/native/include"]
}
```

读取时由 `inputDirs` 元素形态校验明确失败，不把字符串恢复成默认规则，不读取旧 `sourceDirs` / `inputFiles` 生成新目录，也不保留兼容分支。不能依赖 Gson 或类型转换偶然抛错来识别旧格式。

期望流程：

```text
插件升级
  -> 旧 project info schema 读取失败
  -> project info unavailable
  -> 用户首次 Run 降级完整 Gradle
  -> 生成新 schema
  -> 后续恢复 external incremental
```

失败必须由现有 project info 读取边界捕获并记录原因，不能让类型转换异常逃逸到 IDE 事件线程或编译主流程。错误信息需明确说明 external build input schema 不兼容以及将通过完整 Gradle 重建。

需要同步修改：

- `ProjectInfoSerializerInGradle.parseExternalBuildInfos()`；
- `ProjectInfoSerializer.restoreExternalBuildLists()` 或移除对应旧格式恢复逻辑；
- `CmdLineContextManager` 的工程路径转换；
- Gradle init script 生成资源及其一致性测试；
- report/project info 打印格式。

## 10. 预期行为

以 DTMP 场景为例：

```text
<worktree>/DTMP                 [CppHeader]
<worktree>/DTMP                 [CppSource, CppHeader]
<worktree>/DTMP/DTMP            [NativeDirectory]
<worktree>/DTMP/DTMP/include    [CppHeader]
```

结果：

| 文件 | 结果 |
|---|---|
| `DTMP/tools/daemon/tmp/.wapt_dtmp_pid` | 忽略 |
| `DTMP/tools/daemon/logs/wapt-dtmp-daemon-*.log` | 忽略 |
| `DTMP/DTMP/src/main.cpp` | `ExternalBuildSource` |
| `DTMP/DTMP/include/api.h` | `ExternalBuildSource` |
| `DTMP/DTMP/schema/message.proto` | `ExternalBuildSource` |
| `DTMP/DTMP/prebuilt/libhelper.so` | `ExternalBuildSource` |
| `DTMP/DTMP/.cache/message.proto` | 忽略 |
| `DTMP/DTMP/include/LICENSE` | 忽略 |
| `DTMP/schema/message.proto` | 忽略，配置根不接受非标准类型 |
| `DTMP/prebuilt/libhelper.so` | 忽略，配置根不接受预编译库类型 |
| `DTMP/CMakeLists.txt` | 通过 `configFiles` 命中 |
| `DTMP/tools/daemon/helper.cpp` | `ExternalBuildSource`，配置根无法判断其是否属于具体 target |

最后一项是目录模型的明确边界。要进一步排除同一 CMake 根中的无关 C++ 工程，需要更精确的 target/source ownership，而不是增加路径黑名单。

## 11. 验证先行与测试落点

本修改保护的是稳定、可观察的文件分类和 external Gradle task 选择，能够形成确定性断言，通过测试价值门禁。实施时先保留 report `bd7e6ffe` 作为失败证据，再写失败测试。

### 11.1 L1：输入采集与去重

Owner：`GradleProjectInfoReaderExternalBuildTest`。

新增断言：

1. include root 只生成 `CppHeader`。
2. CMake 配置根只生成 `CppSource + CppHeader`。
3. 非 Header source parent 严格位于配置根之下或位于工程外时生成 `NativeDirectory`；等于配置根时不能扩大。
4. SDK、NDK、toolchain、Gradle cache、build 和 staging 路径进入排除目录。
5. Flutter Dart root 与 assets 目录声明、l10n、task 确认的 asset directory 使用不同规则。
6. 已识别 local path package 使用与主工程相同的 Dart、配置文件和目录声明规则。
7. 单文件 asset、font、shader 不从 YAML 主动生成目录规则。
8. 未识别 native metadata 输入被忽略，不产生 unsupported 状态，并聚合打印一条 debug 日志。
9. 同路径同规则去重。
10. 同路径不同规则保留。
11. 父目录和子目录均保留。
12. 显式 Header source 只生成 `CppHeader`，不会因同时位于 include root 而扩大为 `NativeDirectory`。
13. Flutter 配置目录声明拒绝绝对路径、`..` 越界、符号链接目录和排除目录，同时接受已存在或带尾部分隔符的空目录。
14. package 根级非 Dart task input 和指向 package 根的目录声明不会生成根级 `FlutterAsset`。

### 11.2 L1：匹配行为

Owner：优先在 `ExternalBuildSource` 对应的现有测试落点补充；如果没有独立 owner，则放入 `FileChangesHandlerTest`，断言最终 `ChangedFile` 行为。

覆盖：

- 配置根下 `.cpp`、`.h`、`.S` 正确命中，`.proto`、`.a`、`.so` 和其他未知类型不命中；
- `NativeDirectory` 下 `.proto`、`.fbs`、`.def`、`.a`、`.so`、`.metal`、`.rs`、`.zig` 等非隐藏普通文件正确命中；
- include root 下 Header 命中，非 Header、无扩展名文件不命中；
- `.wapt_dtmp_pid`、`NativeDirectory` 中的隐藏目录内容、符号链接文件和符号链接目录内容不命中；
- CMake config 精确命中；
- Flutter `.dart` 命中 Dart root；
- 已声明但当前为空的 Flutter asset 目录首次新增文件时正确命中；
- Flutter l10n ARB 输入正确命中；
- 单文件 asset、font 和 shader 只通过 task inputs 命中；
- package 严格子目录中的单文件 asset、font 和 shader 可由 task input 父目录命中，根级单文件资源不扩大为根级 `FlutterAsset`；
- Flutter 任意扩展名 asset 只在 `FlutterAsset` 目录命中；
- FlutterAsset 声明目录下的嵌套普通文件按已接受的递归策略命中；
- 不存在的删除路径不生成 external build source，移动进入监控范围的新路径正常命中；
- 同时命中父子目录时，`baseDir` 使用路径最深的实际命中目录；
- 同一 build info 不产生重复 target。

### 11.3 L1：序列化

Owner：

- `ProjectInfoSerializerInGradleAndroidTestTest`；
- `JuggProjectInfoSerializerAndroidTestTest`；
- `ReadProjectInfoScriptContentTest`。

覆盖：

- 新 `inputDirs` 对象格式往返一致；
- 规则集合不会丢失；
- 目录和规则使用稳定顺序序列化；
- 旧字符串数组读取失败；
- 不再从旧 `sourceDirs` / `inputFiles` 恢复；
- 生成的 `readProjectInfo.gradle.kts` 与源码同步并可编译。

### 11.4 Internal Flow / L3 等价验证

Owner：`ExternalBuildFlowTest`，必要时补 `JuggCompileHelperTest`。

场景：

1. daemon pid/log 进入 IDE/Git 候选列表后被过滤，不执行 external task。
2. 配置根修改 `.cpp`，或具体 source 目录修改任意非隐藏输入后，仍执行对应 native task 并收集产物。
3. Flutter Dart 和已确认 asset 修改仍执行 Flutter external task。
4. 未识别 native metadata 输入被忽略，不影响已支持输入继续执行 external incremental。
5. 删除或移出监控范围不启动 external task；完整 Run 才负责删除行为生效。

最终至少执行定向测试和：

```bash
./gradlew :idea:compileKotlin
```

禁止执行没有 `--tests` 过滤的全量 `:main:test` 或 `:idea:test`。

## 12. 实施顺序

1. 在 `FileChangesHandlerTest` / external build resolver owner 中增加 daemon pid/log 的失败测试。
2. 在 `GradleProjectInfoReaderExternalBuildTest` 固定目录规则采集和“不合并父子目录”行为。
3. 修改 `ExternalBuildInfo` 数据模型和规则枚举。
4. 修改 C++、Flutter input 采集及 `compactInputDirs()`。
5. 修改 `resolveExternalBuilds()` 和 `ChangedFile.baseDir` 选择。
6. 修改 Gradle、IDE、CLI 三处序列化/路径搬迁，并删除旧格式恢复逻辑。
7. 重新生成并校验 `main/src/main/resources/gradle/readProjectInfo.gradle.kts`。
8. 执行定向 L1、Internal Flow 测试和 Kotlin 编译。
9. 使用报告对应工程或等价目录结构手工验证：修改 pid/log、配置根未知类型、include root 非 Header 不触发 C++；修改配置根 `.cpp/.h` 与具体 source 目录任意非隐藏输入正常触发。
10. 同步 `02_compile_core.md`、`04_engineering_project.md`，并检查 Wiki 是否存在 external build 输入识别的用户说明。

## 13. 已知边界

- `FlutterAsset` 递归覆盖已确认目录，可能让 Flutter 实际未声明的嵌套文件误触发 external task。
- Flutter task inputs 被视为 project info 生成时必定可用；不可用时的精确资源和 local path package 发现不在本次处理范围内。
- Flutter package 根不会生成 `FlutterAsset`；直接放在 package 根的单文件资源无法仅凭 task input 父目录进入监控。
- 配置根中的非标准 Native 输入不触发；新建且尚未被 metadata 发现的目录中第一个非标准输入也不触发。
- 具体 source 目录中的所有非隐藏普通文件都会触发，其中可能包含与构建无关的日志或辅助文件。
- 同一具体目录混合输入与构建输出时可能形成反馈触发；输出目录应由 `excludedDirs` 隔离。
- 配置根之外且 metadata 未作为 source 暴露的预编译 `.a/.so` 不受监控。
- 删除输入以及从监控范围移出不触发 external build，用户需要执行完整 Run 才能保证旧 Native 代码或 Flutter asset 被移除。
- 旧 project info 首次读取失败后需要一次完整 Gradle 重建。

## 14. 非目标

本方案不处理：

- 基于单个文件的 external input 建模；
- 任意 glob、正则或 gitignore 风格规则；
- 用户自定义扩展名；
- 解析 CMake link command fragments 发现预编译 `.a/.so`；
- Flutter task inputs 不可用时通过 `package_config.json` 或磁盘扫描恢复 package；
- 精确模拟 Flutter asset 直接子文件、单文件和分辨率变体语义；
- 对删除、移出监控范围或旧产物移除做增量适配；
- 自动判断同一 CMake 根下某个 C/C++ 文件是否属于目标 target；
- 通过 daemon、logs、tmp 等目录名建立项目专属黑名单；
- 优化 IDE VFS 或 Git 自身的全局变更扫描；
- 为旧 project info 增加兼容迁移。

如果后续真实项目证明目录规则无法表达重要输入，再单独评估 link metadata、受限扩展名规则或精确文件模型，不在本次 bugfix 中提前引入。
