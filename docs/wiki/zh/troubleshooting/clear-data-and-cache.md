---
title: 清除 App 数据或缓存会影响增量编译吗？
description: 说明清除 App 数据或缓存对增量部署的影响、重新运行的恢复行为，以及清数据测试和导出增量 APK 的选择。
status: active
tags:
  - troubleshooting
  - faq
  - clean-data
  - deploy
---

# 清除 App 数据或缓存会影响增量编译吗？

影响的是设备上的增量部署结果。普通增量部署的产物保存在 App 数据区的 `code_cache/` 中。直接在手机系统设置里清除 App 数据，会同时删除这些产物；再次打开 App 时，之前的增量改动就会丢失。

清缓存是否有影响，取决于清理范围：如果清理操作删除了 `code_cache/` 中的增量产物，结果相同；只清理业务缓存且保留增量产物时，不会因此丢失增量改动。

手机上的清理操作不会删除电脑上已经编译好的增量产物。重新执行一次 Jugg Run，Jugg 会检测设备部署状态，恢复并重新下发增量产物。只在手机上重新打开 App 不会触发这次恢复。

需要清数据测试，同时保留当前改动时，可以在降级 Gradle 的 `Confirm fallback` 弹窗中选择：

| 使用场景 | 入口 | 处理结果 |
|---|---|---|
| 在当前设备上清数据验证 | `Clean And Reinstall` | 先清数据、重装 APK，再恢复当前增量部署，一次操作完成 |
| 把包含当前改动的 APK 交给测试，且清数据后仍保留改动 | `Export incremental APK` | 将已编译的增量结果写入 APK 并重新签名；安装导出的 APK 后，清除 App 数据不会移除已写入 APK 的改动 |

导出前需要先完成当前改动的编译，具体步骤见 [导出增量 APK](../guide/export-incremental-apk.md)。保留增量改动不等于保留 App 数据：清数据仍会移除登录态、本地数据库和业务缓存。

## 相关页面

- [清理数据](../guide/clean-data.md)
- [导出增量 APK](../guide/export-incremental-apk.md)
- [部署状态与恢复](../concepts/deploy-state-recover.md)
