# Jugg Report db48845d KMP 受影响文件编译失败调查

创建日期：2026-09-17。状态：已重新分析，当前无法复现，不进行修复。

## 1. 用户现象

用户在 KMP Android 模块 `inskmpeditsdk` 中修改 UI 代码。Jugg 首轮增量编译成功，随后因依赖分析发现受影响源码而继续编译，第二轮出现大量 `unresolved reference`。

失败发生在第二轮编译以下文件时：

- `KMPStoryMainPagePreview.kt`
- `KMPStoryUIStateCalculator.kt`

## 2. 已确认环境

报告 `db48845d` 可以确认：

- Gradle：`8.13`
- Kotlin Gradle Plugin / Kotlin compiler：`2.1.20`
- Android 编译任务：`:inskmpeditsdk:compileDebugKotlinAndroid`
- 用户失败模块的 Kotlin 输出目录：`build/tmp/kotlin-classes/debug`
- 第二轮 kotlinc 的 `-d`、`-cp` 和 `-Xfriend-paths` 均已包含该真实输出目录

日志中的 Android tooling 依赖为 `8.9.1`，但当前证据不足以把问题归因于 AGP 新旧产物混合。

## 3. 编译时序与失败边界

### 3.1 首轮编译成功

首轮编译 7 个变更文件，kotlinc 将 class 和模块元数据直接写入：

```text
build/tmp/kotlin-classes/debug
```

本轮同时输出：

```text
META-INF/inskmpeditsdk_debug.kotlin_module
```

kotlinc 的输出报告显示，该次生成的 `.kotlin_module` 只关联本轮参与编译的源码。

### 3.2 Jugg 触发第二轮受影响文件编译

首轮成功后，Jugg 输出：

```text
Compile success, but found effected source files, continue compile.
Files: [KMPStoryMainPagePreview.kt, KMPStoryUIStateCalculator.kt]
```

第二轮仍使用正确的 `build/tmp/kotlin-classes/debug` 作为当前模块 classpath，不存在目标模块 Kotlin class 目录遗漏。

### 3.3 第二轮集中丢失 Kotlin 顶层声明

失败符号主要包括：

- 顶层常量：`PLAYHEAD_POINT_TOLERANCE_MS`、`MAIN_TRACK_ID`、`MAIN_SPEED_OVERLAY_TYPE`、`ORIGINAL_AUDIO_DECORATION_ID`
- 顶层函数或扩展：`nearestPointSelectionAt`、`and`、`computePovComponentVisibility`
- Compose 生成的顶层资源访问器：`story2_pov_analyze_apei_fullscreen`、`story2_pov_analyze_forward`、`story2_pov_analyze_protagonist`

这些符号依赖 Kotlin package part / `.kotlin_module` 元数据发现。普通 class 依赖没有出现同等范围的整体丢失。

## 4. 已排除的错误假设

### 4.1 不存在现场证据支持 `classes/kotlin/android/debug`

此前根据 `classes/kotlin/android/main` 外推了以下目录：

```text
classes/kotlin/android/debug
classes/kotlin/androidDebug
classes/kotlin/androidDebug/main
classes/kotlin/debug
```

该推断错误，原因如下：

- `classes/kotlin/android/debug` 在报告日志中出现 0 次。
- 用户失败模块实际使用 `build/tmp/kotlin-classes/debug`。
- `classes/kotlin/android/main` 只出现在 `foundation`、`hybridCache` 等依赖模块中，不能用于推导失败模块的 variant 输出。
- Demo 中通过 `destinationDirectory.set(...)` 强制创建该路径，只能验证人为目录，不能复现用户自然场景。

因此不能把本问题描述为“Jugg 未覆盖用户 Kotlin 版本产生的 KMP Android variant 输出目录”。

### 4.2 不是目标模块 classpath 缺失

第二轮 kotlinc 命令已同时包含：

```text
-Xfriend-paths=<module>/build/tmp/kotlin-classes/debug
-d <module>/build/tmp/kotlin-classes/debug
-cp ...:<module>/build/tmp/kotlin-classes/debug:...
```

增加不存在的候选目录不会改变该失败命令，也不能解决报告中的 unresolved references。

### 4.3 尚无证据支持 AGP 新旧产物混合

报告没有显示同一模块在两套 AGP Kotlin 输出之间切换，也没有显示 classpath 选中了旧版本目录。当前不应继续保留该候选根因。

### 4.4 `commonSourceFiles=[]` 不是独立根因证据

原报告两轮编译都记录了：

```text
commonSourceFiles=[]
isNeedComplementaryFiles=false
expectActualSourceFiles=[]
```

当前实现只在本轮源码命中 `expect` / `actual` 时补充 KMP complementary sources 和相应编译参数。报告中的普通 `commonMain` 文件没有命中该分支，因此上述值符合现有设计。它仍可作为后续项目差异排查点，但不能仅凭空数组认定第二轮缺少 KMP 参数。

## 5. 重新分析后的根因判断

目前能够确认的仅是故障边界：

> 用户项目在 Kotlin 2.1.20 的第二轮 affected-file 编译中，无法解析未参与本轮编译的同模块 Kotlin 顶层声明。

现有证据不足以继续认定 `KmModuleMergerForCompilation` 是根因：

- 两轮日志都没有记录 `loadAndMerge()` / `save()` 异常或降级。
- 报告未包含全量构建后、首轮原始输出后、合并后及第二轮前的 `.kotlin_module` 文件，无法证明 package parts 在哪一步丢失。
- 相同 Kotlin 版本、相同自然输出目录和相同多轮 affected-file 链路已在仓库 Demo 中成功，说明“Jugg 的 Kotlin 2.1.20 多轮元数据合并必然失败”不成立。

因此，元数据合并仍是需要现场产物验证的候选方向，而不是已确认根因。用户项目特有的 source set 组合、生成源码布局、首轮具体 package parts 或编译前基线产物均可能构成尚未复现的必要条件。

## 6. 自然复现结果

### 6.1 复现场景

复现严格使用自然生成的 `build/tmp/kotlin-classes/debug`，没有覆盖 `destinationDirectory`：

1. 将 Demo 切换到 Kotlin `2.1.20`。
2. 首轮只修改并编译 `commonMain` 的 `CrossModuleLog.kt`，通过方法签名变化触发真实影响分析。
3. 让影响分析自然选中 `commonMain` 的 `ComposeResourceConsumer.kt`。
4. 受影响文件同时引用未参与首轮编译的普通顶层函数和 Compose 生成的顶层资源访问器。
5. 检查第二轮 Kotlin 编译结果、未编译文件集合及最终 DEX 输出。

Demo 中与目标问题无关的 `romhiddenapi` 旧兼容样例在 Kotlin 2.1.20 全量基线阶段无法编译；复现时仅排除该目录以建立 KMP 基线，复现结束后已撤销这一临时调整。

### 6.2 结果

定向 Flow 在 Kotlin `2.1.20` 下通过：

- 首轮成功输出 `CrossModuleLog`。
- 影响分析发现 `ComposeResourceConsumer.kt` 并进入第二轮。
- 第二轮能够解析普通顶层函数和 Compose 生成的资源访问器。
- 第二轮输出 `ComposeResourceConsumerKt.class`，随后生成 `ComposeResourceConsumerKt.dex`。
- 未出现 `unresolved reference`，未遗留未编译文件。

同一场景在 Demo 默认 Kotlin `2.1.0` 下也通过。两个版本均未复现报告故障，临时复现代码和版本调整已全部撤销，不保留不能证明修复价值的自动化测试。

## 7. 修复门禁结论

本轮不修改生产代码，原因是：

- 原报告能够证明用户现场失败，但不能证明具体失效组件。
- 仓库中最接近现场的自然 affected-file Flow 无法复现。
- 此时修改元数据合并或 KMP 参数会依赖猜测，既无法证明命中用户问题，也可能改变现有成功路径。

只有取得稳定失败证据后才进入修复。满足以下任一条件即可重新启动：

1. 提供可移植的最小项目，使相同的首轮和第二轮操作稳定出现 unresolved references。
2. 提供用户现场四个阶段的同名 `.kotlin_module`：全量构建后、首轮 kotlinc 原始输出后、Jugg 合并后、第二轮编译前。
3. 提供足以在 Demo 中恢复必要条件的源码与生成源码结构，并能先形成失败 Flow。

恢复调查后，应先比较 package parts 和 file facades，定位第一个发生差异的阶段；确认 behavior owner 后先保留失败回归，再做最小生产修复。

## 8. 被撤销的错误修复

以下提交基于未经现场证实的输出目录假设，应从分支历史移除：

- `17d19fc33`：增加 KMP Android variant 输出目录候选
- `910b91f76`：通过强制 `destinationDirectory` 构造 `classes/kotlin/android/debug` 的 Flow 测试

这两个提交均未命中报告中的真实失败边界，现已从分支历史移除。中间无关提交
`3e9625018` 已在改写后以 `5a5b554b6` 保留。

## 9. 报告关键日志位置

报告文件：`compile_2026-09-17_10-10-59.0.log`

- `2881`：首轮 kotlinc 命令及真实输出目录
- `3133`：首轮输出 `inskmpeditsdk_debug.kotlin_module`
- `3417`：发现两个受影响文件并触发第二轮编译
- `3441`：第二轮 kotlinc 命令，classpath 已包含真实输出目录
- `3866` 起：Compose 资源访问器和同模块顶层声明 unresolved references

本地复现证据：

- `idea/build/test-results/test/TEST-com.sickworm.intellij.jugg.manager.KmpComposeFlowReproTest.xml`：Kotlin 2.1.20 定向 Flow 通过
- `android_demo_project/build/jugg/log/compile_2026-09-17_15-27-32.0.log`：影响分析、第二轮 Kotlin 成功及 DEX 输出
