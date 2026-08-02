# 读取选中设备时禁止自动启动 AVD 修复方案

## 1. 背景

当前 Jugg 在 Android Studio Panda 4 和 Quail 1 中可能出现以下行为：

1. IDE 当前选中一台未启动的虚拟设备。
2. Jugg 执行设备存在性检查。
3. Run 输出提示 `No device found. Stop installing.`。
4. 提示出现后，选中的虚拟设备仍被异步启动。

该现象不是 Quail 独有。Panda 4 与 Quail 1 的 `DeviceAndSnapshotComboBoxTarget.launchDevices()` 都会主动启动未连接的选中虚拟设备。

## 2. 现状与根因

### 2.1 当前调用链

```text
JuggRunningTask.doRun()
  -> deployTargetManager.hasDevice
  -> IDeployTargetManager.hasDevice
  -> DeployTargetManager.getSelectedDevices()
  -> AsDeployerCompat.getSelectedDevices(project)
  -> MeerkatAsDeployerCompat / QuailAsDeployerCompat
  -> DeployTarget.launchDevices(project)
  -> 对未连接的 selected target 调用 boot()
  -> DeviceFutures.ifReady 当前仍未完成，返回 null
  -> Jugg 输出 No device found
  -> AVD 在后台继续启动
```

`getSelectedDevices()` 还被以下只读流程调用：

- `JuggCompileHelper.incrementalCompile()`：读取设备名称判断是否首次运行。
- `DeployStateManager.updateDeployState()`：刷新部署状态。
- `JuggRunningTask.run()`：记录本轮运行设备。
- `JuggDebugSessionManager`：检查 Debug 是否为单设备。
- `MoreOptionsManager`：对当前设备移除 agent。
- MCP `get-status`、`device-list`、compile job 和设备解析流程。

因此自动启动不只可能发生在最终部署阶段，也可能发生在编译前判断、状态刷新、MCP 查询或项目初始化后的后台流程。

### 2.2 旧保护为什么失效

`DeployTargetManager.getSelectedDevices()` 在 2025 年增加了以下保护：

```text
if getConnectedDevices() is empty
  return emptyList()
```

该保护只能覆盖“ADB 中完全没有设备”的场景，无法覆盖：

- 一台无关真机在线，但 IDE 选中的是离线 AVD。
- ADB 列表中残留 `OFFLINE` / `DISCONNECTED` 设备对象。
- 多设备环境中至少一台设备在线，另一台被选中的 AVD 未启动。

本次现场日志正是第一种情况：真机 `RF8M82H39HD` 在线，选中的 `Pixel 6 / emulator-5554` 离线。前置判断被真机放行，随后 `launchDevices()` 启动 Pixel 6。

### 2.3 本质问题

`getSelectedDevices()` 名义上是查询方法，但当前兼容实现使用了有副作用的 launch API。调用方普遍把它当作可重复执行的只读查询，接口语义与实际行为不一致。

## 3. 修复目标

1. 读取选中设备时永远不启动 AVD。
2. 只有全部选中设备都已经运行时，才返回完整的选中设备列表。
3. 保持单选、多选及返回顺序不变。
4. 不改变用户主动从 Android Studio 原生 Run 启动 AVD 的行为。
5. 不新增测试专用 provider、supplier、factory 或可变闭包。
6. 不引入新的业务接口或设备实体。

## 4. 非目标

- Jugg 不负责等待离线 AVD 启动完成。
- Jugg 不自动选择第一台在线设备替代 IDE 选择，MCP 已有明确 fallback 的流程除外。
- 本次不调整 Android Studio 原生 Run/Debug 的设备启动策略。
- 本次不修改 install、Apply Changes、Direct Overlay 或 ADB transport 实现。

## 5. 版本 API 评估

| 兼容范围 | 当前入口 | 当前副作用 | 可用的只读 API | 建议 |
|---|---|---|---|---|
| Chipmunk | `DeployTarget.getDevices(AndroidFacet)` | 可能启动 AVD | 无公开等价 API | 保留现状，单独评估 |
| Giraffe～Iguana | `DeployTarget.getDevices(Project)` | 可能启动 AVD | 无公开等价 API | 保留现状，单独评估 |
| Meerkat～Panda | `DeployTarget.launchDevices(Project)` | 明确调用 `boot()` | `getAndroidDevices(Project)` | 本次修复 |
| Quail | `DeployTarget.launchDevices(Project)` | 明确调用 `boot()` | `getAndroidDevices(Project)` | 本次修复 |

`MeerkatAsDeployerCompat` 的继承范围包括：

```text
MeerkatAsDeployerCompat
  -> NarwhalAsDeployerCompat
  -> NarwhalAsDeployerFeatureCompat
     -> OtterAsDeployerFeatureCompat
        -> PandaAsDeployerFeatureCompat
```

因此修改 `MeerkatAsDeployerCompat` 可以覆盖 Meerkat、Narwhal、Narwhal Feature Drop、Otter 和 Panda；Quail 为独立实现，需要单独修改。

## 6. 推荐实现

### 6.1 统一接口语义

更新以下接口注释，明确 `getSelectedDevices()` 是无副作用查询：

- `deploy_compat/interface/.../IAsDeployerCompat.kt`
- `main/.../IDeployTargetManager.kt`

期望语义：

```text
返回 IDE 当前选中且已经运行的 ddmlib 设备；不得启动虚拟设备。
```

不新增 `peekSelectedDevices()` 或 `launchSelectedDevices()`。当前 Jugg 没有主动启动 AVD 的业务需求，新增接口只会扩大认知成本。

### 6.2 Meerkat～Panda

修改 `MeerkatAsDeployerCompat.getSelectedDevices()`：

```kotlin
override fun getSelectedDevices(project: Project): List<IDevice>? {
    val deployTargetContext = DeployTargetContext()
    val deployTarget = deployTargetContext.currentDeployTargetProvider.getDeployTarget(project)
    val selectedDevices = deployTarget.getAndroidDevices(project)
    val readyDevices = selectedDevices.mapNotNull { it.ddmlibDevice }
    return readyDevices.takeIf {
        it.isNotEmpty() && it.size == selectedDevices.size
    }
}
```

行为依据：

- `getAndroidDevices(project)` 只按 `selectedTargets` 顺序映射 AndroidDevice，不调用 `boot()`。
- `DeviceProvisionerAndroidDevice.ddmlibDevice` 仅在设备已经运行时返回 `IDevice`，未运行时返回 null。
- `mapNotNull` 不会改变在线设备的相对顺序。
- 只有 `readyDevices.size == selectedDevices.size` 时才返回，避免 Multiple Devices 中静默跳过离线设备并产生部分部署。

### 6.3 Quail

`QuailAsDeployerCompat.getSelectedDevices()` 使用与 Meerkat 相同的只读路径。Quail 继续保持独立兼容实现，不改变其 deployer 包迁移隔离边界。

### 6.4 DeployTargetManager

保留当前“无任何 ADB 设备时提前返回”的保护，以继续保护暂未适配的 Chipmunk～Iguana 路径。

同时修正 `IDeployTargetManager.getDeviceNameList()` 的重复查询：

```kotlin
val devices = getSelectedDevices()
if (devices.isEmpty()) {
    return null
}
return devices.joinToString(", ") { it.name }
```

当前实现连续调用两次 `getSelectedDevices()`，可能产生重复启动、重复查询或两次结果不一致。该调整不改变返回值语义。

### 6.5 Run 主链路快照

建议在 `JuggRunningTask.doRun()` 中只读取一次设备列表：

```text
val devices = deployTargetManager.getSelectedDevices()
if devices is empty
  输出 No device found 并结束
else
  按 devices 顺序部署
```

替换当前 `hasDevice` 检查后再次调用 `getSelectedDevices()` 的方式，避免两次查询之间用户切换选择或设备状态变化。

这是同一问题的收口，不改变部署策略。

## 7. 不推荐方案

### 7.1 等待 `launchDevices()` 完成

不推荐。该方案仍会自动启动 AVD，只是避免 `No device found` 提示，与用户期望相反，并会延长 Jugg Run 的阻塞时间。

### 7.2 仅增强 `getConnectedDevices()` 前置判断

不推荐。任何“存在一台在线设备”的判断都无法证明“被选中的设备在线”，混合设备场景仍会触发启动。

### 7.3 在所有调用点分别禁止启动

不推荐。副作用来自兼容层查询实现，应在数据源处修复。逐个调用点增加条件容易遗漏 MCP、状态刷新和后续新增入口。

### 7.4 为旧版本立即引入反射

Chipmunk～Iguana 的 selector action 存在 package-private 的 `getSelectedDevices()`，理论上可通过反射读取并保持顺序，但需要同时反射不同包名和 Device 内部方法。

当前不推荐纳入第一阶段，原因：

- package-private API 跨版本不稳定。
- Iguana 同时包含 legacy selector 与新 selector，选择错误会读取到不同状态源。
- 反射异常可能让旧版本完全无法获取设备。
- 当前问题已在 Panda、Quail 稳定复现，优先使用公开 API 完成低风险修复。

旧版本继续由现有“ADB 完全为空时提前返回”保护。若后续确认 Giraffe～Iguana 也需要完全禁止启动，再单独设计版本内反射适配。

## 8. 影响面评估

### 8.1 正向影响

| 场景 | 修改后行为 |
|---|---|
| 真机在线，IDE 选中离线 AVD | 返回空设备，提示 No device found，不启动 AVD |
| IDE 选中在线真机 | 继续部署到该真机 |
| IDE 选中在线 AVD | 继续部署到该 AVD |
| Multiple Devices 全部在线 | 保持 IDE 选择顺序逐台部署 |
| Multiple Devices 部分离线 | 整轮提示无设备，不部署任何设备，也不启动离线 AVD |
| 项目打开或部署状态刷新 | 不再因为设备状态查询启动 AVD |
| MCP get-status / device-list | 查询不再产生设备启动副作用 |
| Debug 成功后 attach | 仍要求恰好一台当前选中且在线的设备 |

### 8.2 行为变化

此前用户选中离线 AVD 后点击 Jugg Run，Android Studio API 会隐式启动该 AVD。修复后 Jugg 会直接提示没有可用设备，用户需要先手动启动 AVD，再次执行 Jugg Run。

这是有意的行为变化，与 `No device found. Stop installing.` 的提示语义一致。

### 8.3 多设备影响

- `getAndroidDevices(project)` 保持 `selectedTargets` 列表顺序。
- `mapNotNull` 不重排在线设备。
- 只有所有选中设备都能解析为 `IDevice` 时才返回完整列表。
- `JuggRunningTask` 继续按完整列表顺序串行部署。
- 如果多选列表中间存在离线 AVD，例如 `[A 在线, B 离线, C 在线]`，整轮返回无设备，不会只部署 `[A, C]`。

### 8.4 状态与 fallback

- 无在线选中设备时仍返回部署失败，不改变 Gradle compile 是否成功。
- Gradle build 后无设备仍会初始化增量编译上下文。
- 不改变 deploy failure fallback 规则。
- `DeployStateManager` 会将空列表解析为 no device，不再启动设备后反复刷新状态。

### 8.5 MCP 影响

`DeviceSelectionResolver` 在 selected devices 为空时仍可按现有逻辑 fallback 到第一台在线 connected device。该行为是 MCP 自己的显式策略，不属于自动启动 AVD。

`device-list` 和 `get-status` 只会减少副作用，返回结构不变。

### 8.6 兼容风险

| 风险 | 等级 | 处理 |
|---|---|---|
| `getAndroidDevices()` 在某个补丁版本返回顺序变化 | 低 | Panda/Quail 本地 JAR 字节码已确认按 selectedTargets 顺序映射 |
| 已运行设备的 `ddmlibDevice` 短暂为 null | 中 | 与当前 `ifReady == null` 等价，返回 no device；不等待、不启动 |
| 用户依赖 Jugg 自动启动 AVD | 中 | 明确改为手动启动，符合本次目标 |
| 旧版 AS 仍可能自动启动 | 中 | 第一阶段明确限定 Meerkat 及以上，旧版另行评估 |
| 两次设备查询结果变化 | 低 | `getDeviceNameList` 和 Run 主链路改为单次快照 |

## 9. 实施顺序

1. 更新 `IAsDeployerCompat` 与 `IDeployTargetManager` 的无副作用语义注释。
2. 修改 `MeerkatAsDeployerCompat.getSelectedDevices()`。
3. 修改 `QuailAsDeployerCompat.getSelectedDevices()`。
4. 修正 `getDeviceNameList()` 的重复查询。
5. `JuggRunningTask.doRun()` 使用单次设备快照。
6. 更新 `docs/ai_knowledge/04_engineering_compat.md` 和 `03_deploy_complete.md`。
7. 执行定向编译与兼容验证。

## 10. 验证方案

### 10.1 代码验证

```bash
./gradlew :deploy_compat:v_meerkat:compileKotlin \
  :deploy_compat:v_panda:compileKotlin \
  :deploy_compat:v_quail:compileKotlin \
  :idea:compileKotlin
```

### 10.2 自动化回归落点

若实施时保留自动化测试，按项目分层追加：

- L2：`idea/src/test/java/com/sickworm/intellij/jugg/ide/logic/JuggRunningTaskTest.kt`
  - 空设备列表只查询一次并返回 no device。
  - 多设备列表按输入顺序进入部署。
- L3：`idea/src/test/java/com/sickworm/intellij/jugg/manager/TopLevelFlowTest.kt#testInstallAndLaunch`
  - 回归真实设备安装主链路。

禁止为测试向生产代码增加 provider、supplier、factory 或默认 lambda 参数。

### 10.3 手工兼容矩阵

| AS 版本 | 在线设备 | IDE 选择 | 预期 |
|---|---|---|---|
| Panda 4 | 真机 A 在线 | 离线 AVD B | 提示无设备，B 不启动 |
| Quail 1 | 真机 A 在线 | 离线 AVD B | 提示无设备，B 不启动 |
| Panda 4 | 真机 A、C 在线 | Multiple `[C, A]` | 按 `[C, A]` 部署 |
| Quail 1 | 真机 A、C 在线 | Multiple `[C, A]` | 按 `[C, A]` 部署 |
| Panda 4 | 无在线设备 | 离线 AVD B | 提示无设备，B 不启动 |
| Quail 1 | 无在线设备 | 离线 AVD B | 提示无设备，B 不启动 |
| Quail 1 | A 在线、B 离线、C 在线 | Multiple `[A, B, C]` | 不部署任何设备，B 不启动 |

额外验证：

- 打开项目并等待部署状态刷新，AVD 不启动。
- 调用 MCP `get-status`、`device-list`，AVD 不启动。
- 点击 More Options 中依赖选中设备的操作，离线 AVD 不启动。
- Debug 单设备在线时仍可 attach；离线 AVD 不启动并明确失败。

## 11. 回滚策略

该修复只改变设备列表获取方式。若某个新版 AS 补丁版本无法读取在线选中设备，可按兼容模块单独回滚：

- 回滚 `MeerkatAsDeployerCompat` 会同时影响 Meerkat～Panda。
- Quail 可独立回滚，不影响旧版本。

不需要迁移配置、数据库或部署历史。

## 12. 结论

推荐将 `getSelectedDevices()` 改为纯查询，并在 Meerkat～Panda、Quail 使用公开的 `getAndroidDevices(project)` + `ddmlibDevice` 路径。该方案改动小、无新增抽象、保留多设备顺序，并从源头消除 Run、状态刷新、MCP 等多个入口的 AVD 自动启动副作用。

Chipmunk～Iguana 缺少公开的等价 API，不应在本次低风险修复中引入跨版本反射；保留现有保护并作为独立兼容任务评估。
