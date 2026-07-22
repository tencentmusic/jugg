# 编译系统：DataBinding / ViewBinding

> 最后核对：2026-07-22
> 一致性规则：文档与代码冲突时，以代码为准。

---

## 1. 文档定位

本页聚焦 DataBinding/ViewBinding 在 Jugg 增量编译里的两阶段处理：

- 资源阶段如何生成 base classes、stripped XML 和 DataBinding 触发源。
- 源码阶段如何运行 DataBinding annotation processor、生成 mapper holder、合并 BR。
- 两阶段之间依赖哪些 Gradle 中间产物与 Jugg 临时目录。

不重复资源编译与 Java/Kotlin 编译主链；对应内容见 `02_compile_resource.md` 与 `02_compile_source.md`。

---

## 2. 核心源码索引

| 类 | 文件 | 作用 |
|----|------|------|
| `ResourceCompiler` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/overlay/ResourceCompiler.kt` | 资源编译阶段入口，触发 `DataBindingGenBaseClassesCompiler` |
| `SourceCompiler` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/source/SourceCompiler.kt` | 源码编译阶段入口，触发 `SourceDataBindingProcessor` 和 mapper 生成 |
| `SourceDataBindingProcessor` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/source/SourceDataBindingProcessor.kt` | 在源码编译前协调 DataBinding mapper 生成和失败重试 |
| `DataBindingArgsManager` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/databinding/DataBindingArgsManager.kt` | 统一维护 DataBinding/ViewBinding 的临时目录、Gradle 中间产物路径、触发源和 mapper/BR 路径 |
| `DataBindingGenBaseClassesCompiler` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/databinding/DataBindingGenBaseClassesCompiler.kt` | 资源阶段：split layout XML，生成 ViewBinding base classes 或 DataBinding trigger file |
| `DataBindingGenMapperCompiler` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/databinding/DataBindingGenMapperCompiler.kt` | 源码阶段：运行 DataBinding annotation processor，生成增量 mapper holder，合并 library/app BR |
| `LayoutIncludeAnalyzer` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/databinding/LayoutIncludeAnalyzer.kt` | 找到当前变更 layout 通过 `<include>` 影响到的 layout info |
| `DataBindingClasspathHelper` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/databinding/DataBindingClasspathHelper.kt` | 为 DataBinding annotation processor 准备 compiler classpath、plugin 和 Gradle/AAR setter stores |
| `DataBindingTemplates` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/databinding/DataBindingTemplates.kt` | 生成 mapper delegate、full mapper、incremental holder 模板 |

---

## 3. 核心数据流与目录模型

| 路径/状态 | 维护者 | 关键语义 |
|-----------|--------|----------|
| `tempCompileDir/data_binding/<relative module>` | `DataBindingArgsManager` | Jugg 自己的 DataBinding 工作区，按 module root 相对路径隔离 |
| `dataBindingSourcesOutputDir` | `DataBindingArgsManager` | base class、APT 生成源、mapper/BR 的当前轮输出目录 |
| `dataBindingStrippedXmlDir` | `DataBindingArgsManager` | DataBinding split 后的 stripped XML，后续作为 res 输出进入 overlay |
| `tempDataBindingLayoutXmlDir` | `DataBindingArgsManager` | 当前轮 layout info merge 目录，资源阶段生成，源码阶段继续消费 |
| `backupDataBindingLayoutXmlDir` | `DataBindingArgsManager.reset()` | 备份 Gradle layout info，避免新增后删除文件导致 Gradle 后续编译失败 |
| `incrementalDependencyClassesFolder` | `DataBindingArgsManager` | 保存 incremental artifact，供下轮 include 和 base class 生成使用 |
| `dataBindingPreProcessorSources` | `DataBindingArgsManager` | DataBinding annotation processor trigger source 目录 |
| `dataBindingDependencyArtifacts` | `DataBindingArgsManager` | Mapper APT 的 setter store 输入目录；不同来源保留在独立子目录，避免同名 JSON 覆盖 |
| `mapperDir` | `DataBindingArgsManager` | 保存 delegate mapper、full mapper、历史 incremental mapper 源 |
| `isKaAptRetryAptSuccess` / `isLastFallbackAptFailed` | `DataBindingArgsManager.Companion` | KAPT fallback APT 分支的跨阶段状态；当前默认 APT 路径下通常只用于失败重试判断 |

---

## 4. 两阶段处理链路

### 4.1 资源阶段：base classes / trigger / stripped XML

```text
ResourceCompiler
  -> DataBindingGenBaseClassesCompiler
     -> DataBindingArgsManager 解析 Gradle/Jugg DataBinding 目录
     -> 产出 tempDataBindingLayoutXmlDir、base classes 或 DataBinding trigger、stripped XML
     -> JuggCompiler 把这些产物转给 SourceCompiler 或 overlays
```

资源阶段的单文件内部顺序优先直接读 `DataBindingGenBaseClassesCompiler`。文档只保留跨阶段价值：它把 Gradle layout info 备份到 Jugg 临时目录，并产出源码阶段必须消费的 trigger/layout info。

### 4.2 源码阶段：mapper / BR / language compile

```text
SourceCompiler.prepareSourceCompile()
  -> SourceDataBindingProcessor.processDataBindingMapper()
     -> DataBindingGenMapperCompiler.doModuleCompile()
        -> 不 reset argsManager，继续消费资源阶段写入的 layout info
        -> runAnnotationProcessor()
           -> LayoutIncludeAnalyzer.findAllIncludePath(resource)
           -> DataBindingClasspathHelper 收集当前模块/父模块的 Gradle setter store 和全部 AAR setter store
           -> 按来源隔离复制到 dataBindingDependencyArtifacts，由官方 DataBinding processor 递归加载并合并
           -> 当前默认：JavaCompilerInvoker apt-only
           -> 保留分支：KotlinCompilerInvoker kapt
              -> kapt 失败时切到 Java APT fallback 重试一次
        -> 产出 DataBinderMapperImpl_Inc_N、DataBinderMapper_IncrementalHolder、BR、stripped XML
  -> DataBinding 生成的 Java 源合入 Java compile 输入
  -> Kotlin -> Java -> Dex/Minify 继续执行
```

---

## 5. 增量关键点

- DataBinding 和 ViewBinding 都从资源阶段入口开始，但只有 DataBinding 会进入 mapper/BR 阶段。
- `DataBindingArgsManager.isUseDataBinding(module, xmlFile)` 会在未显式开启时通过 Gradle kapt 输出目录和 XML 中 `<layout` 做猜测；普通 ViewBinding layout 不应触发 DataBinding mapper。
- BR 合并使用 `LinkedHashMap` 保持声明顺序稳定；新增字段追加到末尾，避免 BR id 抖动。
- mapper 使用 `DataBinderMapperImpl_Inc_N` 增量编号；`N` 来自已部署 dex 中同包名 inc mapper 的数量。
- 源码阶段不能调用 `argsManager.reset()`，因为 mapper 生成依赖资源阶段刚写入的 `tempDataBindingLayoutXmlDir`。
- 当前 `isFallbackApt = true`，DataBinding mapper 默认走 Java APT trigger；KAPT 失败 fallback 分支保留在代码中，但排查当前行为时应先按 APT 路径看。
- fallback APT 也失败时通过 `isLastFallbackAptFailed` 通知 `SourceDataBindingProcessor` 先编译 Kotlin class 后再重试一次 mapper 生成。
- `DataBindingClasspathHelper` 只给 DataBinding 相关依赖做 annotation processing，避免 ARouter 等其他 processor 进入这条旁路。
- Mapper APT 会复用当前 variant 最近一次 Gradle 完整构建生成的模块 `*-setter_store.json`，并收集所有 AAR transform 根目录下的 `data-binding/*-setter_store.json`；Jugg 不解析或手工合并 JSON。
- 不同 setter store 按来源复制到独立子目录，因为官方 DataBinding processor 会递归读取 dependency artifacts；直接平铺会让同名 store 相互覆盖。
- 当前方案只保证复用最近一次 Gradle 基线。若本轮修改的当前源码或最近一次构建源码包含 `@BindingAdapter` / `@BindingMethods` 等声明，`JuggCompileHelper` 会在增量编译前回退 Gradle，避免继续使用旧 store；完整增量方案见 `docs/task/databinding_setter_store_incremental_design.md`。
- Mapper 在 Kotlin 预编译重试后仍失败时保持 fail-open：记录警告并继续源码编译，允许不影响生成结果的 DataBinding XML 改动继续增量部署。
- `copyToGradleDir()` 不是普通输出复制，它是为了让 Gradle 后续编译看到稳定的 layout info，避免新增后删除文件导致全量 Gradle 失败。

---

## 6. 隐形约束 / 设计思路

- `isFallbackApt` 当前恒为 true，因此 `isJava` 默认偏向 APT；如果后续代码取消该常量策略，再重新按 `isUseKaptForDataBinding()` 判断 KAPT 路径。
- `DataBindingGenBaseClassesCompiler` 的输出在 `JuggCompiler` 中会被转成下一步 `SourceCompiler` 输入，而不是直接作为最终 source 产物结束。
- DataBinding 的 stripped XML 会以 `CompileOutput.Type.ResXml` 返回，并在 `JuggCompiler` / `SourceCompiler` 中被移动到 overlays，不能只看 Java 输出判断是否成功。
- `mergeLibraryBr()` / `mergeAppBr()` 要求 Gradle 上一次生成的 BR 文件存在；不存在时会抛异常，而不是新建一个空 BR。
- include 关系不是靠扫描当前 XML 文本直接编译所有引用方，而是基于 layout info 文件补齐到 `tempDataBindingLayoutXmlDir`。
- AGP 7.2.2 和 AGP 8.4 的中间产物路径不同，`DataBindingArgsManager` 通过候选目录匹配；路径问题优先看这里，不要先改编译器参数。
- DataBinding trigger file 只是为了触发 annotation processor；真实 mapper 和 BR 仍来自 processor 输出。

---

## 7. 排查入口

| 现象 | 优先入口 |
|------|----------|
| 明明启用了 DataBinding 但未进入 mapper 阶段 | `DataBindingArgsManager.isUseDataBinding()` 与 module `packageName` |
| ViewBinding class 未生成 | `DataBindingGenBaseClassesCompiler.splitLayoutXml()` / `generateBaseClasses()` |
| DataBinding mapper 生成失败 | `DataBindingGenMapperCompiler.runAnnotationProcessor()`，重点看 `runAnnotationProcessor apt output` 日志 |
| 自定义属性提示找不到 setter 或参数类型不匹配 | 先检查 `DataBindingClasspathHelper` 日志是否包含 app/module/AAR 对应 setter store，再确认最近一次 Gradle 完整构建是否已生成该声明 |
| BR 缺字段或 id 抖动 | `mergeLibraryBr()` / `mergeAppBr()` 的 baseline BR 与 current incremental BR |
| `<include>` 修改后引用方未更新 | `LayoutIncludeAnalyzer.findAllIncludePath()` 和 `tempDataBindingLayoutXmlDir` 内容 |
| Gradle 后续编译因 layout info 文件缺失失败 | `DataBindingArgsManager.backupDataBindingLayoutXmlDir` 与 `copyToGradleDir()` |
| DataBinding processor 失败后重试行为异常 | `isKaAptRetryAptSuccess` / `isLastFallbackAptFailed` 与 `SourceDataBindingProcessor` |
| AGP 升级后找不到中间产物 | `DataBindingArgsManager.gradleDataBindingLayoutXmlDir` 候选路径 |

---

## 8. 关联文档

- 源码编译：`02_compile_source.md`
- 资源编译：`02_compile_resource.md`
- 项目路径模型：`04_engineering_project.md`
