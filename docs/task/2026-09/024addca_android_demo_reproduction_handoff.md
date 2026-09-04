# Jugg Report 024addca 本地复现交接

## 目标

在 `android_demo_project` 中最小化复现报告 `024addca` 的 Kotlin 2.0.21 增量编译异常：

```text
Internal error: Exception during IR lowering
java.lang.AssertionError:
Dispatch receiver type PlayerGeneralSongInfoFragment
is not a subtype of AbsPlayerFragment
at org.jetbrains.kotlin.ir.util.IrUtilsKt.copyValueParametersToStatic(IrUtils.kt:1060)
```

本阶段只要求本地复现，不修改 Jugg 生产代码，不提交临时用例。

**结论（2026-09-04）：暂定为无法复现。** Demo 最小用例未能打出目标 `IR lowering` / `copyValueParametersToStatic` 断言；JOOX vinyl HEAD 对齐报告脏文件的三次本地增量编译均成功。现场临时 fixture 与 Kotlin 2.0.21 profile 已清理。

判定为成功复现必须同时满足：

1. 首轮编译源码输入含 Base 与 Leaf，Middle 不在首轮输入中。
2. 报错为 `Exception during IR lowering`。
3. 栈包含 `SyntheticAccessorLowering`。
4. 栈包含 `IrUtilsKt.copyValueParametersToStatic(IrUtils.kt:1060)`。
5. Assertion 文本表达 Leaf receiver 不是 Base 的子类型。

如果只是后续受影响文件补编译失败，或出现 `unresolved reference '<init>'`，或出现下面已记录的 `No override for FUN`，都不算相同断言复现。`No override for FUN` 可作为同源身份分裂证据，但不能替代上述 5 条。

## 安全边界

- 不使用 Web、curl、远程 API 或报告上传功能。
- Gradle 验证应添加 `--offline`。
- `jugg.py` 仅连接本机 localhost Jugg MCP 服务。
- 不运行 `deploy`，不连接设备。
- 不修改或恢复根工程已有的用户改动 `build.gradle`。
- 失败后不要立刻再跑第二次 `jugg compile`，避免自动 fallback Gradle 覆盖现场。换 fixture 时先 `gradle-build` 重建 baseline。

## 已读取依据

- `docs/ai_knowledge/00_overview.md`
- `docs/ai_knowledge/99_index.md`
- `docs/ai_knowledge/98_code_map.md`
- `docs/ai_knowledge/09_plugin_runtime_debug.md`
- `docs/ai_knowledge/02_compile_source.md`
- `docs/ai_knowledge/06_testing.md`
- jugg-android-dev-loop skill / `flow_compile_deploy.md`
- issue-handler skill
- JOOX `origin/feature/9.11/136687964_player_vinyl_style` 与报告 commit `f86b020da4e50a1ea55534df1e2d5c739b3bd744`
- `KotlinCompilerInvoker.createIsolatedKmpBaseline()`：隔离只对 KMP expect/actual complementary 生效

## 报告与 JOOX 源码对照

报告日志：

```text
/Users/wormchen/Downloads/apks/dump_ext/10096_024addca/diagnostics/logs/compile_2026-09-03_17-49-46.0.log
```

失败发生在 2026-09-04 10:22，首轮 Kotlin 输入包括：

- `AbsPlayerFragment.kt`（脏 Base）
- `PlayerGeneralSongInfoFragment.kt`（脏 Leaf）
- `PlayerGeneralTopBarFragment.kt`（脏，直接继承 Base）
- `IPlayerUIViewModel.kt` / `PlayerUIViewModel.kt`（脏，是 `viewModel` 的类型）
- 其它若干 fragment / reporter
- **没有** `AbsPlayerPagerCellFragment.kt`

当前工作区 `JOOX_Android_4` 在 `release/9.11`，没有这些类。源码在：

```text
origin/feature/9.11/136687964_player_vinyl_style
```

失败时的真实继承（vinyl 分支 HEAD，比 baseline commit `f86b020` 更新）：

```text
AbsPlayerFragment                          package ...fragment
        ↑
AbsPlayerPagerCellFragment                 package ...fragment.pager   ← 未脏，来自 baseline
        ↑
PlayerGeneralSongInfoFragment              package ...fragment.general
```

`f86b020` 里 SongInfo 曾直接继承 `AbsPlayerFragment`；失败编译时源码已改成走 Middle。中间类名是 `AbsPlayerPagerCellFragment`，不是交接初稿里的 `AbsPlayerPagerSubCellFragment`。

同批还有 `PlayerGeneralTopBarFragment : AbsPlayerFragment(...)`，即脏叶子同时存在「直连 Base」和「经 Middle」两条链。

Base 上与 synthetic accessor 相关的成员：

- `protected val viewModel: IPlayerUIViewModel by playerUIViewModel()`
- `protected fun collectFlow(...)`
- `protected inline fun <reified T : Fragment> addOrReplaceFragment(...)`（SongInfo 未直接调用）
- Middle 内有 `private inner class EcoFlow`，调用 `collectFlow`

SongInfo 自身通过协程 lambda 访问 `viewModel`：

```kotlin
viewLifecycleOwner.lifecycleScope.launch {
    viewModel.likeEvent.collect { ... }
}
```

三类不在同一 package。这是 JVM protected 需要 synthetic accessor 的前提。

## 当前根因判断

已确认事实：

- Jugg 普通 Kotlin/JVM 编译把 `tmp/kotlin-classes` 同时当作 `-d`、`-cp` 和 `-Xfriend-paths`。脏源码对应的旧 class 仍留在 classpath。
- KMP 才会 `createIsolatedKmpBaseline()` 去掉 dirty class；普通模块不会。
- 脏 Base 源码 + classpath Middle + 脏 Leaf 足以在 Kotlin 2.0.21 增量编译中产生 **source/binary 同名类型身份分裂**。

当前假设：

- 报告断言发生在 `SyntheticAccessorLowering` 为 Base(source) 的 protected 成员生成 static accessor 时，dispatch receiver 是 Leaf，而 Leaf 的 IR 超类链经 Middle(binary) 指向 Base(binary)，因此 `Leaf <: Base(source)` 失败。
- 要打到这个断言，调用必须绑定到 Base(source) 成员，且 accessor 建在 Base 上，而不是被 JVM lowering 改写成 Leaf 上的 `access$xxx`。

## Android demo 临时环境

```text
/Users/wormchen/IdeaProjects/jugg/jugg/android_demo_project
```

仍使用：

```properties
kotlinVersion=2.0.21
kspVersion=2.0.21-1.0.27
org.gradle.java.home=/Users/wormchen/Library/Java/JavaVirtualMachines/corretto-17.0.17/Contents/Home
```

任务结束后用 `./switch-kotlin-version.sh 1.9` 恢复，不要 `git checkout` 覆盖用户改动。

## 当前 fixture 文件

```text
android_demo_project/app/src/main/java/com/sickworm/jugg/demo/testcase/kotlinbaselineidentity/
  base/BaselineIdentityBase.kt
  base/BaselineIdentityState.kt
  middle/BaselineIdentityMiddle.kt
  leaf/BaselineIdentityLeaf.kt
  leaf/BaselineIdentityDirectLeaf.kt
```

这是「protected 委托属性 + suspend lambda + 脏 interface 类型」版本，最近一次增量编译 **成功**，不能当作失败现场。

## 已排除 / 已部分验证的用例

### 用例一：只改变继承关系

结果：编译成功。混合源码/baseline 继承链本身不够。

### 用例二：Base protected inline 调 protected value

结果：首轮成功，随后 Middle 补编译 `unresolved reference '<init>'`。不是目标断言。

### 用例三：同包 lambda 调 protected value

交接时已 `gradle-build`。后续增量 `jugg compile`（job `1c8f9c1f-9f68-4d8e-815a-b1036344f848`）**成功**。

javap 显示 lambda 被降成 Leaf 上的 `readValue$lambda$0`，`invokevirtual value()`，不经过 `SyntheticAccessorLowering` 为 Base 建 accessor。同包 + invokedynamic 不够。

### 用例四：跨包 + named inner class + 脏 Base/Leaf

首轮输入只有 Base+Leaf，Middle 不在输入中，**编译成功**。

javap：accessor 在 Leaf 上（`Leaf.access$value`），不在 Base 上。

### 用例五：abstract + 构造参数 + 匿名对象 + 直连脏叶子

对齐 JOOX：Base 抽象且带 `(tag, layoutId)`；Middle 透传；`DirectLeaf` 直连 Base；`Leaf` 经 Middle。首轮编译 3 个脏文件，**成功**。

### 用例六：internal inline 调 private —— 同源身份分裂已打到

日志：

```text
android_demo_project/build/jugg/log/standlone_cli/compile_2026-09-04_12-35-15.0.log
```

首轮输入：`BaselineIdentityBase.kt`、`BaselineIdentityDirectLeaf.kt`、`BaselineIdentityLeaf.kt`，Middle 不在输入中。

异常：

```text
java.lang.IllegalStateException: No override for FUN name:value visibility:internal
modality:FINAL <> ($this:...BaselineIdentityBase) returnType:kotlin.Int [inline]
in CLASS CLASS name:BaselineIdentityLeaf
superTypes:[...BaselineIdentityMiddle]
at org.jetbrains.kotlin.backend.common.actualizer.SpecialFakeOverrideSymbolsResolver.getReferencedSimpleFunction(SpecialFakeOverrideSymbolsResolver.kt:303)
```

这说明 Leaf 的 IR 超类是 Middle(binary)，找不到 Base(source) 上的 inline `value`。与报告是同一类身份分裂，但失败点在 fake override actualizer，还没走进 `SyntheticAccessorLowering`。

Kotlin 2.0 不允许 `protected inline` 访问 `private`（Public-API inline error），所以用了 `internal inline` + `private`。Gradle 全量编译该形态合法，且 Base 上有 `access$secret(Base)`。

不要把这次失败当目标复现。

### 用例七：protected 委托属性 + suspend lambda + 脏 interface

对齐 `viewModel: IPlayerUIViewModel` 与协程 lambda。首轮 4 文件编译 **成功**。suspend lambda 的 accessor 仍在 Leaf：`Leaf.access$getViewModel`。

## 关键实现观察

Kotlin 2.0.21 对「子类嵌套类/协程 lambda 访问父类 protected」通常把 accessor 放在 **Leaf**，全量 javap 为 `Leaf.access$xxx(Leaf)`。报告断言的 parent 是 **AbsPlayerFragment**，说明当时 lowering 仍在对 Base(source) 做 `copyValueParametersToStatic`。

要打到目标断言，需要让调用绑定到 Base(source)，并且在 fake override 阶段不要先炸（避免用例六），同时 subtype check 使用分裂后的符号。

Compose live literals 在 demo 中始终开启，单独不足以触发。

## JOOX 工程 Jugg CLI 实验（2026-09-04）

工程：`/Users/wormchen/IdeaProjects/joox/JOOX_Android_4`  
IDEA runtime，`kotlin_version=2.0.21`，Gradle fallback 为 remote。

`release/9.11` 没有这套播放器类。已 stash `LocalDebugLoginHelper.kt`，切到 `origin/feature/9.11/136687964_player_vinyl_style`，本地分支名 `jugg-repro-024addca`。远程 `gradle-build` 本身 `BUILD SUCCESSFUL`（约 417s / 再跑 68s），CLI 因无设备 install 报 ERROR，不影响增量 classpath。结束后已切回 `release/9.11` 并 stash pop。

三次本地增量 `jugg compile`（`isGradleCompile=false`，未 deploy）均成功，Middle `AbsPlayerPagerCellFragment.kt` 均不在输入中：

1. 仅 `AbsPlayerFragment.kt` + `PlayerGeneralSongInfoFragment.kt`：成功。
2. 6 文件（再加 TopBar / Cover / Lyric / Reporter）：成功。
3. 对齐报告的 9 文件（再加 `IPlayerUIViewModel` / `PlayerUIViewModel` / `PlayerUISharedEvent`）：成功。日志 `build/jugg/log/compile_latest.log`，job `ee7355d9-adf7-4139-b514-c44f5ccef1b7`。无 `IR lowering` / `copyValueParametersToStatic`。

结论：在当前 vinyl HEAD + 刚打过的 remote Gradle 基线上，报告同批脏文件不足以稳定打出目标断言。报告现场可能还依赖当时未提交的工作区差异，或该 Kotlin 身份分裂是偶发的。

## 建议的下一实验

优先让 accessor 建在 Base 上，且成员不要是 `inline`（避免用例六提前失败）：

1. 恢复用例六的跨包 abstract/Middle/DirectLeaf 骨架。
2. Base 使用 **非 inline** 的 `protected` 成员，但调用点不能被降成 Leaf 方法。可试：
   - 独立文件中的 local/匿名类（不是 Leaf 的 inner）
   - 或对照 `kotlinc`：classpath 保留旧 `Base.class` + `Middle.class`，源码只给新 Base+Leaf
3. 不要覆盖 `compile_2026-09-04_12-35-15.0.log`；它是目前唯一的身份分裂现场。

## 完成后的恢复步骤

已于 2026-09-04 执行：

1. 已删除 `kotlinbaselineidentity/` 下全部临时 Kotlin 文件及空目录。
2. 已在 `android_demo_project` 运行 `./switch-kotlin-version.sh 1.9`，`kotlinVersion` 回到 `1.9.22`，`.kotlin-version-backup` 已清除。
3. `git status --short`：复现现场已干净。仍保留任务开始前的无关本地改动（根工程 `build.gradle`、`android_demo_project/app/build.gradle`、若干 wiki 页面）。

## 后续修复方向（尚未授权实施）

普通 Kotlin 增量编译为本轮脏源码对应的 baseline class 建立隔离 classpath，复用已有 `createIsolatedKmpBaseline()` 思路，但不要把隔离范围限制在 expect/actual。实施前仍需目标断言的失败证据，并定位 compiler flow 测试 owner。

## 提交状态

- 没有提交任何复现 fixture 或生产代码改动。
- 临时源码和 Kotlin 2.0.21 profile 已清理。
- 根工程 `build.gradle` 的既有用户改动与本任务无关，必须保留。
