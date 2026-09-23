---
title: 自定义 APK 签名脚本
description: 说明如何让 Jugg 在增量改写 APK 后改用项目脚本签名，以支持平台证书、厂商密钥或服务器签名流程。
status: active
tags:
  - capability
  - deploy
  - apk
  - sign
---

# 自定义 APK 签名脚本

平台证书、厂商密钥或远端签名服务无法放进本地 Gradle `SigningConfig` 时，Jugg 默认的本地 keystore 签名会产出签名身份错误的 APK，更新已有系统包时会得到 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`。启用 `Enable custom APK sign script` 后，Jugg 把待签名 APK 交给项目脚本处理，写回内容、校验、原子替换和后续安装流程保持不变。

## 支持范围

| 场景 | 当前支持情况 | 用户可见结果 |
|---|---|---|
| Manifest 变化写回 APK | 支持 | zipalign 后执行项目脚本签名，校验通过才替换并安装 |
| native lib 变化写回 APK | 支持 | 同上 |
| embedded dex 与 Embedded APK | 支持 | 同上 |
| Gradle 完整构建产出的 APK | 不支持 | 签名仍由 Gradle 自身完成，不执行脚本 |
| CLI 增量构建与手工导出增量 APK | 不支持 | 继续使用默认本地签名 |
| 本地签名配置无效 | 支持 | 启用脚本时不再要求本地 `SigningConfig` 可用 |

同一轮 Run 里，只有 Jugg 自己改写过的 APK 会走项目脚本。

## 触发与结果

```text
本轮生成 Manifest、resources.arsc 或 native lib 增量产物
  -> 写入最近的 Gradle APK
  -> zipalign
  -> 启用脚本: <配置的命令> <待签名 APK 绝对路径>
  -> apksigner verify 校验脚本产出的 APK
  -> 校验通过才替换原 APK 并继续安装
```

Jugg 在本地工程根目录执行脚本，并把待签名 APK 的绝对路径作为最后一个位置参数传入。路径包含空格、括号或 Unicode 字符时仍作为单个参数传递。脚本必须原地覆盖这个 APK；签名服务把结果写到其它文件时，脚本需要在退出前替换该路径。

```bash
# Run Configuration 中配置
./scripts/sign-system-apk.sh --server production

# Jugg 实际执行
./scripts/sign-system-apk.sh --server production "/path/to/.app-debug.apk.tmp_aligned"
```

示例工程 `android_demo_project` 提供 `scripts/sign-system-apk.sh`，接收 `$1` 作为 APK 路径，使用工程已有默认签名和 Android SDK `apksigner` 原地签名，可直接用于验证这条能力。

## 使用边界

- 勾选开关后脚本不能为空，为空时 Run Configuration 校验失败；未勾选时只显示开关，现有签名行为完全不变。
- 脚本退出码 `0` 只表示执行完成。Jugg 随后仍执行 `apksigner verify`，校验失败时原 APK 保持不变，本轮不进入安装。
- 脚本非零退出、被取消或校验失败时，当次更新不会回退到本地 keystore 签名，避免产生签名身份错误的 APK。
- 脚本输出转发到 Run 窗口，取消 Run 会终止脚本进程。Jugg 不对已知失败自动重试，上传、等待、下载及其重试策略由项目脚本负责。
- 部署 retry 和 recover 安装的是已经签好的 APK，不会重复执行脚本。
- 多设备 Run 可能对同一个 APK 重复执行脚本，重复执行的语义和副作用由项目脚本负责。
- 脚本内容属于敏感配置，Jugg 日志和诊断信息只显示 `(configured)` / `(not_configured)`，不记录脚本原文。
- 远程编译只改变产物来源：APK 的改写和签名仍在连接设备的本地 IDE 主机完成。

## 相关页面

- [APK 更新与安装](../../concepts/apk-update-and-install.md)
- [自定义 APK 安装脚本](./custom-apk-install-script.md)
- [多 APK](./multi-apk.md)
- [多设备](./multi-device.md)
- [无法安装、部署、启动或 Debug](../../troubleshooting/app-cannot-run.md)
