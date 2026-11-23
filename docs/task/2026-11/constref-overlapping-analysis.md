# 修复 ConstRef 重叠分析漏跟编

**Purpose** — Preserve pending constant changes across overlapping analyses.
**Decision** — Keep existing unacknowledged changes when later analysis finds an empty delta.
**Impact** — Future queries can still discover callers.

## 场景

IDE 一次保存多个文件，或 CI 同时提交多个源码文件做增量编译时，后台分析与编译触发的按需分析可能选中同一个常量定义文件。后台先将定义从旧值写成新值并登记跟编差异。按需分析随后读到同一个新值，得出空差异，原先会清掉该文件的待跟编记录。引用方因此继续使用基线中内联的旧值。

## 实施

- `ConstRefChangeTracker` 收到空差异时保留已登记状态；有实际变化时继续更新变更与删除记录。成功部署后的确认仍负责消费待跟编差异。
- 保留现有解析、索引、缓存和调度流程。正常增量继续只补编受影响的引用方，重叠时的重复分析仍可完成。故障时沿用 ConstRef 现有局部降级以及用户主动选择的完整 Gradle 构建入口。
- 扩展 `ConstRefEngineTest` 的结果断言，覆盖缓存复用、常量定义未变但 checksum 改变后重新解析，以及成功确认后的清理。仅验证私有常量或空白改动的用例先确认初始差异，明确模拟已构建基线。
- 永久用例直接比较跟编名单。另用临时程序控制原始竞态的顺序，分别重放 IDE 指纹缓存配置和 CI 原始基线索引。

## 验证

| 验证项 | 结果 |
| --- | --- |
| 失败证据 | 修复前，重新解析定义未变的文件之后，`ConstRefEngineTest` 查询不到原先待跟编的引用方 |
| 定向回归 | ConstRef 测试 106 项通过，4 项性能基准依现有开关跳过；`:idea:compileKotlin` 通过。测试编译以临时 init 脚本排除缺少接口实现的既有 `LibraryTestApkBuildHistoryTest` |
| 重叠与串行对照 | 使用本地 demo 和原 CI 基线索引分别重放，两种时序均保留两处常量差异 |
| Android_Demo 原 CI 场景 | 使用原基线副本和对应的三个仓库提交，本地调整基线副本中外部源码根的绝对路径后成功运行 `buildIncrementalApk`。日志确认两批分析在索引复用检查时都将 `SongListActivity.kt` 列为待解析，随后两处差异均进入跟编查询。`SongListPage.kt` 进入跟编，补丁 DEX 的页头条件使用 `99`，事件发送方和监听方使用 `API_EVENT_PLAY_LIST_CHANGED_V2` |
| IDE 编译链路 | 临时定向测试通过 `MockJugg` 调用 `JuggManager`，两处变更触发三个引用方进入 staging；`JavaInvoker.dex` 使用 `99`，`NestedObjectConfigInvoker.dex` 使用 `new`。验证到 IDE 编译产物，未执行 Android Studio 图形界面 Run 或设备部署 |
