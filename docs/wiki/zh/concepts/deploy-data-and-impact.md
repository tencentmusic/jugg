---
title: 部署数据与影响分析
description: 解释增量编译产物如何被归类为在线 class、Hot Fix、overlay 或 APK 更新，以及结构变化如何触发下一轮补编译。
status: active
tags:
  - concept
  - deploy
  - impact
---

# 部署数据与影响分析

增量编译生成 DEX、资源、Manifest 和 native lib 后，Jugg 还不能立即把“发生变化的文件”原样发送到设备。它需要先判断未修改的调用方是否仍兼容新的 class 结构，再把最终产物按设备上的生效方式分类。

这一步连接增量编译和增量部署：影响分析决定是否继续补编译源码，部署数据决定本轮使用 Apply Changes、Hot Fix、APK 更新还是兼容部署。

## class 结构变化会把流程送回编译阶段

如果类 A 删除方法或修改字段签名，只重新编译 A 仍会在旧 APK 中留下按旧签名调用的类 B。本轮编译虽然成功，B 运行到旧调用时仍会抛出 `NoSuchMethodError` 或 `NoSuchFieldError`。

```text
A 的新 class 生成
  -> 对比 APK 基线中的旧 class
  -> 查询旧调用方、字段访问方和子类
  -> 命中的源码加入下一轮编译
  -> 没有新的受影响源码后再生成最终部署数据
```

方法、字段、继承和泛型结构的传播规则见[重编译 / 扩散编译](./incremental-compile/recompile-propagation.md)。编译期常量被内联后没有普通运行时引用，由[常量引用分析](./incremental-compile/const-ref.md)单独找回使用方。本页不重复这两套编译机制。

## 最终产物按生效方式分类

影响传播结束后，Jugg 把可部署内容分成几类：

| 部署数据 | 判断来源 | 生效方式 |
|---|---|---|
| 可在线修改的 class | 新旧 class 结构保持兼容 | 作为 modified class 进入 Apply Changes |
| 新增 class | APK 和已部署历史中不存在 | 作为 new class 进入 Apply Changes |
| 需要 Hot Fix 的 class | 结构变化、library dex、multi-dex 或其它在线替换边界 | 重启 App 后由运行时加载 |
| 资源与 assets overlay | 资源增量编译结果 | Apply Changes 或 Direct Overlay |
| APK 更新文件 | Manifest、配套资源表、已经生成的 native lib | 写回对应 APK 并重新签名安装 |

同一个源码修改可能同时产生多类数据。例如修改 Manifest 引用的资源时，Manifest 和配套资源表进入 APK 更新，普通资源和 class 仍可在安装后继续通过 overlay 下发。

## 首次资源部署需要完整 overlay

设备第一次接收资源 overlay 时，只有本轮变化文件不足以形成完整的新资源视图。Jugg 会从 APK 基线和本轮产物中补齐资源集合，再生成第一次 full resource overlay。

后续成功部署已经建立资源历史，Jugg 才继续发送相对上轮状态的变化。部署失败时不会提前提交这份历史，因此下一轮仍能重新生成完整结果，而不会把未成功的资源当成设备已有内容。

## 多 APK 只裁剪传输数据

base、split、test APK 可能拥有同名的 `resources.arsc`、DEX 或 overlay 路径。每项部署数据会记录真实目标 APK，单次传输前再按 applicationId 和 APK 归属裁剪。

裁剪结果只用于当前 APK 的一次下发。部署历史必须在所有目标完成后使用整轮原始数据提交，不能用某个 APK 的局部结果推进全局状态。否则下一轮可能错误认为其它 APK 的变化也已经部署。

## 分类如何决定部署结果

```text
最终部署数据
  -> 存在 APK 更新文件：先更新并安装 APK
  -> 存在需要 Hot Fix 的 class：重启 App
  -> 只有普通 class / overlay：Apply Changes 并重建 Activity
  -> 设备需要兼容部署：转换为重启后加载的产物
  -> 设备未 ready 且状态可校验：可使用 Direct Overlay 传输
```

分类决定产物应该怎样生效，但不能跳过设备状态校验。本地历史、deployment cache 和设备 overlay ID 不一致时，Jugg 会先 Recover；状态恢复完成后才使用本轮数据。

## 历史只能在部署成功后提交

影响分析和部署分类得到的都是本轮暂存结果。只有全部编译、目标 APK 更新、设备传输和生命周期动作成功后，新的 class 结构、文件历史和 overlay ID 才会成为下一轮基线。

部署中途失败或用户取消时，暂存结果不会伪装成已经生效。下一次运行仍基于上一轮成功状态重新计算影响和部署数据。

## 相关页面

- [增量编译](./incremental-compile/)
- [重编译 / 扩散编译](./incremental-compile/recompile-propagation.md)
- [常量引用分析](./incremental-compile/const-ref.md)
- [增量部署总览](./deploy-strategy.md)
- [Apply Changes 中的 class 与 overlay](./apply-changes.md)
- [APK 更新与安装](./apk-update-and-install.md)
- [部署状态与恢复](./deploy-state-recover.md)
