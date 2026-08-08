---
title: 多设备
description: 说明 Jugg 对多台已选设备逐台部署和结果汇总的能力。
status: active
tags:
  - capability
  - deploy
  - multi-device
---

# 多设备

Jugg 支持对 Run 配置中选中的多台设备执行同一轮编译结果部署。部署会按选择顺序逐台运行，并在最后汇总成功状态、最高优先级 deploy type、失败原因和是否可回退 Gradle。

## 多设备部署行为

| 操作场景 | 当前支持情况 | 部署策略 |
|---|---|---|
| 多台设备同时运行 | 支持 | 编译一次，逐台部署 |
| 不同设备部署策略不同 | 支持 | 每台设备独立 recover、retry 和 install |
| 部分设备失败 | 支持汇总 | 合并失败原因并判断整轮 fallback 资格 |
| 自动 Gradle fallback | 支持 | 所有失败都允许 fallback 时整轮重跑 |
| 多设备 deploy type 展示 | 支持 | 按 INSTALL > EMBEDDED > COMPAT_HOT_FIX > HOT_FIX > HOT_RELOAD 取最高优先级 |

> [!NOTE]
> 多设备 fallback 是整轮 Run 级别，不是只对失败设备重新编译或重新部署。

## 这项能力如何生效

```text
Run 选中多台设备
  -> JuggCompileHelper 编译一次
  -> 按选择顺序 deployDevice()
  -> 每台设备调用 JuggDeployerHelper.deploy()
  -> 汇总 DeployTaskResult 列表
  -> 全部成功: 结束本轮
  -> 部分失败且允许 fallback: force Gradle 后重跑
  -> 否则展示失败原因
```

每台设备都有自己的 deployment cache、overlay id 和 ready 状态。因此同一轮 Run 会按设备分别选择 Hot Reload、recover 或 reinstall，再由 Run 层统一收口最终结果。

## 与部署历史的关系

多设备不会把某台设备的临时 scoped data 直接提交成全局文件历史。Jugg 会在成功路径中按部署流程推进 history 和 overlay checkpoint，避免不同设备间串状态。

## 相关页面

- [多设备选择](../../guide/multi-device.md)
- [Recover 与 Retry](./recover-and-retry.md)
- [部署历史与缓存](./deploy-history-cache.md)
- [Clean Reinstall](./clean-reinstall.md)
- [Gradle 回退](../compile/gradle-fallback.md)
