# 回退共享库补回后的强制 overlay 优先级

## 决策依据

用户要求回退 `792d989c1`。该提交在成功补回共享库后调用 `setLoaders()`，把 overlay 优先当作修复目标；用户后续对照完整 Gradle 构建确认，宿主与共享库有同路径 asset 时，完整构建后的共享库内容优先。

用户实测：完整构建后高、低版本均展示共享库 asset；部署 overlay 后，高版本再次登记共享库展示 overlay asset；低版本登记后 hot reload 展示 overlay asset，重启后展示共享库 asset。当前接受 Apply Changes/ResourcesLoader 在同路径 asset 冲突时的行为差异，不扩大改动到整个资源优先级模型。

## 回退范围

- `RuntimeSharedLibraries.java` 恢复到 `792d989c1` 父提交的行为，删除成功状态汇总与 `restoreLoaderPriority()`，保留原 WebView/ICU 共享库路径恢复逻辑；按用户要求在补回位置添加英文注释，说明同路径 asset 的已知差异及不强制 loader 优先的原因。
- 删除被回退提交新增的 `shared_library_overlay_asset_priority_fix.md`，以本页记录最终决策。
- 运行时知识库改为描述实际路径恢复行为和已接受的 asset 冲突限制。
- Agent 版本升至 `1.0.90`，不降回已发布/缓存过的 `1.0.88`，确保设备从 `1.0.89` 更新到回退后的 bundle。

## 验证

失败依据是用户对完整构建与增量部署的实测对照。回退恢复既有 runtime，不新增绑定实现细节的测试；复用 `ApplyChangesOverlayPolicyTest`、`FlutterAssetRefreshTest`、`InstrumenterSourcePolicyTest` 共 26 项定向回归，并构建双 ABI agent、DEX 与插件包。

检查 runtime 源码相对父提交仅多出已知缺陷注释；最终 instruments DEX/runtime class 不含 `restoreLoaderPriority`，且保留共享库恢复逻辑。构建前清理已有残留问题涉及的生成文件 `jvmti_agent/build/tmp/buildInstrumentJar/bootstrap.jar` 和 `jvmti_agent/build/outputs/jugg-instruments.jar`，防止旧方法残留。核对插件内嵌 bundle/runtime 与本次输出一致。

本地验证已完成：

- Runtime 源码相对 `792d989c1` 父提交仅增加三行英文已知缺陷注释，行为完全回退。
- 26 项定向测试全部通过（4 + 12 + 10，0 failure/error/skipped）；注释补充后重新打包通过。
- JDK 17 下双 ABI agent、DEX 与 `:idea:buildPlugin` 构建通过；双 ABI 编译参数确认为 `AGENT_VERSION=1.0.90`。
- 最终 DEX/runtime class 保留共享库恢复逻辑且不含 `restoreLoaderPriority`；插件内嵌 bundle/runtime、bundle 内 native/instruments 与本次输出逐字节一致。
- `git diff --check` 通过；本次未新增日志或设备操作，真机由用户验证。

## 文档范围

同步 `03_runtime_jvmti.md`；本次撤销额外的 loader 调序，不改变现有用户操作和支持范围，不新增 Wiki 操作说明。
