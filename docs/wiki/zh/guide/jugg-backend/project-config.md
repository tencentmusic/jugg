---
title: Jugg 后台项目配置下发
description: 说明 Jugg 后台可以按项目下发的配置项，以及适合自建后台维护的配置场景。
status: active
tags:
  - guide
  - backend
  - configuration
---

# Jugg 后台项目配置下发

项目配置下发用于把团队默认配置集中放在后台。插件检查更新时会携带当前项目名，后台可以返回该项目的 `customConfigJson`，插件随后将配置应用到本地项目。

## 适合下发什么

| 配置 | 用途 |
|---|---|
| `servers` | 提供可用后台地址列表，支持插件后续切换服务器 |
| `buildFileRules` | 标记需要纳入变更检测的构建文件规则 |
| `dontFilterIgnoredFileRules` | 对被忽略文件中的特定规则仍执行变更检测 |
| `moduleCustomConfigs` | 为指定模块补充 classpath、同步路径或忽略过滤策略 |
| `customCompilers` | 给项目下发自定义编译器 jar |
| `embeddedApksSearchRules` | 配置嵌入式 APK 的搜索规则 |

旧字段 `buildFileList` 已不建议新增使用；新后台优先维护 `buildFileRules`。

## 模块配置

`moduleCustomConfigs` 适合解决“只有某些模块需要额外规则”的问题：

| 字段 | 说明 |
|---|---|
| `moduleStdPath` | 模块的标准化路径 |
| `customClasspath` | 同步并加入 classpath 的路径 |
| `customSyncFilePath` | 只需要同步的路径 |
| `isDoNotIgnored` | 即使模块命中忽略规则，也不要从变更模块中排除 |

这类配置建议只给确实需要补充产物或同步规则的模块使用，避免把所有模块都写入后台配置。

## 自定义编译器配置

后台可以在项目配置中返回 `customCompilers`，让插件下载团队自定义编译器：

| 字段 | 说明 |
|---|---|
| `jarFileName` | 下载后的 jar 文件名 |
| `path` | 本地路径或 HTTP 下载地址 |
| `md5` | 文件校验值 |

如果自建后台负责托管自定义编译器，通常同时实现 `/download_custom_compiler`，并让 `path` 指向该下载接口。

## 配置维护建议

- 按项目名维护配置，避免把所有团队配置混在一个响应里。
- 默认返回空数组或 `null`，只给需要特殊处理的项目下发配置。
- 配置变化后建议让用户重新检查更新或重启 IDE，确保配置应用到当前项目。
- 不要把账号密码、私钥等敏感信息放进项目配置响应。
- 自定义编译器 jar 应当有版本管理和 md5 校验，便于回滚。

## 相关页面

- [自建接入清单](./self-hosting.md)
- [自定义编译器](../custom-compiler.md)
- [配置](../../reference/configuration.md)
