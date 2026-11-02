# JVMTI 检测限定本轮启动目标

## 问题与范围

Jojo 2026-11-02 20:10:33～20:11:11 的现场中，主 App 首轮已确认 JVMTI 可用，但两个未启动的自指向 library androidTest 包持续返回未知，使检测耗时 38.254 秒。

仅修正检测目标：普通 Run/Debug 与失败重试使用 DeployTargetManager 的启动包名；androidTest 使用所选测试 APK 的 instrumentation target。延迟启动或未选到测试 APK 时不做部署后检测。不调整 agent 推送、超时计时及 sandbox 复用。

## 实现与验证

JVMTI helper 显式接收目标包名，不再枚举部署 APK。调用方在实际启动分支记录目标，重试使用其重启的同一目标。

复用 JuggDeployerHelperDeployFlowTest（L2 虚拟设备 Flow），验证无关测试包状态不影响主 App，并保留目标不可用时的兼容回退判断。验证结果：

- 修复前新增回归用例失败，复现未启动测试包状态影响检测结果。
- 使用本机 JDK 17 与 Android SDK 执行 `:idea:test --tests "com.sickworm.intellij.jugg.deploy.run.JuggDeployerHelperDeployFlowTest"`，最终 40 项全部通过（含 3 项新增目标选择回归），同时完成 main/idea Kotlin 编译。
- 最初未配置 ANDROID_HOME；补齐 SDK 后 demo 在 JDK 21 下遭遇旧 D8 空指针异常。切换现有 JDK 17 后构建和测试成功。
- `git diff --check` 通过；本次没有新增日志调用。
- 未安装新插件在 Jojo 真机复测，实际耗时改善仍待部署后确认。

## 文档

同步 03_runtime_jvmti.md 的检测契约。本次恢复已有部署行为，不改变操作入口、支持范围和恢复步骤，无需更新 Wiki。
