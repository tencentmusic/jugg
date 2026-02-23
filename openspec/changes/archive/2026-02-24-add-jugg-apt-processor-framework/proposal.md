## Why

当前 Jugg 的单文件增量编译在面对 APT/KAPT/KSP 产生的“模块级聚合源码”时，无法保证生成结果始终正确，容易出现新页面未被全局入口注册的问题。现在需要在不走传统 apt/kapt/ksp 处理链路的前提下，引入一套可扩展的自定义注解处理框架，保证增量场景下全局聚合源码可被及时修正并继续参与后续编译。

## What Changes

- 在 `SourceCompiler` 起始阶段新增 `JuggAptCompiler` 处理步骤：输入本轮编译相关文件与已生成 APT 源码，输出“修正后的 APT 源码集合”供后续 `JavaCompiler`/`KotlinCompiler` 使用。
- 新增 `IJuggAptProcessor` 接口与处理器注册机制：`JuggAptCompiler` 内部维护处理器列表，逐个执行 `process`，统一产出需要重新编译的 APT 源码。
- 新增 `BaseJuggAptProcessor` 抽象基类：沉淀通用能力（文本级注解命中判断、方法末尾插入模板代码、重复插入防重等），降低新增处理器成本。
- 落地一个 Kuikly `@Page` 聚合修正处理器：针对 `KuiklyCoreEntry.kt` 的 `triggerRegisterPages`，若缺少当前页面注册片段则自动补齐模板调用。
- 将处理后的源码回传到 `JuggCompiler` 既有流程，继续走 Java/Kotlin 编译链路，不引入新的外部 APT 执行依赖。

## Capabilities

### New Capabilities
- `incremental-apt-source-rewrite`: 在增量编译开始阶段扫描并改写已生成 APT 源码，使其可作为本轮编译输入继续参与编译。
- `extensible-jugg-apt-processor-framework`: 提供 `IJuggAptProcessor` + `BaseJuggAptProcessor` 的可扩展处理器框架与通用文本改写能力。
- `kuikly-page-router-aggregation-sync`: 提供 Kuikly `@Page` 聚合文件增量同步能力，确保 `triggerRegisterPages` 包含当前页面注册语句且避免重复插入。

### Modified Capabilities
- None.

## Impact

- 编译主链路：`main/src/main/java/com/sickworm/intellij/jugg/compiler/source/SourceCompiler.kt`、`main/src/main/java/com/sickworm/intellij/jugg/compiler/JuggCompiler.kt`
- Java/Kotlin 后续编译输入衔接：`main/src/main/java/com/sickworm/intellij/jugg/compiler/source/JavaCompiler.kt`、`main/src/main/java/com/sickworm/intellij/jugg/compiler/source/kotlin/KotlinCompiler.kt`
- 新增 APT 处理框架与处理器实现：预计位于 `main/src/main/java/com/sickworm/intellij/jugg/compiler/source/` 下的新子目录
- 风险与依赖：文本改写策略需控制误匹配；需保障重复编译幂等性与多处理器执行顺序稳定；不新增第三方依赖，沿用现有编译流程与产物管理。
