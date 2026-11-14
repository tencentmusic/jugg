# RecyclerView item 内替换 SDK ConstraintLayout 的复现补充

## 用户补充与结论边界

用户补充：带 `<layout>` 的 XML 通过 DataBinding 用于 RecyclerView 的 ViewHolder / itemView；绑定时仅配置容器内部的 ImageView，没有业务代码对 SDK 容器进行访问或强转。

在 `android_demo_project` 按这一描述补齐实际 RecyclerView 后，结论为：

| 条件 | Jugg 编译与部署 | 实际运行 |
|---|---|---|
| 仅替换一份 item XML 的 SDK 标签为 AndroidX | 成功，仅一个 XML 输入 | 正常创建 ViewHolder，并执行 ImageView 配置 |
| 同时改另一资源 root 的 DataBinding XML，目标 item 先处理 | 成功，仅两个 XML 输入 | 在 `onCreateViewHolder → Binding.inflate → BindingImpl` 创建时强转崩溃 |
| 保持第二行源码不变，完整 Gradle 构建 | 成功 | 页面与 ImageView 配置恢复正常，crash buffer 为空 |

因此用户新增的场景与已有缺陷相容，也能在同一 ViewHolder 调用链复现；但它本身尚不足以独立触发故障。我们仍需额外的多资源目录条件，不能把“使用 RecyclerView”判断为根因，也不能据此认定原用户一定命中了已找到的缺陷。

## 当前 demo 结构

本记录承接 [前轮复现记录](sdk_constraintlayout_xml_cast_reproduction.md)。前轮记录中的 Activity 直接 `setContentView`、SDK 控件作为根节点，是历史验证结构；当前 fixture 已调整为：

- `MultiRootDataBindingActivity`：代码创建 RecyclerView 和 LinearLayoutManager；Adapter 通过 `ActivityDataBindingMultiRootBinding.inflate(inflater, parent, false)` 创建 item；ViewHolder 保存生成的 Binding。
- `activity_data_binding_multi_root.xml`：`<layout>` 内是 LinearLayout，里面嵌套 SDK ConstraintLayout，SDK 容器内再放一个 `preview_image` ImageView。SDK 容器不是 item 的根节点。
- `onBindViewHolder`：只调用 `binding.previewImage.setImageResource(android.R.drawable.ic_menu_gallery)`，并打印完成标记；不访问或强转 `sdkContainer`。
- SDK 仍来自前轮的独立 JAR，仅包含控件类，不含资源或 Binding；本轮不改动 JAR。
- 对照布局仍为 `app/src/debug/res/layout/data_binding_multi_root_peer.xml`，正常文字为 `baseline`。

Activity 和嵌套 ImageView 先经完整 Gradle 构建形成基线；正式故障实验只更换 XML 控件标签，以及双目录实验中的 peer 文字，不修改 Java、SDK JAR 或生成代码。

## 重放步骤

使用 JDK 17、SDK 34 构建和 API 35 / arm64 的 Pixel_6 模拟器。IDE 选择模拟器，再通过 `jugg devices` 确认仅 `emulator-5554` 被选中。所有 CLI 命令指定 demo 的 `--project-dir`，所有 adb 命令指定设备，避免使用同时连接的真机。

1. 保持提交中的 SDK 标签与 peer 的 `baseline`，运行 `jugg gradle-build`。显式启动 `com.example.myapplication/com.sickworm.jugg.demo.testcase.databinding.MultiRootDataBindingActivity`，应打印 `item bound: ImageView configured`。
2. 只将 item XML 内 SDK 控件的开始、结束标签替换为 `androidx.constraintlayout.widget.ConstraintLayout`，运行 `jugg deploy`，再次启动页面。该轮应正常，且增量日志包含目标 Binding 和 BindingImpl。
3. 恢复 SDK 标签，重新完整构建基线。随后同轮将目标标签改为 AndroidX、peer 文字改为 `changed`，运行 `jugg deploy`。
4. 确认日志有两个 `res root` 批次，目标先处理；生成的 Java / DEX 只有 peer 的 Binding，没有目标 Binding。启动页面后从 `adb -s emulator-5554 logcat -b crash -d` 保存下述异常。
5. 保持两份 XML、Activity 与 SDK JAR 不变，运行 `jugg gradle-build`，启动页面确认图片配置成功；对比四份输入 SHA-256，确认没有源码修正掩盖问题。
6. 恢复 SDK 标签与 peer 的 `baseline`，完整构建并启动，留下可运行基线。

```text
java.lang.ClassCastException: androidx.constraintlayout.widget.ConstraintLayout
  cannot be cast to com.sickworm.jugg.demo.sdk.SdkConstraintLayout
  at com.example.myapplication.databinding.ActivityDataBindingMultiRootBindingImpl.<init>(ActivityDataBindingMultiRootBindingImpl.java:32)
  at com.example.myapplication.databinding.ActivityDataBindingMultiRootBindingImpl.<init>(ActivityDataBindingMultiRootBindingImpl.java:29)
  at com.example.myapplication.DataBinderMapperImpl_Inc_1.getDataBinder(DataBinderMapperImpl_Inc_1.java:104)
  at androidx.databinding.DataBindingUtil.inflate(DataBindingUtil.java:130)
  at androidx.databinding.ViewDataBinding.inflateInternal(ViewDataBinding.java:1409)
  at com.example.myapplication.databinding.ActivityDataBindingMultiRootBinding.inflate(ActivityDataBindingMultiRootBinding.java:48)
  at com.sickworm.jugg.demo.testcase.databinding.MultiRootDataBindingActivity$ItemAdapter.onCreateViewHolder(MultiRootDataBindingActivity.java:26)
  at androidx.recyclerview.widget.RecyclerView$Adapter.createViewHolder(RecyclerView.java:7295)
```

强转发生在 Binding 创建时，早于 `onBindViewHolder`。即使业务只使用 ImageView，旧 Binding 的构造代码仍会把 XML 中的容器转成 SDK 类型。单 XML 正常轮和完整 Gradle 恢复轮生成的同一处强转均为 AndroidX，支持前轮的“资源已更新、目标 Binding 遗漏”解释；这不是图片加载行为本身的异常。

## 实测证据

日期：2026-10-20，Asia/Shanghai。本地证据目录：`build/reproductions/recyclerview-sdk-constraint-cast-evidence/`，构建产物与原始日志不提交。

| 验证 | Job ID | 结果 |
|---|---|---|
| RecyclerView / SDK 基线 | `f6923118-5f4b-4ab7-b2ba-0571a20b08ac` | 23:19:04 图片配置成功 |
| 仅替换 item XML | `a281ab86-6563-4a39-b76b-f8e1f5887c77` | 增量成功；23:19:53 图片配置成功，crash buffer 为空 |
| 重建 SDK 基线 | `01d51601-c7ed-4c2e-8da0-5fcb8cfff4d9` | 页面正常 |
| 同轮修改两个 root 的 XML | `054520df-472b-48be-a4d4-c3908a16c45c` | 增量成功；23:21:37 在 RecyclerView 创建 ViewHolder 时崩溃 |
| 相同源码完整构建 | `d41bbd57-07bb-4ef4-8229-58824f3aa0db` | 页面正常、图片配置成功；四份文件 SHA-256 相同，crash buffer 为空 |

关键文件：`single-xml-deploy.json`、`single-xml-runtime.log`、`single-xml-crash.log`、`dual-xml-deploy.json`、`dual-xml-compile.log`、`dual-xml-crash.log`、`dual-stripped-target.xml`、三份 BindingImpl 对照，以及 `before-full-sha256.json` / `after-full-sha256.json`。

首次基线构建 `bb8bcab4-a3e5-4985-8967-ae7107596bc1` 遇到同工作区另一组测试并行重建 demo，报 Kotlin daemon / kapt 缓存删除失败。已保留 `baseline-build-failed.log`，等待该共享构建结束后才重试成功；这次环境失败不计入目标缺陷证据。

## 自动化回归与并行修复边界

复用前轮 `DataBindingResourceRootsCompileFlowTest`，其 `JuggCompiler` / `ApkParser` 真实 DEX 断言保护 XML 替换后 Binding 类型与输出完整性，不新增生产 seam 或仅扫描源码的守卫。ViewHolder 运行时调用链及图片绑定结果由上述模拟器验证覆盖，不重复新增同粒度测试。

```sh
./gradlew :main:test \
  --tests 'com.sickworm.intellij.jugg.compiler.databinding.DataBindingResourceRootsCompileFlowTest' \
  --tests 'com.sickworm.intellij.jugg.deploy.data.DeployDataGeneratorTest'
```

本轮执行期间，并行的修复任务已在同一工作区修改 `ResourceCompiler`，将 Binding 联合生成移到 root 分组之前，并把上述测试扩展为 5 项。因此本轮源码测试实际是 **5 项 Binding 回归通过、28 项部署数据回归通过，耗时 3 分 11 秒**，不能描述为“旧生产实现的红测试”或与前轮 2 tests / 1 failure 混淆。

运行时失败证据来自当时 IDE 已加载插件的实际编译和部署；测试通过证据来自包含并行生产补丁的工作区源码，两者版本边界不同。本任务没有安装或热更该生产补丁，也不据此宣称已在设备验证修复。原始 JUnit XML 与测试日志保存在本轮证据目录。

本次提交仅包含 RecyclerView demo 的 Activity、item XML 和本补充记录；并行任务的生产代码、测试扩展和文档改动保持原样，不纳入本次提交。没有功能或架构变更，不同步 AI 知识库和 Wiki。结束时恢复可运行 SDK 基线，并关闭本次启动的模拟器。

## 依据

- `docs/ai_knowledge/00_overview.md`、`99_index.md`、`98_code_map.md`。
- `09_plugin_runtime_debug.md` 的证据边界、现场保全和反证门禁；`02_compile_databinding.md` 的两阶段生成链路；`06_testing.md` 的测试价值、真实产物及 testcase 回归要求。
- `/Users/wormchen/.codex/skills/jugg-android-dev-loop/SKILL.md`，及 `references/flow_compile_deploy.md`、`policy_incremental_compile_limits.md`、`error_patterns.md`。
- 前轮复现记录、当前 demo Activity / XML、`DataBindingResourceRootsCompileFlowTest`、本轮原始编译日志 / crash / BindingImpl / 输入哈希和 JUnit 报告。
