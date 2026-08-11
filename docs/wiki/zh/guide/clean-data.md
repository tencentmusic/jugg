---
title: 清理数据
description: 说明如何用 Clean Reinstall 清理 App 数据并重装，以及它和手机系统里手动清数据的区别。
status: active
tags:
  - guide
  - clean-data
  - reinstall
---

# 清理数据

需要一个干净 App 现场时，使用 Jugg 的 Clean Reinstall。它会清理 App 数据、重装 APK，并重新建立 Jugg 的部署状态。

## 什么时候用

- 验证首次启动、首次登录、数据库迁移或权限弹窗。
- App 数据被手动改坏，需要重建现场。
- 设备上的安装状态和 Jugg 记录不一致。
- 删除类、资源或 Manifest 节点后，要确认旧内容不再存在。

如果只是想完整 Gradle 构建，不需要清数据，使用 [降级 Gradle 编译](./downgrade-gradle.md)。

## 从哪里触发

常见入口在降级 Gradle 的确认弹窗中：

```text
Confirm fallback
  -> Clean And Reinstall
```

CLI 场景可以执行：

```bash
jugg clean-reinstall
```

成功后重新进入要验证的页面。清数据会清掉登录态、本地数据库和缓存，这是预期行为。

## 不建议手动清数据

直接在手机系统设置里清除 App 数据，会同时清掉 Jugg 放在 App 数据区里的部署记录。下一次运行时 Jugg 会尝试恢复，但如果你的目标本来就是清数据测试，Clean Reinstall 更稳。

## 相关页面

- [运行 App](./run.md)
- [降级 Gradle 编译](./downgrade-gradle.md)
- [部署状态与恢复](../concepts/deploy-state-recover.md)
- [Clean Reinstall 能力](../capabilities/deploy/clean-reinstall.md)
- [无法安装、启动或进入 Debug](../troubleshooting/app-cannot-run.md)
