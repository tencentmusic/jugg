# App 启动 Activity 降级方案

> 创建日期：2026-09-15
> 状态：已实现
> 决策：App 启动目标按 launch Activity、HOME Activity、stop App 的顺序降级。

## 1. 背景与现状

Jugg 当前通过 `AdbCmdHelper.startDefaultApp()` 遍历本轮 APK 文件，对每个 APK 调用 `IDeviceAdb.getDefaultLaunchActivity()`。只要找到 launch Activity，就执行：

```text
am start [-D] [-S] -n <package>/<activity>
```

如果所有 APK 都没有 launch Activity，当前实现只打印：

```text
No default launch activity found for <package>, won't start App.
```

随后直接返回，不启动 Activity，也不额外 stop App。由于没有抛出异常，上层 `DeployTargetManager.startApp()`、`restartApp()` 和 `restartAppForDebug()` 仍返回成功。

该行为无法覆盖桌面 App 和没有 LAUNCHER 入口但声明了 HOME Activity 的 App。

## 2. 目标

将 App 启动目标统一调整为以下优先级：

1. 保持现有 launch Activity 选择和启动行为；
2. 所有 APK 都没有 launch Activity 时，使用 HOME Activity；
3. 所有 APK 都没有 launch、HOME Activity 时，执行现有 `stopApp()`，即 `am force-stop <package>`。

该规则同时覆盖：

- Gradle install 后启动 App；
- 增量部署后 restart/start App；
- Recover 中为建立 deployable 状态执行的 restart；
- Debug 部署后的 restart；
- IDE Restart App 入口；
- MCP/CLI `restart` 入口。

## 3. 行为定义

### 3.1 候选优先级

| 优先级 | 候选条件 | 选择规则 | 最终动作 |
|---|---|---|---|
| 1 | `MAIN` + `LAUNCHER` 或 `LEANBACK_LAUNCHER` | 完全复用现有 `DefaultApkActivityLocator` 逻辑：单 APK 多候选时优先带 `DEFAULT` 的 launcher，否则取第一个 | `am start`；restart 时附带 `-S`，Debug 时附带 `-D` |
| 2 | enabled、exported、组件名有效，且包含 `MAIN` + `HOME` | 按 APK 顺序和 Manifest 声明顺序取第一个 | 同上 |
| 3 | 前两类均不存在 | 不再伪装成已启动 | `am force-stop <package>` |

不把“第一个可启动 Activity”作为候选：Manifest 声明顺序无法表达业务首页，且大量普通 Activity 既未声明 `exported=true` 也没有 intent-filter，`am start -n` 会稳定失败。HOME 候选必须声明 `MAIN` + `HOME`，这是系统认可的入口语义，也是唯一可以可靠显式启动的非 launcher 候选。

`activity-alias` 继续沿用现有 Manifest 模型并参与 HOME 候选选择。HOME 候选执行显式启动时使用 alias 自身的 qualified name，避免绕过 alias 后启动未导出的 target Activity。现有 launch Activity 路径保持当前 `realActivityQname` 行为，不在本功能中调整。

### 3.2 多 APK 顺序

优先级必须跨整个 APK 集合分阶段执行：

```text
全部 APK 查 launch Activity
  -> 未找到：全部 APK 查 HOME Activity
  -> 未找到：stop App
```

不能对每个 APK 依次执行“launch -> HOME”。否则 base APK 中的 HOME Activity 会抢在后续 split APK 的 launch Activity 前面，破坏“launch Activity 第一优先”的契约。

每一阶段均沿用 `apks.flatMap { it.files }` 的现有稳定顺序；同一 APK 内沿用 Manifest 声明顺序。单个 split Manifest 读取失败仍沿用现有异常行为。

### 3.3 stop 与返回语义

第 3 级直接复用现有 `AdbCmdHelper.stopApp()`，不增加 Android 版本分支，也不引入新的进程控制命令。

`IDeployTargetManager.startApp()` / `restartApp()` 的 Boolean 契约保持不变：表示本次生命周期命令是否无异常完成，不承诺 App 已进入前台或 ready。因此 stop fallback 成功执行后仍返回 `true`：

- 普通 deploy/install 不因无 Activity 被误判为部署失败；
- MCP `restart` 未设置 `waitAppReadyAfterSuccess` 时仍只确认命令完成；
- 显式设置 `waitAppReadyAfterSuccess=true` 时，因为 App 没有启动，后置 ready 检查会按现有机制返回失败；
- Recover 路径仍由现有 `waitingForDeployable()` 判断 App 是否真正 online，失败后继续原 recover/reinstall 策略。

不为区分 STARTED/STOPPED 引入新的结果实体或修改 `IDeployTargetManager` 公共返回类型。

### 3.4 日志

保持用户可见日志能够解释采用了哪一级降级：

- 找到 launch Activity：保留现有 debug 日志；
- 使用 HOME Activity：打印 info，说明未找到 launch Activity 及选中的 HOME Activity；
- stop fallback：打印 warn，说明 launch 和 HOME Activity 均不存在，已 stop App。

不把普通 Manifest 候选遍历过程提升为用户日志；候选查找过程继续只用于 debug/排查。

## 4. 实现设计

### 4.1 Activity 解析

继续复用现有 `ManifestActivityInfo`、`NodeActivity` 和 `DefaultApkActivityLocator`，不新增 locator 接口或候选数据类。

`DefaultApkActivityLocator` 增加一个最小查询方法：

- HOME Activity：筛选 enabled、exported、组件名有效、`MAIN` + `HOME`，返回第一个节点的 qualified name。

现有 launcher 查找方法不改筛选、排序和返回值，确保第 1 级行为保持不变。

### 4.2 exported 语义

`NodeActivity` 现有 `getExported()` 已能满足 HOME 判断：显式声明 `exported=false` 的节点被排除，未声明时保持 `true`。

不引入“未声明 exported 时按有无 intent-filter 推导”的额外规则。HOME 候选必须带 `MAIN` + `HOME` intent filter，因此推导规则对 HOME 选择没有任何可观察差异，只会扩大 `NodeActivity` 这个公共模型类的行为变更面。

### 4.3 APK 与设备适配层

`ApkReader` 增加 HOME Activity 的读取入口，继续从 APK 内的二进制 Manifest 获取数据。

`IDeviceAdb` 增加带安全默认实现的查询方法：

```text
getHomeActivity(apkFile) -> null
```

默认实现避免迫使无关测试 fake 和其它适配实现批量增加样板代码。`IdeaDeviceAdb` 覆盖该方法并委托 `ApkReader`。现有 `getDefaultLaunchActivity()` 保持不变。

### 4.4 启动编排

`AdbCmdHelper.startDefaultApp()` 保持当前入口和参数，在内部按两个阶段遍历 APK：

```text
getDefaultLaunchActivity
  ?: getHomeActivity
  ?: stopApp
```

找到 HOME 后继续复用 `startApp()`，从而自动保留：

- restart 的 `-S`；
- Debug 的 `-D`；
- `-D -S` 参数顺序；
- 当前显式 component 启动格式。

INSTALL 和强制 Direct Overlay 当前会在部署前先 stop App。若部署后的启动再次走到第 3 级，重复 `force-stop` 是幂等操作，首版不为避免一次重复命令增加调用方状态或特殊分支。

## 5. 变更清单

### 5.1 `main` 生产代码

- `main/src/main/java/com/sickworm/intellij/jugg/apk/DefaultApkActivityLocator.kt`
  - 增加 HOME Activity 的确定性选择逻辑；保持 launcher 逻辑不变。
- `main/src/main/java/com/sickworm/intellij/jugg/apk/ApkReader.kt`
  - 暴露 HOME Activity 查询入口。
- `main/src/main/java/com/sickworm/intellij/jugg/deploy/IDeviceAdb.kt`
  - 增加 HOME 查询方法及默认空实现。
- `main/src/main/java/com/sickworm/intellij/jugg/deploy/AdbCmdHelper.kt`
  - 实现跨 APK 的三级选择顺序、fallback 日志和最终 stop。

### 5.2 `idea` 生产代码

- `idea/src/main/java/com/sickworm/intellij/jugg/deploy/IdeaDeviceAdb.kt`
  - 使用 `ApkReader` 实现 HOME 查询。

不修改 `JuggDeployerHelper`、`DeployTargetManager`、MCP action 或 CLI 实现；它们继续复用现有启动入口和返回契约。

### 5.3 测试

- 新增 `main/src/test/java/com/sickworm/intellij/jugg/apk/DefaultApkActivityLocatorTest.kt`
  - 作为 Activity 选择规则的 L1 behavior owner。
- 更新 `main/src/test/java/com/sickworm/intellij/jugg/deploy/AdbCmdHelperTest.kt`
  - 覆盖跨 APK 优先级、启动命令和 stop fallback。
- 复用 `idea/src/test/java/com/sickworm/intellij/jugg/manager/TopLevelFlowTest.kt#testInstallAndLaunch`
  - 验证现有 launch Activity 的 install + launch 主链不回归。

### 5.4 知识库与 Wiki

- `docs/ai_knowledge/03_deploy_core.md`
  - 补充 install/restart/start 的 Activity 选择和 stop fallback。
- `docs/ai_knowledge/03_deploy_complete.md`
  - 补充 Run 完成后的启动降级结果。
- `docs/ai_knowledge/08_mcp_tools_list.md`
  - 明确 `restart` 无 Activity 时执行 stop；`waitAppReadyAfterSuccess=true` 会因未 ready 返回失败。
- `docs/ai_knowledge/08_cli_tools_list.md`
  - 同步 CLI `jugg restart` 的 fallback 语义。
- `docs/wiki/zh/capabilities/deploy/restart.md` 与英文镜像
  - 说明 App restart/start 的三级 Activity 选择规则。
- `docs/wiki/zh/guide/restart-app.md` 与英文镜像
  - 提醒无 launch/HOME Activity 时 Restart 会 stop App，且不清理数据。
- `docs/wiki/zh/reference/mcp-tools.md` 与英文镜像
  - 同步 `restart` 工具的无 Activity 行为。

不新增 Wiki 页面，不修改侧边栏或索引结构。

## 6. 验证计划

### 6.1 失败证据与测试价值

当前失败证据由现有代码分支直接给出：所有 APK 的 `getDefaultLaunchActivity()` 返回 null 后，方法只打印 warn 并返回，没有 HOME 查询，也没有执行 `am force-stop`。

Activity 选择优先级和最终 ADB 命令是稳定、独立、用户可观察的行为，能够通过 fake `IDeviceAdb` 形成确定性断言，通过测试价值门禁。测试不需要修改生产代码以增加 mock seam。

### 6.2 TDD 顺序

1. 先在 `DefaultApkActivityLocatorTest` 写失败测试，证明 HOME 选择能力当前不存在；
2. 先在 `AdbCmdHelperTest` 写失败测试，证明当前不会按跨 APK 优先级 fallback，也不会在无候选时 stop；
3. 确认定向测试失败后再修改生产代码；
4. 实现后重新执行同一组测试，并运行现有 install + launch L3 回归。

### 6.3 自动化场景

`DefaultApkActivityLocatorTest`：

- launch 选择的现有 DEFAULT 优先规则保持不变；
- enabled、exported 的 `MAIN` + `HOME` 被选中；
- HOME 候选跳过 disabled、exported=false 和空组件名；
- HOME 候选为 activity-alias 时返回 alias qualified name；
- 无 HOME 候选返回 null。

`AdbCmdHelperTest`：

- 后一个 APK 的 launch Activity 优先于前一个 APK 的 HOME；
- 所有 APK 无 launch 时使用 HOME；
- HOME 在 restart + Debug 场景继续生成正确的 `am start -D -S -n ...`；
- 普通 launcher 场景继续生成现有命令；
- 所有 APK 无 launch/HOME 时唯一命令是 `am force-stop <package>`，不启动任何其它 Activity。

### 6.4 定向验证命令

```bash
./gradlew :main:test \
  --tests com.sickworm.intellij.jugg.apk.DefaultApkActivityLocatorTest \
  --tests com.sickworm.intellij.jugg.deploy.AdbCmdHelperTest

./gradlew :idea:test \
  --tests com.sickworm.intellij.jugg.manager.TopLevelFlowTest.testInstallAndLaunch

./gradlew :idea:compileKotlin
```

完成后检查：

- 本次 diff 中新增/修改日志符合 Jugg 日志换行与等级规范；
- 中英文 Wiki 内容严格镜像；
- `git diff --check` 通过；
- 未修改 launcher 的筛选、DEFAULT 优先级和 `realActivityQname` 返回行为。

## 7. 风险与收口

### 7.1 force-stop 副作用

第 3 级会让无 Activity App 进入 stopped 状态，后台 Service、Receiver 或 Job 不保证自行恢复。这是用户确认的最终降级语义，日志和文档必须明确，不伪装为进程已经 restart。

### 7.2 split APK

候选按 APK 原顺序读取。单个 split Manifest 读取失败仍沿用现有异常行为，不吞掉解析错误，也不跳过失败 APK 后伪造成功。

### 7.3 ready 与 Debug

stop fallback 后 App 不会 ready。显式 ready 等待、Recover 在线检测或 Debug attach 可能失败，继续由各自现有错误路径负责；本功能不伪造 ready，也不新增自动启动 Service/Receiver 的行为。

## 8. 不在范围内

- 新增用户可配置的启动 Activity；
- 回退到“第一个可启动 Activity”：Manifest 顺序无法表达业务首页，普通未导出 Activity 会稳定启动失败；
- 使用 Service、BroadcastReceiver、ContentProvider 或 instrumentation 拉起无 Activity App；
- 使用 Android 13+ `am stop-app` 或按 Android 版本切换 stop 命令；
- 修改 Activity-only relaunch、Apply Changes Full Swap 或 Direct Overlay transport；
- 调整 MCP/CLI schema、返回数据结构或错误码；
- 修复现有 launcher activity-alias 使用 `realActivityQname` 的行为；
- 为避免 INSTALL 前后重复 stop 增加额外状态。

## 9. 评审范围与确认项

本方案没有未决的产品问题，但实现前需要确认以下解释与范围：

- 只保留 launch Activity 和 HOME Activity 两级候选，不引入“第一个可启动 Activity”；
- 第 3 级复用现有 `am force-stop`，stop 成功视为生命周期命令完成，但不表示 App 已 ready；
- launch Activity 的既有选择逻辑严格保持不变；
- 首版不增加配置和新的结果类型，只修改上述文件与文档。

评审通过后，按本方案先补失败测试，再实施生产代码和文档变更；若实现中发现必须修改未列出的公共契约或部署编排，先暂停并重新提交范围评审。

## 10. 实现结果

### 10.1 首版三段以外的一次扩展与收敛

首版实现曾按评审意见扩展出“第一个可启动 Activity”第三级候选，并为此引入 `getFirstActivity()` 读取入口，以及 `NodeActivity` 未声明 `exported` 时按 intent-filter 推导的缺省语义。

最终收敛决策改为只保留 launch Activity 和 HOME Activity 两级候选，无候选时直接 stop App。收敛时删除了 `computeFirstActivity()` / `getFirstActivity()` 生产 API、`IdeaDeviceAdb` 与 `ApkReader` 的对应实现、`DefaultApkActivityLocatorTest` 与 `AdbCmdHelperTest` 中 first-only 用例，以及 `AdbCmdHelper` 的第三级分支与 info 日志。`NodeActivity` 和 `ManifestActivityInfoTest` 一并回退：HOME 候选必然带 `MAIN` + `HOME` intent filter，推导规则对 HOME 选择没有可观察差异，保留它只会扩大公共模型类的行为变更面。

### 10.2 最终实现

生产代码按本方案 §4 的文件边界落地，未新增接口、候选实体、配置项或结果类型，未修改 `JuggDeployerHelper`、`DeployTargetManager`、MCP action 与 CLI 实现。日志等级与换行沿用 `JuggLogger` 规范，无 `error` 日志。

失败证据：`DefaultApkActivityLocator.computeHomeActivity()` 与 `IDeviceAdb.getHomeActivity()` 先以返回 null 的空实现落地，`AdbCmdHelper.startDefaultApp()` 保持原逻辑，`DefaultApkActivityLocatorTest` 与 `AdbCmdHelperTest` 共 18 个用例中 9 个失败。收敛为三段优先级时，先写出“无 launch/HOME 时只 force-stop”的用例，确认它对仍会启动 first Activity 的实现失败（定向用例 1 个失败），再移除生产侧 first fallback。

实现后 `DefaultApkActivityLocatorTest` 5 个、`AdbCmdHelperTest` 7 个用例全部通过：

```bash
./gradlew :main:test \
  --tests com.sickworm.intellij.jugg.apk.DefaultApkActivityLocatorTest \
  --tests com.sickworm.intellij.jugg.deploy.AdbCmdHelperTest
```

L3 回归 `TopLevelFlowTest.testInstallAndLaunch`（真机 install + launch 主链）通过，`./gradlew :idea:compileKotlin` 通过。

文档同步：`03_deploy_core.md` §4.4 与 §7、`03_deploy_complete.md` §4.2、`08_mcp_tools_list.md` `restart`、`08_cli_tools_list.md` `restart`，以及 `docs/wiki/{zh/,}capabilities/deploy/restart.md`、`docs/wiki/{zh/,}guide/restart-app.md`、`docs/wiki/{zh/,}reference/mcp-tools.md`（中文为基准、英文镜像）。skill 文档中未发现与新行为冲突的旧描述，未新增 Wiki 页面与侧边栏条目。
