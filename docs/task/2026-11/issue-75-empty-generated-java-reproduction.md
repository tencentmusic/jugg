# Issue 75：同名空生成 Java 文件复现

## 目标与场景

验证依赖模块的正常 Java 父类被应用 AIDL 生成目录中的同包同名空 Java 文件遮蔽时，是否出现 Gradle 通过而 Jugg 增量失败。

fixture：`library1` 的 `ParentModel` 包含 public `_id`、`title`；应用的 `ChildModel` 继承该类，Kotlin probe 访问继承字段与子类字段。应用另有对应 parcelable AIDL 声明。

当前 demo 使用 Kotlin 1.9.22、AGP 7.3.1、Gradle 7.4、JDK 17。通过 `-PjuggIssue75Fixture=true` 将应用 Build Tools 从默认 30.0.3 切换为 36.0.0，由标准 AGP `compileDebugAidl` 任务调用 SDK AIDL 工具生成同名空 Java 文件；测试不创建或写入该文件。尚未重放报告的 Kotlin 2.0.21/AGP 8.9.3 环境。

## 编译输入与失败机制

本例冲突发生在应用的 Java 源码根和依赖模块的二进制输入之间：

```text
应用的 -Xjava-source-roots
└─ build/app/generated/aidl_source_output_dir/debug/out/
   └─ com/example/library1/issue75/ParentModel.java（空文件）

binary classpath
└─ build/library1/intermediates/javac/debug/classes/
   └─ com/example/library1/issue75/ParentModel.class（正常父类）
```

依赖模块本身有正常的 `ParentModel.java` 源码，但 Jugg 本轮通过 classpath 读取其已编译的 `.class`，没有把依赖模块的父类源码目录一起加入应用的 Java source roots。

Jugg `KotlinCompilerInvoker` 将模块源码目录和 `BaseCompileContext.getGeneratedSourcePaths()` 收集的生成目录整体传入 `-Xjava-source-roots`。Kotlin 对目录源码根可以按包路径和文件名命中 `ParentModel.java`；文件命中后再解析类声明。空文件没有目标类，解析返回空，该路径没有继续回退到正常的同名 `.class`，因而子类父类关系解析失败，继承字段 `_id`、`title` 随之无法解析。

Gradle Kotlin 插件的输入方式不同：`KotlinCompile` 枚举具体 Java 文件，并加入 compiler 的 `freeArgs`。Kotlin 的单文件源码索引读取实际声明的 ClassId；空文件没有类声明，不会登记为 `ParentModel`。因此即使空文件作为具体源码输入，也不会产生上述按目录文件名命中的遮蔽。

源码依据：

- Jugg：`main/src/main/java/com/sickworm/intellij/jugg/compiler/source/kotlin/KotlinCompilerInvoker.kt`、`main/src/main/java/com/sickworm/intellij/jugg/project/BaseCompileContext.kt`。
- Gradle Kotlin 插件：本机 `kotlin-gradle-plugin-1.9.22-sources.jar` 中的 `KotlinCompile.kt`，Java 文件列表加入 `args.freeArgs`。
- Kotlin 单文件索引：[SingleJavaFileRootsIndex.kt（1.9.22）](https://github.com/JetBrains/kotlin/blob/v1.9.22/compiler/cli/cli-base/src/org/jetbrains/kotlin/cli/jvm/index/SingleJavaFileRootsIndex.kt)。
- Kotlin 目录查找与后续解析：[KotlinCliJavaFileManagerImpl.kt（2.0.21）](https://github.com/JetBrains/kotlin/blob/v2.0.21/compiler/cli/cli-base/src/org/jetbrains/kotlin/cli/jvm/compiler/KotlinCliJavaFileManagerImpl.kt)。

## classpath、产物目录与顺序边界

- 原报告补充材料中，目标父子类的 binary classpath 路径、包路径、字段及继承关系均已检查，未发现对应异常。demo 中仅移除空 Java 文件便能恢复 Jugg 编译，也证明该次 binary classpath 可以满足编译。
- AIDL 首先生成 Java 源码；同一模块中，有实际类声明的 AIDL 生成 Java 与普通 Java 通常由 javac 编译到该模块同一个 class 输出目录。空 Java 文件不会生成 `.class`。本例正常父类属于依赖模块，其 class 输出目录与应用模块不同。
- `-Xjava-source-roots` 和 binary classpath 是两类输入，不能把两者直接合并成一张目录顺序表。当前确认的是 Gradle 与 Jugg 的源码传入方式存在差异，尚未逐项核对两边全部有效输入及其顺序。
- 仅将 AIDL 目录移到其他 Java source roots 后面，预计仍可能命中同名空文件；此顺序变体尚未单独实验，不作为已验证结论。
- AOSP AIDL 的 Java 非结构化 Parcelable 分支在设置输出目录时，会创建空输出文件。AGP `AidlProcessor` 另有按特定注释内容识别并删除旧占位文件的逻辑，因此不同 Build Tools 版本可能留下不同结果。本复现使用 Build Tools 36.0.0 的真实 Gradle 产物；原报告具体生成过程仍未捕获。

## 验证

定向流程测试：`Issue75EmptyJavaReproTest.compareEmptyGeneratedJavaWithBinaryParent`，复用 `MockJugg` 的真实编译链，覆盖：

1. 使用 fixture 开关运行 Gradle assemble，断言 AGP 自动生成的同名 Java 文件存在且为 0 字节。修改 probe 后再次运行 Gradle assemble，断言成功且空文件仍存在。
2. 修复前改为期望成功的断言后，定向测试失败，确认已有失败证据；修复后修改同一 probe，保留原始空 Java 文件，Jugg 增量编译成功。
3. 仅删除空生成 Java 文件，Jugg 增量编译成功。

运行：`./gradlew :idea:test --tests com.sickworm.intellij.jugg.manager.Issue75EmptyJavaReproTest`，设置 `JAVA_HOME` 为 JDK 17、`ANDROID_HOME` 为 SDK 路径。

最终 Gradle 自动生成对照已通过：首次 assemble 实际执行 `:app:compileDebugAidl` 并生成 0 字节文件，38 秒成功；修改 Kotlin probe 后再次 assemble，保留该空文件，12 秒成功。该阶段为修复前复现验证；清理阶段恢复 probe 并移除本次生成的空文件。

修复前原始复现测试通过：1 项执行、0 failures、0 errors，测试用时 57.267 秒，外层 Gradle 1 分 11 秒，包含上述三项对照。Jugg 经仓库 `MockJugg` 流程运行真实编译链；本机 IDE CLI 因缺少有效 Jugg Run Configuration 未能执行，本次没有以真实 IDE Run 作为验证证据。

## 范围

在原有 fixture 和复现测试基础上实现兼容修复，不涉及 Wiki 操作说明变化。此实验确认该输入条件足以产生同类故障；原报告失败瞬间选中文件仍需现场 trace 或同条件对照确认。


## 修复实现

- 在独立文件 `project/GeneratedSourcePaths.kt` 中实现扫描与过滤复制，由 `BaseCompileContext.getGeneratedSourcePaths()` 调用。
- 每次同步扫描实际收集的生成根。含 0 字节 Java 文件的根替换为临时模块 `filtered_generated_sources` 下按本次目录序号隔离的副本，过滤空 Java、保留其他文件；无空 Java 的根保持原路径和顺序。
- 每次重建副本前清理旧内容，防止已删除类残留；保留原工程文件。扫描或复制失败按目录降级为原路径并 warn 记录原因，其余目录继续处理，记录 debug 耗时及命中信息。
- L1 测试保护原文件保留、有效文件复制、非 Java 空文件保留、跨轮增删刷新和空文件恢复后的原路径行为；L3 复用真实 Gradle AIDL 空文件验证编译恢复。
- 测试价值：生成源码解析和跨轮新鲜度属于稳定可观察契约，不使用测试专用生产 seam。
- 最终验证：修复前成功断言失败（1 failure）；修复后 `GeneratedSourcePathsTest`（1 项）和 `Issue75EmptyJavaReproTest`（1 项）通过，外层 Gradle 1 分 21 秒。现有 `JuggCompileTest.compileResourceAddIds`、`compileDataBinding`、`compileDataBindingWithStaleClassPathSource` 3 项均通过，外层 Gradle 46 秒；demo 日志一次扫描 6 个生成根耗时 3 ms，仅代表本地 demo。尚未在报告原工程和 Kotlin 2.0.21 工具链重放。


## 实现收敛

副本目录改用本次生成根的序号，每次命中都清理后重建；不计算路径摘要。删除或创建失败时记录 warn 并使用原目录；扫描或复制遇到 I/O、访问权限异常时同样按目录降级。补充目录顺序变化的 L1 回归，验证重建后各根只保留本轮对应文件。

本次定向验证：`GeneratedSourcePathsTest` 2 项、`Issue75EmptyJavaReproTest` 1 项均通过，外层 Gradle 56 秒；`git diff --check` 通过。


## 辅助过滤失败时降级

过滤属于辅助兼容能力。副本存储不可用、扫描或复制遇到 I/O/权限异常时，保留该目录原始输入并继续处理其余目录；不使用不完整副本，不自动重试。原始编译结果由编译器决定。

新增两项 L1 用例保护副本目录创建失败和单根访问失败：修复前均失败（4 项中 2 failures）；验证原路径保留、原文件不变，以及其他根仍完成过滤。

降级修复后验证：`GeneratedSourcePathsTest` 4 项和 `Issue75EmptyJavaReproTest` 1 项全部通过，外层 Gradle 58 秒；`git diff --check` 通过。
