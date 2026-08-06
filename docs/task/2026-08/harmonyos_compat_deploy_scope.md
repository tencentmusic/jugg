# HarmonyOS 自动兼容部署条件放开

## 背景

当前 `CompatDeployHelper` 仅在 `hw_sc.build.platform.version` 的 major/minor 大于等于 4.2 时自动启用兼容部署。需求调整为所有 HarmonyOS 设备均自动启用兼容部署，不再限制系统版本。

## 已批准范围

- `main/src/main/java/com/sickworm/intellij/jugg/deploy/CompatDeployHelper.kt`
  - 以非空的 `hw_sc.build.platform.version` 属性识别 HarmonyOS。
  - 删除 HarmonyOS 4.2 最低版本比较。
  - 属性缺失或空白时保持现有非 HarmonyOS 路径。
- `idea/src/test/java/com/sickworm/intellij/jugg/deploy/run/JuggDeployerHelperDeployFlowTest.kt`
  - 复用现有 L2 owner，覆盖低于 4.2 的 HarmonyOS 版本自动进入兼容部署。
  - 保留无 HarmonyOS 属性时使用普通部署的回归断言。
- `docs/ai_knowledge/03_runtime_jvmti.md`
  - 更新 HarmonyOS 自动兼容部署规则。
- `docs/ai_knowledge/03_deploy_core.md`
  - 更新 `CompatDeployHelper` 的 HarmonyOS 判断约束。

## 验证策略

1. 先更新现有 L2 测试并在旧实现上取得失败证据。
2. 修改生产代码后重新执行定向 L2 测试。
3. 执行 `./gradlew :idea:compileKotlin`。
4. 执行 `git diff --check` 并检查本次提交文件范围。

## 范围外

- 不修改 `jvmti_agent`。
- 不修改 Android Studio deploy compat 模块。
- 不修改手动 Force compatible deployment 的设置和设备记录语义。
- 不处理工作区中已有的 Run Configuration、`plugin.xml` 和图标改动。
