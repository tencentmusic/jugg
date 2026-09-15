<p align="left">
  <a href="./SECURITY.md">English</a> | <strong>简体中文</strong>
</p>

# 安全策略

## 受支持的版本

安全更新针对 GitHub 上最新的稳定版 Jugg 发布。

| 版本 | 是否支持 |
| --- | --- |
| [最新 GitHub Release](https://github.com/tencentmusic/jugg/releases/latest) | 是 |
| 更早的 GitHub Release | 请升级到最新稳定版 |
| Nightly / 开发构建 | 仅尽最大努力处理 |

## 如何报告漏洞

不要通过公开 GitHub Issue、Pull Request 或 Discussion 报告疑似安全漏洞。

请发送邮件至 [ch.operation@gmail.com](mailto:ch.operation@gmail.com) 私下报告。如果本仓库 Security 页提供 **Report a vulnerability**，也可以改用 GitHub 私密漏洞报告。

请尽量提供：

- 问题描述，以及为什么它是安全问题
- 受影响的 Jugg 版本或构建号
- 复现步骤，或最小概念验证
- 预期影响，例如凭据泄露、非预期代码执行，或诊断数据泄漏

除复现所需内容外，请不要附带签名密钥、生产凭据或私有源码。

## 后续处理

- 我们会尽快确认收到报告，通常在几个工作日内。
- 我们会告知该报告被接受、被拒绝，或还需要补充信息。
- 若被接受，我们会修复问题，并在发布可用修复后再协调公开披露。
- 如果报告者希望署名，我们可能会致谢。

请在公开披露前，给我们合理时间完成调查和修复。

## 范围

本策略覆盖 Jugg 自身：Android Studio / IntelliJ 插件、`jugg` CLI、MCP 工具、问题报告上传、自定义编译器加载，以及 JVMTI agent。

普通编译或部署缺陷，以及用 Jugg 构建的应用自身问题，不在本策略范围内，除非它们暴露了 Jugg 的安全问题。

非安全缺陷请使用 [Bug 反馈](https://github.com/tencentmusic/jugg/issues/new?template=01_bug_report_zh.yml)。
