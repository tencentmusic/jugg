# DataBinding setter store 增量合并实现计划

## 1. 目标

保持 Mapper APT 默认快速路径，并只在 Kotlin adapter declaration 变化时运行隔离 KAPT，在当前 module 内支持：

- 当前轮 adapter declaration 对同轮 layout 立即可见。
- processor 输出的 current-module setter store 合入持久化 merged store。
- 后续 layout-only 编译复用 merged store。
- Gradle baseline 和无关 adapter 记录不丢失。
- DataBinding 细节不进入 `JuggCompiler` 主流程或文件删除链路。

设计依据见 `docs/task/databinding_setter_store_incremental_design.md`。

## 2. 最终链路

```text
SourceCompiler
  -> SourceDataBindingProcessor
     -> DataBindingGenMapperCompiler
        -> DataBindingClasspathHelper: merged store ?: Gradle baseline
        -> Kotlin adapter: isolated project KAPT in Gradle JVM
        -> official current-module setter store
        -> compile Kotlin adapter class
        -> DataBindingSetterStoreCache.merge()
        -> Java Mapper APT
        -> layout 存在时继续生成 Mapper / BindingImpl / BR
        -> adapter-only 时返回并继续普通源码编译
```

Java adapter 仍由一次 Mapper APT处理；Kotlin adapter 需要先通过 KAPT stub/processor生成 store。Java APT不能直接处理 Kotlin source，也不能以普通 classpath class 替代 KAPT annotation round。

## 3. TDD 执行清单

| 测试文件 | 层级 | 场景 |
|----------|------|------|
| `main/src/test/java/com/sickworm/intellij/jugg/compiler/source/kotlin/KotlinCompilerProcessRunnerTest.kt` | L1 | Gradle JVM选择、JDK module flags |
| `main/src/test/java/com/sickworm/intellij/jugg/compiler/databinding/DataBindingSetterStoreCacheTest.kt` | L1 | generated store 合入 baseline，保留无关记录 |
| 同上 | L1 | 同一 declaring type 替换旧记录 |
| 同上 | L1 | 多轮 generated store 累积 |
| 同上 | L1 | 冲突不覆盖上一版 merged store |
| `idea/src/test/java/com/sickworm/intellij/jugg/compile/JuggCompileForDataBindingTest.kt` | L3 | 同轮 Kotlin adapter + layout |
| 同上 | L3 | adapter-only 后下一轮 layout 复用 |
| 同上 | L3 | Java adapter + layout |
| 同上 | L2 | Gradle baseline 原有 adapter 保留 |
| `idea/src/test/java/com/sickworm/intellij/jugg/compiler/JuggCompileHelperTest.kt` | L2 | adapter declaration 不再前置 fallback |
| `idea/src/test/java/com/sickworm/intellij/jugg/manager/TopLevelFlowTest.kt` | L3 | 真实 demo 编译和部署 |

删除、移除全部声明和 declaring class 改名不属于 B1，不增加对应测试。

## 4. 生产代码修改清单

### 4.1 `DataBindingGenMapperCompiler`

- adapter declaration 检测内联在 DataBinding compiler 文件内。
- Java adapter 使用现有 Mapper APT；Kotlin adapter 使用隔离 KAPT store phase。
- APT/KAPT 成功后读取 `dataBindingAarOutDir` 中的 current-module setter store。
- 有 adapter declaration 时调用 `DataBindingSetterStoreCache.merge()`。
- 有 layout 时保持 Mapper holder、BR merge 和输出逻辑。
- adapter-only 时不要求 Mapper 文件存在，只更新 store 后返回成功。
- Kotlin adapter 变化时允许一次 KAPT store invocation + 一次 Mapper APT；其他场景保持一次 APT。

### 4.2 `SourceDataBindingProcessor`

- 原有 DataBinding trigger source 仍可触发 Mapper。
- 当前 Java/Kotlin source 含 adapter declaration 时也触发 processor。
- 无 layout 但有 adapter declaration 时允许 adapter-only processor 路径。
- Kotlin adapter store 生成成功后先编译 adapter class，再进入 Mapper APT。
- 不承担 JSON cache 实现。

### 4.3 `DataBindingSetterStoreCache`

- API 收敛为 `merge(baselineStore, generatedStore)` 和 `getMergedStore(baselineStore)`。
- baseline hash 一致时从上一版 merged store继续合并，否则从 Gradle baseline 开始。
- 从 generated store 提取 declaring types，在 base 中删除同类型旧记录。
- 合并 generated store，检测 schema 和完整 key 冲突。
- 通过 generation 指针原子发布，失败时保留上一版。
- 不保存 source fragment、old source 或 removed types。

### 4.4 `DataBindingClasspathHelper`

- project module 优先提供有效 Jugg merged store。
- cache 不可用时提供 Gradle baseline。
- 同一 module 不同时提供 baseline 和 merged store。
- AAR setter store 收集保持不变。

### 4.5 `DataBindingArgsManager`

- 保留稳定的 `setterStoreCacheDir`。
- Kotlin adapter KAPT与 Mapper APT 使用独立 aar/layout/output 工作目录。

### 4.6 `KotlinCompilerInvoker` / `KotlinCompilerProcessRunner`

- 普通编译默认继续使用进程内 `K2JVMCompilerIsolate`。
- 增加 opt-in isolated execution mode，仅由 Kotlin DataBinding adapter 使用。
- 从 `ICompileContext.cmdCompileEnv` 读取 Gradle `JAVA_HOME`。
- 子进程直接启动项目 `K2JVMCompiler` main，复用现有 CLI 参数和输出解析器。
- JDK 9+ 为 kapt/javac internal packages 添加 exports/opens；支持取消和超时。

### 4.7 `JuggCompileHelper`

- 仅移除 BindingAdapter declaration 的前置 Gradle fallback。
- 不附加 `oldSource`，不保留 deleted source，不增加 DataBinding 编译职责。

## 5. 明确恢复的文件

以下文件恢复到 setter store 功能接入前的职责，不保留 DataBinding 特殊逻辑：

- `JuggCompiler.kt`
- `CompilerExt.kt`
- `JuggManager.kt`
- `FileChangesHandler.kt`
- `IFileChangesHandler.kt`
- `DeployFileManager.kt`
- `SourceCompiler.kt` 的 Mapper 失败策略

删除：

- `DataBindingSetterStoreCompiler.kt`
- 独立的 `DataBindingDeclarationUtils.kt`
- 删除源码和 old-source 相关测试

## 6. cache merge 规则

1. 校验 baseline 和 generated store 的 schema。
2. baseline hash 一致时读取上一版 merged，否则使用 baseline。
3. 从 generated store 的八个已知区域收集 declaring types。
4. 从 base 删除这些 declaring types 贡献的旧记录。
5. 合并 generated store。
6. 相同 key/value 去重；相同 key、不同 value 判定冲突。
7. 写入新 generation 并回读校验。
8. 原子替换 `current` 指针。

## 7. 失败策略

以下情况不发布新 cache：

- Gradle baseline 不存在或无法读取。
- current-module setter store 未生成。
- setter store `version` 不支持或必要字段缺失。
- `useAndroidX` 不兼容。
- 不同声明竞争同一完整 key。
- generation 写入、回读或原子发布失败。

adapter declaration 未变化时允许继续使用已有 merged store或 Gradle baseline。

## 8. 验证命令

```bash
./gradlew :main:test --tests "com.sickworm.intellij.jugg.compiler.databinding.DataBindingSetterStoreCacheTest"
./gradlew :idea:test \
  --tests "com.sickworm.intellij.jugg.compile.JuggCompileForDataBindingTest.testNewBindingAdapterAndLayoutCompileIncrementally" \
  --tests "com.sickworm.intellij.jugg.compile.JuggCompileForDataBindingTest.testNewBindingAdapterIsReusedByNextIncrementalCompile" \
  --tests "com.sickworm.intellij.jugg.compile.JuggCompileForDataBindingTest.testJavaBindingAdapterAndLayoutCompileIncrementally"
./gradlew :idea:test --tests "com.sickworm.intellij.jugg.manager.TopLevelFlowTest.testDeployIncrementalDataBindingSetterStore"
./gradlew :idea:compileKotlin
```

禁止无 `--tests` 过滤的全量 `:main:test` / `:idea:test`。

## 9. 非目标

- 不处理 adapter source 删除或移除全部声明。
- 不处理 declaring class 改名后的旧记录清理。
- 不修改 DataBinding processor。
- 不自行解析 Java/Kotlin adapter 方法。
- B1 不实现 project library 传播和属性级 layout 影响分析。
