---
title: 报告问题
description: 说明如何通过 Jugg 上传问题日志，以及 Report ID 代表什么。
status: active
tags:
  - guide
  - report
  - logs
---

# 报告问题

报告问题功能把本轮 Jugg 日志和设备错误日志打包，上传到问题报告服务。它适合在增量编译失败、部署失败、运行结果不符合预期时使用。手工报告优先使用当前可用的 Jugg 后台服务；没有后台时使用 Jugg 的公共服务。

团队后台还可以开启失败日志自动上传。自动上传仅发送到当前可用后台，不采集 adb logcat，也不会弹出确认窗口。

> [!IMPORTANT]
> GitHub Releases 和 JetBrains Marketplace 的公开包不内置 Jugg 后台服务列表。未设置 Custom Server 时，**日志反馈默认不会进行任何数据上传**；只有用户在报告窗口主动选择 `Upload logs`，才会联网向公共服务提交脱敏诊断包，选择本地保存则不会上传。下文的“自动选择的后台”仅指打包了服务列表的内部构建。公开包如由用户主动设置 Custom Server，则可向该后台上传原文日志，并可能按后台配置自动上传失败日志。

## 从哪里打开

推荐使用以下任一入口：

- 在 Android Studio 中双击 `Shift`，搜索并选择 `Report Jugg Issue`。
- 打开 `Jugg Running Panel`，点击 `Report Issue`。

也可以从 Jugg Run Configuration 打开：

1. 打开 `Edit Configurations...`。
2. 选择当前 Jugg 配置。
3. 点击 `Report issues`。

没有可用后台服务时，打开报告窗口后：

1. 核对并选择诊断文件。Jugg 日志默认选中且不可取消；工程快照默认选中，但可以取消。
2. 选择 `Upload logs` 上传，或勾选 `Save locally without uploading` 后创建本地诊断包。
3. 上传完成后复制 Report ID；失败时可以点击 `Retry Upload`，或把保留的 zip 交给维护人员。

确认窗口会展示公共上传地址 `https://jugg.sickworm.com/report_issue`。选择本地保存后，系统文件管理器会选中新生成的 zip。

存在可用后台服务时，点击报告入口后不显示文件和地址确认窗口。Jugg 使用原先默认勾选的诊断内容直接上传到该服务器的 `/report_issue`；因此不能在此流程取消工程快照或选择仅本地保存。后台上传允许 HTTP 或 HTTPS；使用 HTTP 时内容不会受 TLS 保护。目标无效或上传失败时不会转发到公共服务，结果窗口仍可对同一目标重试。

上传成功后，结果窗口会显示 8 位小写十六进制 Report ID。把它和复现步骤一起发给维护者。后台服务的复制结果另附一行 `Server Url`，标明实际使用的服务器地址。

## 会上传什么

上传内容主要用于定位本轮 Jugg 行为：

- Jugg 编译和部署日志。
- 结构化的环境和工程摘要。
- 默认勾选、可取消的 IDE、Gradle 和 included build 工程快照。
- 可取消的目标设备错误 logcat。
- 可选的 hook 调试日志。
- 描述实际 zip entry 的 `manifest.json`。

工程快照包含现存的 `project_infos.json`、`gradle_project_infos.json`，以及当前 included build 对应的 `include_build_*_gradle_project_infos.json`，脱敏后保存到诊断包的 `diagnostics/project-info/`。`applicationId` 和字段是否存在等诊断信息会保留；签名凭据、keystore、keyAlias、Manifest placeholders、APT/KAPT 参数和常见敏感字段的值会被替换。无法解析的快照、过期的 included build 快照、其他 `project_infos.db` 文件、源码和二进制依赖不会进入诊断包。hook 调试日志保存在 `diagnostics/cli/hook-debug.log`。

手工上传到明确设置的 Custom Server 或内部包根据内置列表选出的后台时，都会单独发送工程名和开发者用户名用于后台分类；公共服务仅接收诊断包。手工报告不会被标记为自动上传。**这两类后台同样接收原文 Jugg 日志、设备 logcat 和 hook 调试日志**，其中可能包含工程路径、用户名及其他敏感内容；没有可用后台时上传到公共服务或仅本地保存的日志才会脱敏。工程快照中的敏感字段始终脱敏。诊断包的 manifest 会将原文日志标为 `redaction: none`。仅向可信后台发送报告；使用 HTTP 时传输不加密。

> [!NOTE]
> 上传失败不会改变本地编译部署结果。临时 zip 保留在 `build/jugg/tmp/diagnostics`，可以重试上传；后台报告的原文日志也会保存在这里，达到 7 天后会在项目启动后的清理任务中删除。上传不跟随重定向，也不会在后台失败后改传公共服务。

## 自动上传失败日志

团队后台可以下发 `autoUploadFailureLogs=true`，让 Jugg 在最终编译失败或实际部署失败后自动上传最近两份日志。用户主动取消、跳过部署、没有设备且部署尚未开始，以及降级 Gradle 后最终成功都不会触发自动上传。一次 Run 最多上传一次。

自动上传包包含最近两份真实 Jugg 日志、环境信息、工程摘要、脱敏工程快照、存在时的 hook 调试日志和 manifest，不采集 adb logcat。日志按上述目标服务器规则处理。后台还可以通过 `autoUploadFailureLogsExcludeRegex` 排除已知错误；正则命中当前 Run 的最终错误摘要时不上传。空正则表示不过滤，非法正则会跳过本次上传。

自动上传会随诊断包提交自动上传标记、失败原因摘要和可用的详细错误，同时附带工程名、开发者用户名、插件版本与 Report ID。可用后台（包括内部包按内置列表选出的后台）会接收原始错误文本。手工反馈不带自动上传标记，仍按手工报告处理。

自动上传异步执行，不弹窗、不重试；上传失败不会改变原本的编译部署结果。只有存在可用后台服务时才上传到其 `/report_issue`（允许 HTTP 或 HTTPS）；没有可用后台时跳过，不会上传到公共服务。

## 本地日志位置

如果暂时无法上传，可以先查看最新日志：

```bash
~/.jugg/log/<工程名>_<路径 hash>/compile_latest.log
```

这个文件记录最近一次编译部署的主日志。排查部署、回退和运行时问题时，通常先看这里。

## 相关页面

- [首次运行](../onboarding/first-run.md)
- [日志文件](../reference/log-files.md)
- [编译失败](../troubleshooting/compile-failed.md)
- [改动没有生效](../troubleshooting/changes-not-applied.md)
- [无法安装、启动或进入 Debug](../troubleshooting/app-cannot-run.md)
