---
title: 多 APK
description: 说明 Jugg 对 base、split、test APK 等多 APK 目标的部署分流能力。
status: active
tags:
  - capability
  - deploy
  - multi-apk
---

# 多 APK

Jugg 支持把同一轮部署产物分配到正确的 APK 目标。对于 base APK、split APK、app-test APK 或 library test APK 等场景，部署数据会携带目标 APK 归属，避免资源、dex 或 overlay 被写入错误位置。

## 多 APK 归属规则

| 操作场景 | 当前支持情况 | 部署策略 |
|---|---|---|
| base + split APK | 支持 | 按目标 APK 路径过滤部署项 |
| app androidTest APK | 支持 | 与 app APK 按 applicationId 分组部署 |
| self-targeting library Test APK | 支持补齐 | 缺失时可懒加载并记录 build history |
| 多 APK 同名资源 | 支持区分 | 按目标 APK + relative path 判定覆盖 |
| resources.arsc 或 full resource push | 支持 | 避免主包和 test APK 资源互相过滤 |

> [!IMPORTANT]
> 多 APK 场景不能只看文件相对路径。Jugg 会优先使用部署项的目标 APK 归属来判断该产物应该写入哪个 APK 或 overlay。

## 这项能力如何生效

```text
生成 JuggDeployData
  -> DeployItem 记录 targetApkPaths
  -> JuggDeployTask 按 applicationId 分组
  -> filterForApks() 裁剪当前 APK scoped data
  -> JuggDeployer 对每组 APK 执行 install / swap
  -> 整轮成功后提交全局部署历史
```

`filterForApks()` 只用于单次 transport 的 APK 分流。裁剪后的 scoped data 不能拿来更新全局部署历史；全局 commit 必须使用整轮成功后的原始 deploy data。

## Android Test 相关场景

当 sourcePath 指向 library androidTest，而目标 test APK 还不存在时，Jugg 可以补齐对应 library Test APK，并在安装成功后更新 overlay ids。这样第一轮 replay 不会因为新增 APK 的 checkpoint 缺失而误判状态不匹配。

## 相关页面

- [部署历史与缓存](./deploy-history-cache.md)
- [Recover 与 Retry](./recover-and-retry.md)
- [Application Android Test](../test/application-android-test.md)
- [Library Android Test](../test/library-android-test.md)
