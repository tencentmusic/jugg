---
title: assets 与 native lib
description: 解释 assets 为何能走普通 overlay 而 native lib 必须写回 APK，以及两者在部署方式上的差异。
status: active
tags:
  - concept
  - compile
  - assets
  - native
---

# assets 与 native lib

`assets/` 和 native lib 都不需要 aapt2，也不属于 Java/Kotlin 源码编译。但它们的部署方式不同：assets 可以作为普通 overlay 叠加生效，native lib 必须写回 APK。

## 不是所有产物都能叠加生效

增量部署的快路径是 overlay：把变化文件作为附加层叠在已安装 APK 之上，不重装整包。但这条快路径有前提，运行时必须能从 overlay 路径读到这类文件。

`assets/` 满足这个前提，系统会从 overlay 读取。native lib（`.so`）不满足：动态库的加载路径绑定在 APK 内，运行时不会从普通 overlay 加载新的 `.so`。所以这两类文件虽然都绕过了 aapt2，部署方式却必须分开。

## 按可叠加性分流部署

assets 走普通 overlay：

```text
assets 变化文件
  -> 复制为可部署的 assets overlay
  -> 写入 staging
  -> 部署阶段叠加生效
```

native lib 走写回 APK：

```text
native lib（.so）变化
  -> 进入「需要写回 APK 的文件」清单
  -> 写回目标 APK 并重新签名
  -> 部署阶段按 update apk 模式安装更新后的 APK
```

assets 变化不触发 aapt2 的 compile/link，也不生成 `resources.arsc`。native lib 这边，Jugg 处理的是已经产出的 `.so` 文件；C/C++ 源码、CMake、NDK、ABI 和打包规则仍由 Gradle/NDK 完成。

## 与资源编译的区别

| 类型 | 是否需要 aapt2 | 是否生成资源表 | 部署方式 |
|---|---|---|---|
| `res/` | 是 | 是 | 资源 overlay（含 `resources.arsc`） |
| `assets/` | 否 | 否 | 普通 overlay 叠加 |
| native lib | 否 | 否 | 写回 APK 后 update apk |

## assets 与 native lib 的部署约束

这种分流也带来几条不能混淆的部署约束：

- **assets 不触发资源 link**：assets 变化不应走 aapt2，也不会产生资源表。
- **native lib 必须 update apk**：`.so` 更新不能按普通 assets overlay 理解，必须写回 APK 并重新安装。
- **保留目标 APK 归属**：多 APK 场景下，输出要带上各自的 APK 归属，不能默认复制给所有 APK，避免文件下发到错误 APK。

## 相关页面

- [增量编译总览](./index.md)
- [资源增量编译](./resource.md)
- [so 更新能力](../../capabilities/compile/so-update.md)
- [多 APK 部署](../../capabilities/deploy/multi-apk.md)
