# AndroidTestTopLevelFlowTest 失败链路修复记录

> 创建日期：2026-05-04
> 状态：已修复，目标测试与定向回归测试已通过

---

## 一、背景

目标用例：

`com.sickworm.intellij.jugg.manager.AndroidTestTopLevelFlowTest#androidTestIncrementalDeployRunsUpdatedTestApk`

该用例验证 androidTest 顶层链路：

1. 通过 `BuildTarget.ANDROID_TEST` 完成 app APK + androidTest APK 初次构建和安装。
2. 修改 `app/src/androidTest` 下的测试源码。
3. Jugg 增量编译 androidTest 源文件。
4. 增量部署 test APK 对应的 overlay。
5. 运行新加入的 instrumentation test method，并从 logcat 读取 marker。

---

## 二、失败链路与根因

### 2.1 androidTest manifest merge 失败

现象：

`processDebugAndroidTestManifest` 报 `android:exported` 相关 manifest merge 错误。

根因：

`android_demo_project/app/build.gradle` 中 AndroidX Test 依赖过旧：

- `androidx.test:runner:1.1.1`
- `androidx.test.espresso:espresso-core:3.1.1`

在 demo project 当前 `targetSdkVersion 33` 下，旧版 AndroidX Test 组件 manifest 不满足 Android 12+ 的
`android:exported` 要求。

修复：

升级 app demo 的 androidTest 依赖：

- `androidx.test:core:1.6.1`
- `androidx.test:runner:1.6.1`
- `androidx.test.espresso:espresso-core:3.6.1`

对应文件：

- `android_demo_project/app/build.gradle`

---

### 2.2 concrete APK 输出路径无法派生 test APK glob

现象：

androidTest 模式需要同时部署 app APK 和 test APK，但 run config 中的 `outputApkName` 可能是 concrete path：

`app/build/outputs/apk/debug/app-debug.apk`

原 `AndroidTestCommandDeriver` 只覆盖类似：

`app/build/outputs/apk/debug/*.apk`

因此无法追加：

`app/build/outputs/apk/androidTest/debug/*.apk`

根因：

旧实现用正则匹配 `*.apk` glob，未覆盖 Gradle/RunConfiguration 给出的具体 APK 文件路径。

修复：

将 test APK 路径派生改为基于 `/build/outputs/apk/` 目录结构：

1. 找到 marker `/build/outputs/apk/`。
2. 提取 variant path。
3. 丢弃最后的 APK 文件名或 `*.apk`。
4. 拼出 `androidTest/<variant>/*.apk`。

对应文件：

- `main/src/main/java/com/sickworm/intellij/jugg/gradle/compile/AndroidTestCommandDeriver.kt`
- `main/src/test/java/com/sickworm/intellij/jugg/gradle/compile/AndroidTestCommandDeriverTest.kt`

新增测试：

- `concrete apk output path gets androidTest glob appended`

---

### 2.3 app 与 app.androidTest 共用 moduleRootDir 导致编译串模块

现象：

修改 `AppLogicInstrumentedTest.kt` 后，日志显示待编译文件属于 `app.androidTest`，但 Kotlin 编译参数使用的是 app module 的 debug 配置：

- `-module-name app_debug`
- `-Xjava-source-roots=app/src/main/java`
- `-d app/build/tmp/kotlin-classes/debug`

导致 androidTest 依赖缺失，出现 `androidx.test.platform`、`AndroidJUnit4`、`org.junit` 等 unresolved reference。

根因：

`BaseCompiler` 在拆分编译任务时按 `moduleRootDir.path` 分组。androidTest 支持中，synthetic module
`app.androidTest` 与 app module 共用同一个 `moduleRootDir`：

- `app`
- `app.androidTest`

二者被合并成同一组后，后续 `modulesWithOrder.distinctBy { moduleRootDir.path }` 只保留 app，androidTest 源文件被误交给 app module 编译。

修复：

编译分组 key 改为：

`<moduleName>@<moduleRootDir.path>`

这样同根的不同 source set module 会分开编译。

修复后日志中的关键特征：

- `-module-name app_debugAndroidTest`
- `-Xjava-source-roots=app/src/androidTest/java,...`
- `-d app/build/tmp/kotlin-classes/debugAndroidTest`
- classpath 包含 `junit`、`androidx.test:runner`、`androidx.test:core`、`espresso-core`

对应文件：

- `main/src/main/java/com/sickworm/intellij/jugg/compiler/BaseCompiler.kt`
- `main/src/test/java/com/sickworm/intellij/jugg/compiler/BaseCompilerTest.kt`

新增测试：

- `compile_shouldKeepModulesSeparateWhenTheyShareModuleRootDir`

---

### 2.4 androidTest deploy data 中 APK 数量不是 1

现象：

编译成功后，`MockJuggTestExt.checkCompileResult()` 断言失败：

`expected:<1> but was:<2>`

根因：

普通 app 增量测试只有一个 APK；androidTest 场景的 deploy data 必须同时包含：

- base app APK
- test APK

旧 helper 将 `deployData.apks.size == 1` 写死，不适用于 androidTest。

修复：

`checkCompileResult()` 增加 `apksSize` 参数，默认值仍为 `1`，androidTest 用例传入 `2`。

对应文件：

- `idea/src/test/java/com/sickworm/intellij/jugg/manager/MockJuggTestExt.kt`
- `idea/src/test/java/com/sickworm/intellij/jugg/manager/AndroidTestTopLevelFlowTest.kt`

---

### 2.5 新增 @Test 方法应归类为 hot fix，不是 hot reload

现象：

`checkCompileResult()` 的分类断言失败：

`expected:<0, 0, 1> but was:<0, 1, 0>`

根因：

本次测试资产 `AppLogicInstrumentedTest.incremental.kt` 在测试类中新增了一个 `@Test` 方法：

`incrementalAndroidTestMarker()`

对 `DeployDataGenerator` 来说，这是 class structure change，应进入 `hotFixModifiedClasses`，不是
`hotReloadModifiedClasses`。

修复：

将目标用例期望从：

`hotReloadModifiedClassesSize = 1`

改为：

`hotFixModifiedClassesSize = 1`

对应文件：

- `idea/src/test/java/com/sickworm/intellij/jugg/manager/AndroidTestTopLevelFlowTest.kt`
- `idea/src/test/assets/android/modify_source/app/src/androidTest/java/com/example/myapplication/AppLogicInstrumentedTest.incremental.kt`

---

### 2.6 二阶段 deployAndroidTest 误触发“无文件变化”fallback

现象：

第一次 androidTest 运行成功，修改 androidTest 源并完成增量编译后，再调用 `deployAndroidTest(incrementalSpec)`。

日志显示第二次没有直接部署已编译产物，而是重新进入 compile run task：

```text
No file changes. will fallback to gradle compile.
CommonConfirmDialog.showAndGetOrCancel
IllegalStateException: The showAndGet() method is for modal dialogs only
```

最终没有运行第二次 instrumentation，logcat marker 断言失败。

根因：

`changeFileAndNotify()` 已经调用 `juggManager.compileChanges()`，此时增量产物已写入 staging/deploy data。
测试第二阶段再调用 `juggManager.runTask(...)` 会重新进入编译入口，而 `DeployFileManager` 已经没有新的
uncompiled file，于是走到“无文件变化 fallback”分支。在 test environment 中该分支会触发确认弹窗，导致异常。

修复：

在 `MockJugg` 中新增测试专用入口：

`deployCompiledAndroidTest(spec: AndroidTestRunSpec)`

该入口直接调用 `JuggDeployerHelper.deploy(DeployOptions(..., androidTestRunSpec = spec))`，复用当前已编译好的
deploy data，然后运行 instrumentation。

目标用例第二阶段改为：

`jugg.deployCompiledAndroidTest(incrementalSpec)`

对应文件：

- `idea/src/test/java/com/sickworm/intellij/jugg/manager/MockJugg.kt`
- `idea/src/test/java/com/sickworm/intellij/jugg/manager/AndroidTestTopLevelFlowTest.kt`

---

### 2.7 目标测试需要真实 device，ADB 初始设备列表需等待

现象：

目标用例依赖真实 Android device。测试初始化阶段如果刚创建 `AndroidDebugBridge` 就读取 devices，可能在
initial device list 尚未 ready 时得到空列表。

根因：

`AndroidDebugBridge.createBridge(...)` 返回不代表 `hasInitialDeviceList()` 已经完成。

修复：

`AdbDeviceHelper.init()` 创建 bridge 后等待 initial device list，最长等待 10 秒。

对应文件：

- `idea/src/test/java/com/sickworm/intellij/jugg/mock/AdbDeviceHelper.kt`

---

### 2.8 device 依赖测试的跳过/启动策略抽出

现象：

`AndroidTestTopLevelFlowTest` 属于设备依赖测试。无 device 时应跳过；有可用 AVD 时可自动启动，降低本地验证成本。

修复：

将 `RequiresDeviceRule` 从通用 `Commons.kt` 中独立出来，并抽出可测试的 `RequiresDeviceChecker`：

1. `adb devices` 已有 online device 时直接继续。
2. 没有 device 时读取 `jugg.test.avd` / `JUGG_TEST_AVD` 或第一个 AVD。
3. 启动 emulator 并等待 online。
4. 仍无 device 时用 JUnit assumption 跳过。

对应文件：

- `idea/src/test/java/com/sickworm/intellij/jugg/mock/RequiresDeviceRule.kt`
- `main/src/test/java/com/sickworm/intellij/jugg/mock/RequiresDeviceRuleTest.kt`
- `idea/src/test/java/com/sickworm/intellij/jugg/mock/Commons.kt`
- `main/src/test/java/com/sickworm/intellij/jugg/mock/Commons.kt`

---

## 三、最终测试用例结构

目标用例现在覆盖的关键行为：

1. 初始状态是 `READY_FULL_COMPILE`。
2. `deployAndroidTest(initialSpec)` 触发 Gradle 构建、安装 app/test APK，并运行旧测试方法。
3. 初次运行后进入可增量编译状态。
4. `deployTargetManager.getApks()` 中存在 test APK。
5. 修改 `AppLogicInstrumentedTest.kt` 后，Jugg 增量编译生成 androidTest dex。
6. deploy data 包含 2 个 APK，修改类归类为 hot fix。
7. `deployCompiledAndroidTest(incrementalSpec)` 使用当前增量产物部署并运行新测试方法。
8. logcat 中出现 `JUGG_ANDROID_TEST_INCREMENTAL_MARKER_V2`。
9. 最新项目日志包含 `Apply Changes successfully finished`，证明走了增量 deploy。

---

## 四、涉及文件汇总

| 文件 | 改动原因 |
|------|----------|
| `android_demo_project/app/build.gradle` | 升级 AndroidX Test 依赖，修复 targetSdk 33 下 manifest exported 合并失败 |
| `main/src/main/java/com/sickworm/intellij/jugg/compiler/BaseCompiler.kt` | 按 module name + module root 分组，避免 app 与 app.androidTest 同根混编 |
| `main/src/test/java/com/sickworm/intellij/jugg/compiler/BaseCompilerTest.kt` | 增加同根 module 分组回归测试 |
| `main/src/main/java/com/sickworm/intellij/jugg/gradle/compile/AndroidTestCommandDeriver.kt` | 支持 concrete APK path 派生 androidTest APK glob |
| `main/src/test/java/com/sickworm/intellij/jugg/gradle/compile/AndroidTestCommandDeriverTest.kt` | 增加 concrete APK path 回归测试 |
| `idea/src/test/java/com/sickworm/intellij/jugg/manager/AndroidTestTopLevelFlowTest.kt` | 新增/修正 androidTest 顶层增量部署验证 |
| `idea/src/test/java/com/sickworm/intellij/jugg/manager/MockJugg.kt` | 支持 androidTest 初次 run 与已编译产物的二阶段增量部署 |
| `idea/src/test/java/com/sickworm/intellij/jugg/manager/MockJuggTestExt.kt` | check helper 支持 androidTest 双 APK 场景 |
| `idea/src/test/java/com/sickworm/intellij/jugg/mock/AdbDeviceHelper.kt` | 等待 ADB initial device list，减少设备检测竞态 |
| `idea/src/test/java/com/sickworm/intellij/jugg/mock/RequiresDeviceRule.kt` | 设备依赖测试独立 rule，支持缺设备跳过/启动 AVD |
| `main/src/test/java/com/sickworm/intellij/jugg/mock/RequiresDeviceRuleTest.kt` | 覆盖设备 rule 的在线设备、启动 emulator、跳过测试三种分支 |
| `idea/src/test/assets/android/modify_source/app/src/androidTest/...` | 提供 androidTest 增量修改前后资产，新增 logcat marker 方法 |

---

## 五、验证结果

已通过：

```bash
./gradlew :idea:test --tests "com.sickworm.intellij.jugg.manager.AndroidTestTopLevelFlowTest" --stacktrace
```

结果：

```text
BUILD SUCCESSFUL in 1m 13s
```

已通过：

```bash
./gradlew :main:test --tests "com.sickworm.intellij.jugg.compiler.BaseCompilerTest"
```

结果：

```text
BUILD SUCCESSFUL in 3s
```

已通过：

```bash
./gradlew :main:test --tests "com.sickworm.intellij.jugg.gradle.compile.AndroidTestCommandDeriverTest"
```

结果：

```text
BUILD SUCCESSFUL in 7s
```

注意：

未运行全量 `./gradlew :main:test` / `./gradlew :idea:test`，遵守仓库测试规范，避免本机内存压力。

---

## 六、结论

本次失败不是单点问题，而是 androidTest 顶层链路首次端到端跑通时连续暴露的组合问题：

1. demo 依赖过旧导致 test APK manifest 无法构建。
2. androidTest APK 路径派生没有覆盖 concrete APK path。
3. 编译层按 `moduleRootDir` 分组导致 synthetic androidTest module 被 app module 吞掉。
4. 测试 helper 没有表达 androidTest 双 APK 事实。
5. 用例对新增 test method 的热更分类预期错误。
6. 二阶段测试调用方式重新进入编译入口，触发无文件变化 fallback。
7. 设备依赖测试需要更稳定的 device 检测。

修复后，目标用例已能验证：

- app APK + androidTest APK 初次构建与安装；
- androidTest 源文件按 `app.androidTest` 独立增量编译；
- test APK 对应增量 deploy 成功；
- 新增 instrumentation method 实际运行并输出 logcat marker。
