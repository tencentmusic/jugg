---
title: HarmonyOS 兼容部署
description: 说明 Jugg 如何识别 HarmonyOS 设备并自动使用兼容部署，以及与普通 Android、HyperOS 和手动兼容记录的差异。
status: active
tags:
  - capability
  - deploy
  - harmonyos
  - compatibility
---

# HarmonyOS 兼容部署

HarmonyOS 设备虽然保留 Android 应用运行环境，但在线类替换和 overlay 行为不能简单按 Android API 等级判断。Jugg 会在部署前识别 HarmonyOS，并直接选择兼容部署，避免先尝试不可靠的普通路径再失败重试。

## 所有可识别 HarmonyOS 版本都自动启用

Jugg 读取设备属性 \`hw_sc.build.platform.version\`。只要该值存在且非空，就视为 HarmonyOS 设备，不再要求最低 HarmonyOS 版本。

\`\`\`text
读取目标设备属性
  -> HarmonyOS 属性非空
  -> 本轮直接使用兼容部署
  -> App 重启后由 Jugg Runtime 加载增量产物
\`\`\`

属性缺失或为空时，继续使用普通 Android 判断，不会仅凭厂商名称猜测 HarmonyOS。

## 兼容部署改变什么

普通 Hot Reload 优先通过 Android Studio Apply Changes / JVMTI 在线替换。兼容部署会把本轮可在线替换的 class 也转入重启后生效的热修复路径，并继续处理资源和其它 overlay。

因此 HarmonyOS 上更常见的结果是 App 重启，而不是保持当前进程完成纯在线替换。这是为运行结果可靠性做出的取舍。

## 与其它兼容条件的关系

- Android 11 以下设备本身不具备所需 overlay swap 条件，也会使用兼容部署。
- 手工 Force compatible deployment 仍然有效。
- 某次真实 JVMTI 失败后记录的 app/device 组合仍会进入兼容部署。
- HyperOS 可以按具体 app 记录兼容问题，不等同于所有设备、所有 app 自动启用。

HarmonyOS 自动识别不会清除或改写已有手工兼容记录。

## 使用建议

- 第一次安装或 Jugg Runtime 变化后，先完成一次完整 Gradle 构建和安装。
- 部署成功后等待 App 完成重启，再验证修改。
- 若资源或 class 仍未生效，先看 \`compile_latest.log\` 中是否显示兼容部署和重启结果。
- 设备属性无法读取时，确认 ADB 连接与 shell 权限，再考虑手工启用兼容部署。

## 相关页面

- [设备兼容部署](../../guide/compat-device.md)
- [兼容部署原理](../../concepts/compat-deploy.md)
- [Jugg Runtime](../../concepts/jugg-runtime.md)
- [JVMTI Runtime](./jvmti-runtime.md)
