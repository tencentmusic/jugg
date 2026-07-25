# KMP Compose Fixture 默认启用与 Demo 多版本兼容方案

## 1. 目标

将 Compose `1.7.3` 资源编译的 demo fixture 从手工开关改为受支持配置下默认启用：

1. Kotlin `2.1` 的 demo 构建不再需要 `-PenableKmpComposeFixture=true`。
2. `app/build.gradle`、`app/build.gradle.kotlin1.7`、`app/build.gradle.kotlin2.1` 都能正确处理 `:kmpCompose` 依赖是否存在。
3. Kotlin `1.7` 版本切换仍可正常构建和执行现有 KSP1 回归；它不宣称支持 Compose `1.7.3` 增量资源编译。
4. 删除所有运行时、测试和任务文档中的 `enableKmpComposeFixture`。

## 2. 已确认事实

- Compose 资源增量链路只接受 Compose `1.7.3` 与 Kotlin `2.1.x`。`GradleProjectInfoReader` 会要求 `compose-gradle-plugin-1.7.3.jar` 和 `kotlin-stdlib-2.1.*.jar`。
- `kmpCompose` 应用了 `org.jetbrains.kotlin.multiplatform`、`org.jetbrains.compose` 和 `org.jetbrains.kotlin.plugin.compose`。这三个 plugin 的版本声明目前只存在于 `build.gradle.kotlin2.1`。
- 当前 `settings.gradle` 与 `app/build.gradle.kotlin2.1` 都以 `enableKmpComposeFixture` 控制模块与 app 依赖；现有 KMP L2/L3 Flow 因此在命令中传入该属性。
- 直接将 `include ':kmpCompose'` 改为无条件执行会破坏 Kotlin `1.7` 配置：该配置既没有 Compose Multiplatform plugin 声明，也不满足 Jugg Compose 资源链路的 Kotlin `2.1.x` 前提。

## 3. 推荐决策

移除测试 fixture 属性，但保留由实际 Kotlin 版本表达的兼容性边界：

```groovy
def isKmpComposeSupported = providers.gradleProperty('kotlinVersion').orNull?.startsWith('2.1.')
if (isKmpComposeSupported) {
    include ':kmpCompose'
}
```

含义如下：

| 当前 demo 配置 | `:kmpCompose` | app 依赖 | Compose 资源增量 |
|---|---:|---:|---:|
| Kotlin `2.1` | 自动包含 | 自动加入 | 支持 |
| Kotlin `1.7` | 不包含 | 不加入 | 不支持，既有 demo/KSP1 构建保持可用 |

这满足“默认打开”的目标：用户切换至支持的 Kotlin `2.1` demo 后，任何 Gradle/Jugg 命令都无需额外 feature property。版本条件不是隐藏开关，而是对已有支持矩阵的显式约束。

不建议把 `build.gradle` / `build.gradle.kotlin1.7` 整体升级到 Kotlin `2.1`，因为这会取消 Kotlin `1.7`/KSP1 回归配置，超出本次 demo fixture 默认启用的范围。

## 4. 实施步骤

### Step 1：先更新失败测试

1. 在 `idea/src/test/java/com/sickworm/intellij/jugg/manager/JuggCompilerTest.kt` 的 `KmpComposeFlowReproTest` 中删除 assemble、refresh 和 compile command 的 `-PenableKmpComposeFixture=true`。
2. 在 `idea/src/test/java/com/sickworm/intellij/jugg/manager/TopLevelFlowTest.kt` 的 `KmpComposeDeployFlowTest` 中删除同一属性。
3. 此时 Kotlin `2.1` fixture assemble 后不会出现 `kmpCompose` project info，L2/L3 应先失败，证明测试覆盖的是默认启用行为而非旧开关。

测试层级：

| 测试 | 层级 | 证明内容 |
|---|---|---|
| `KmpComposeFlowReproTest` | L2 | Kotlin `2.1` 下全量构建、Gradle project info 和 Jugg 增量编译均无需 feature property |
| `KmpComposeDeployFlowTest` | L3 | 真机完整安装、Compose 资源增量编译/部署/运行无需 feature property |
| `KotlinCompileTest#testKsp1Compile` | 既有回归 | 切回 Kotlin `1.7` 后 KSP1 demo 仍能 assemble 并通过增量 Kotlin 编译 |

### Step 2：改为基于版本自动包含模块

修改 `android_demo_project/settings.gradle`：

1. 删除 `enableKmpComposeFixture` 属性判断。
2. 根据 `kotlinVersion` 是否为 `2.1.*` 自动 `include ':kmpCompose'`。
3. 在版本判断处添加一条英文注释，说明 `kmpCompose` 与 Jugg Compose 资源链路都要求 Kotlin `2.1`。

这里不修改 `kmpCompose/build.gradle`、Compose 版本或 Jugg 编译器能力；Compose `1.7.3` 支持范围保持不变。

### Step 3：补齐三个 app 脚本

修改以下文件：

- `android_demo_project/app/build.gradle`
- `android_demo_project/app/build.gradle.kotlin1.7`
- `android_demo_project/app/build.gradle.kotlin2.1`

在 `dependencies` 中统一用项目存在性判断声明依赖：

```groovy
if (findProject(':kmpCompose') != null) {
    implementation project(':kmpCompose')
}
```

`build.gradle.kotlin2.1` 删除旧的 `project.findProperty('enableKmpComposeFixture')` 判断。其他两个脚本也使用相同的模块存在性判断，因此模板在 Kotlin `1.7` 下不会引用未 include 的项目，而 Kotlin `2.1` 下会自动加入 fixture。

根 `build.gradle.kotlin2.1` 已提供三个所需 plugin 的 version declaration，不需要修改。根 `build.gradle` 与 `build.gradle.kotlin1.7` 不引入 Compose `1.7.3` plugin，避免误导为旧 Kotlin 配置可运行该 fixture。

### Step 4：移除属性的残留引用并同步说明

1. 更新 `KmpComposeFlowReproTest`、`KmpComposeDeployFlowTest` 中所有命令常量和 `ProcessBuilder` 参数。
2. 更新 `docs/task/kmp_compose_incremental_compile_support_plan.md` 与 `docs/task/2026-07-25-compose-1.7.3-incremental-resource-implementation-plan.md`：删除“通过 property 启用”的描述和带 property 的命令。
3. 更新 `android_demo_project/test-version-switch.sh`：断言 Kotlin `1.7` 不会暴露 KMP Compose fixture，Kotlin `2.1` 的配置不含旧 property gate；不再检查或写入该 property。
4. 用 `rg -n "enableKmpComposeFixture"` 确认仓库中没有残留引用。

`docs/ai_knowledge` 的 Compose 支持版本与运行链路描述不变，因此无需修改知识库专题文档。

## 5. 验证顺序

按 TDD 顺序先完成 Step 1，再实施 Step 2 和 Step 3。

```bash
# Kotlin 2.1 的项目模型与增量编译协作
./gradlew :idea:test --tests 'com.sickworm.intellij.jugg.manager.KmpComposeFlowReproTest'

# Kotlin 2.1 的用户可见部署主链路，需要已连接测试设备
./gradlew :idea:test --tests 'com.sickworm.intellij.jugg.manager.KmpComposeDeployFlowTest'

# Kotlin 1.7/KSP1 回归
./gradlew :idea:test --tests 'com.sickworm.intellij.jugg.compile.KotlinCompileTest.testKsp1Compile'

# 版本切换脚本的静态配置回归
(cd android_demo_project && ./test-version-switch.sh)

# 编译验证
./gradlew :idea:compileKotlin
```

执行包含切换版本的验证后，脚本必须恢复任务开始时的 demo 配置；不得把 Gradle wrapper、`gradle.properties` 或 active build script 的切换副作用带入提交。

## 6. 文件清单与提交

| 文件 | 改动目的 |
|---|---|
| `android_demo_project/settings.gradle` | 用 Kotlin `2.1` 兼容性判断替代 fixture property |
| `android_demo_project/app/build.gradle` | 在模块存在时加入 KMP fixture 依赖 |
| `android_demo_project/app/build.gradle.kotlin1.7` | 保证 Kotlin `1.7` 模板不依赖不存在的 project |
| `android_demo_project/app/build.gradle.kotlin2.1` | 默认加入 KMP fixture，删除 property gate |
| `android_demo_project/test-version-switch.sh` | 覆盖版本切换后的 fixture 可见性 |
| `idea/.../JuggCompilerTest.kt` | L2 命令移除 property 并证明默认启用 |
| `idea/.../TopLevelFlowTest.kt` | L3 命令移除 property 并证明默认启用 |
| 两份 Compose task plan | 删除已废弃的启用方式 |

建议单一提交：`[feature] enable Compose fixture by default for supported demo builds`

## 7. 验收标准

1. Kotlin `2.1` 下 `./gradlew :app:assembleDebug` 和 Jugg Compose Flow 都不需要 `enableKmpComposeFixture`。
2. Gradle project info 中存在 `kmpCompose`，且 Compose resource metadata 的状态为 `Supported`。
3. L2 编译协作测试与 L3 真机运行测试继续覆盖 default/custom Compose resource 资源变更。
4. Kotlin `1.7` 的 KSP1 回归不因 fixture 默认启用而失败。
5. 仓库中不再出现 `enableKmpComposeFixture`。
