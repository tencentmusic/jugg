---
title: 快速开始
description: 安装 Jugg、完成首次运行，并了解远程编译、回退和问题反馈这些开始使用前必须知道的事项。
status: active
tags:
  - onboarding
---

# 快速开始

Jugg 是面向大规模 Android 工程的秒级增量构建插件。它以 Android Studio 插件形式接入，不需要修改工程文件；首次运行会复用 Gradle 构建产物建立基线，之后优先走旁路增量编译和热部署链路。

日常使用时，你只需要选择 Jugg 自动生成的 Run Configuration，像原生 Run 一样点击运行。Jugg 会判断本次改动能否增量处理，必要时自动回退到 Gradle。

## 先确认环境

| 项目 | 要求 |
|---|---|
| IDE | Android Studio 2021 至今版本；IntelliJ IDEA 2021.1.3 至 2025.1 系列 |
| AGP | 3.4 - 9.1 |
| Kotlin | 1.3 - 2.2 |
| Android 设备 | Android 8 及以上 |
| 工程要求 | 不需要改 Gradle 脚本，不需要接入业务定制代码 |

未验证版本可能有少量兼容问题。遇到问题时，优先保留日志并提交 Issue ID。

## 第一次使用按这个顺序

| 步骤 | 页面 | 要做什么 |
|---|---|---|
| 1 | [安装](./installation.md) | 下载插件，通过 Android Studio 从本地磁盘安装，重启 IDE |
| 2 | [首次运行](./first-run.md) | 等待 Sync 完成，选择 `jugg:app` 这类运行配置，点击 Run |
| 3 | [云开发机配置](./agent-setup.md) | 本地机器编译慢、磁盘紧张，或团队已有云编译资源时再配置 |

如果只是本地开发，完成前两步就可以开始使用。云开发机不是必选项。

## 开始前必须知道的运行逻辑

- 首次运行、修改 Gradle 文件、切分支后缺少基线时，Jugg 会回退到 Gradle 编译。
- Gradle 编译完成后，Jugg 会在后台收集后续增量编译需要的产物；收集完成后才进入稳定的增量状态。
- 删除类、资源或 Manifest 节点时，增量链路不会真正从设备侧清除旧内容。需要验证删除效果时，主动做一次 Gradle 构建或重新安装。
- 注解处理只保证已适配场景生效。新增或修改未适配注解时，建议回退 Gradle 对照。
- 清除 App 数据会让增量部署历史丢失。再次点击 Run 后，Jugg 会检测到状态缺失并自动恢复部署。
- 编译和部署都支持取消，不需要等待完整流程结束。

## 什么时候主动回退 Gradle

| 场景 | 建议 |
|---|---|
| 手动删除过 `build` 目录或部分构建产物 | 主动回退 Gradle，重建基线 |
| 本轮增量结果和预期不一致 | 先跑一次 Gradle 对照，再提交日志 |
| 修改了注解处理、字节码插桩、复杂构建脚本 | 直接使用 Gradle 构建验证 |
| 切分支后出现大量差异 | 先接受一次 Gradle 编译 |

没有文件修改时再次点击 Jugg Run，也可以主动选择降级到 Gradle。

## 出问题怎么反馈

1. 打开当前 Jugg Run Configuration。
2. 点击 `Report issues` 上传日志。
3. 上传完成后点击 `Copy Issue ID and Close`。
4. 把 Issue ID 发给维护者，并说明本轮操作和期望结果。

日志也可以从工程目录下查看：

```bash
build/jugg/log/compile_latest.log
```

## 相关页面

- [安装](./installation.md)
- [首次运行](./first-run.md)
- [云开发机配置](./agent-setup.md)
- [运行 App](../guide/run.md)
- [回退与限制](../concepts/fallback-and-limits.md)
