# 编译系统：DataBinding / ViewBinding

> 最后核对：2026-07-22
> 一致性规则：文档与代码冲突时，以代码为准。

---

## 1. 文档定位

本页聚焦 DataBinding/ViewBinding 在 Jugg 增量编译里的两阶段处理：

- 资源阶段如何生成 base classes、stripped XML 和 DataBinding 触发源。
- 源码阶段如何通过一次 DataBinding annotation processor invocation 处理 adapter declaration、维护 merged setter store、生成 mapper holder 并合并 BR。
- 各阶段之间依赖哪些 Gradle 中间产物与 Jugg 临时目录。

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
| `DataBindingGenMapperCompiler` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/databinding/DataBindingGenMapperCompiler.kt` | 源码阶段：一次 APT/KAPT 同时处理当前 adapter declaration、生成 current-module store 和 Mapper，并提交 merged store cache |
| `DataBindingSetterStoreCache` | `main/src/main/java/com/sickworm/intellij/jugg/compiler/databinding/DataBindingSetterStoreCache.kt` | 将官方 processor 生成的 current-module store 合入 Gradle baseline/上一版 merged store并原子发布 |
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
| `setterStoreCacheDir` | `DataBindingArgsManager` | module + variant 隔离的稳定缓存目录，不随当前轮 DataBinding 工作区 reset；保存 baseline hash 和 merged store generation |
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

### 4.2 源码阶段：adapter store / mapper / BR / language compile

```text
SourceCompiler.prepareSourceCompile()
  -> SourceDataBindingProcessor.processDataBindingMapper()
     -> DataBindingGenMapperCompiler.doModuleCompile()
        -> 不 reset argsManager，继续消费资源阶段写入的 layout info
        -> runAnnotationProcessor()
           -> LayoutIncludeAnalyzer.findAllIncludePath(resource)
           -> DataBindingClasspathHelper 对 project module 优先选择有效 merged store，否则使用 Gradle baseline，并收集全部 AAR setter store
           -> 按来源隔离复制到 dataBindingDependencyArtifacts，由官方 DataBinding processor 递归加载并合并
           -> 当前默认：JavaCompilerInvoker apt-only
           -> 保留分支：KotlinCompilerInvoker kapt
              -> kapt 失败时切到 Java APT fallback 重试一次
           -> 官方 ProcessMethodAdapters 先把当前声明加入内存 store并输出 current-module store
           -> 官方 ProcessExpressions 随后使用同一内存 store生成 BindingImpl / Mapper
        -> 当前任务含 adapter declaration 时，将 current-module store 合入 DataBindingSetterStoreCache
        -> adapter-only 任务更新 cache 后返回；有 layout 时继续生成 Mapper holder 和 BR
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
- Mapper APT 对 project module 优先复用 Jugg merged store；没有有效 cache 时回到当前 variant 最近一次 Gradle 完整构建生成的模块 `*-setter_store.json`。AAR transform 根目录下的 `data-binding/*-setter_store.json` 仍全部收集。
- adapter declaration source 变化时复用 Mapper 的同一次官方 APT/KAPT；Jugg 读取其 current-module store，只解析容器和 declaring type，用于替换同一类型的历史记录，不自行推导 adapter 方法签名。
- 不同 setter store 按来源复制到独立子目录，因为官方 DataBinding processor 会递归读取 dependency artifacts；直接平铺会让同名 store 相互覆盖。
- 当前模块支持 adapter declaration 新增、同一 declaring type 的修改和跨轮复用，不再因声明变化前置回退 Gradle；删除源码、移除全部声明和 declaring class 改名不在 B1 范围。
- Mapper 失败保持 SourceCompiler 既有失败/重试策略，本功能不扩大其语义。
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
- setter store cache 以 Gradle module store 内容 hash 作为 baseline 身份；baseline 变化时旧 generation 不再命中。
- current-module store 中出现的 declaring types 会先从上一版 merged store移除，再合入本轮官方结果；无法从当前 store 得到旧 declaring type 的删除/改名场景不在 B1 范围。

---

## 7. 排查入口

| 现象 | 优先入口 |
|------|----------|
| 明明启用了 DataBinding 但未进入 mapper 阶段 | `DataBindingArgsManager.isUseDataBinding()` 与 module `packageName` |
| ViewBinding class 未生成 | `DataBindingGenBaseClassesCompiler.splitLayoutXml()` / `generateBaseClasses()` |
| DataBinding mapper 生成失败 | `DataBindingGenMapperCompiler.runAnnotationProcessor()`，重点看 `runAnnotationProcessor apt output` 日志 |
| 自定义属性提示找不到 setter 或参数类型不匹配 | 先检查 `DataBindingClasspathHelper` 是否选择 module merged store，再检查 `DataBindingGenMapperCompiler` 是否从 `dataBindingAarOutDir` 取得 current-module store并发布 cache |
| adapter-only 后下一轮 layout 找不到属性 | 检查 `SourceDataBindingProcessor` 是否因 adapter declaration 触发 processor，以及 setter store cache 的 baseline hash 是否命中 |
| 删除/改名 adapter 后旧属性仍存在 | B1 不处理删除语义；执行 Gradle fallback恢复完整 baseline |
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
- setter store 方案：`../task/databinding_setter_store_incremental_design.md`
