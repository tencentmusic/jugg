---
title: 云开发机配置
description: 配置 Jugg 远程编译所需的云开发机、同步模式、账号、目录和代理。
status: active
tags:
  - onboarding
  - remote
---

# 云开发机配置

云开发机配置是可选项，前提是你已经有可用的云开发机构建资源，或团队提供了可申请的云编译环境。配置完成后，本地 Android Studio 仍然负责入口和部署，Gradle 构建可以放到远端机器执行。

Jugg 会记住最后一次成功的远程编译参数。新工程首次打开时会带入这套配置；后续如果在某个工程里修改，不会自动同步到其他已打开工程。

## 申请云开发机

如果还没有云开发机，先确认团队是否提供 Jugg 云编译通用模板。模板通常已经预装 Java、Android SDK、Git 等基础环境，Gradle home 指向大容量磁盘，申请完成后可以直接用于编译。

申请时可以选较高配置，例如 32 核、64G 内存、500G 硬盘。硬盘后续通常可以扩容；CPU 和内存扩容可能需要重启，也要注意 `/data` 之外的数据是否会丢失。

机器初始化完成后，配置远程编译时：

- `SSH host` 填机器域名或 IP。
- 通过模板申请的机器一般走 SSH key，密码可以留空。
- 如果团队使用动态密码，运行时会弹窗要求输入，登录成功后会保持 session，直到关闭项目或配置变化。

## 打开远程编译

在 Jugg Run Configuration 中打开远程编译配置，然后选择同步模式。同步模式决定本地源码怎么上传到云开发机，以及远端构建产物怎么拉回本地。

## 选择同步模式

| 模式 | 适合场景 | 说明 |
|---|---|---|
| `rsync_simple` | 单工程开发，Mac 或 Linux 本地环境 | 基于 SSH 通道，只同步当前工程，配置最少，推荐优先使用 |
| `rsync` | 多工程需要一起同步，Mac 或 Linux 本地环境 | 基于 SSH 通道，支持多个工程共用远端源码根目录 |
| `iFT` | Windows 本地环境，或团队要求使用 iFT | 需要本机和服务器都安装 iFT，支持多工程同步 |

Windows 不支持 rsync，必须使用 iFT。Mac 或 Linux 优先用 `rsync_simple`；团队已经配置 iFT 时，也可以继续用 iFT。

## 多工程同步

如果工作区包含多个互相依赖的工程，可以开启多工程开发模式。开启后，每次编译前会同步 `Local to remote sync path` 下的所有工程。

如果只有一个工程，优先使用 `rsync_simple`，不需要开启多工程同步。

## 配置账号和主机

| 参数 | 填写方式 |
|---|---|
| `SSH user` | 登录账号，通常是 `root`；如果团队配置了个人账号，则填企业 ID |
| `SSH password/key(optional)` | 登录密码或 SSH key。已配置 SSH key 时可以留空，让 Jugg 自动搜索 `.ssh` 目录 |
| `SSH host` | 云开发机 IP 或域名 |
| `SSH port` | SSH 端口，腾讯云服务器常见端口为 `36000` |

密码留空且无法通过 key 登录时，Jugg 会在运行时弹窗要求输入。

## 配置同步目录

不同同步模式需要的目录略有差异。

### rsync_simple

| 参数 | 含义 |
|---|---|
| `Remote root directory (optional)` | 云开发机上的源码根目录，默认是 `$HOME/remote` |

如果远端已经有固定同步目录，并且不是默认路径，可以填写这个参数，避免重复同步。

### rsync

| 参数 | 含义 |
|---|---|
| `Local to remote sync path` | 本地源码根目录。多工程同步时，这里应是多个工程共同的父目录 |
| `Remote root directory (optional)` | 云开发机源码根目录，默认是 `$HOME/remote` |
| `Remote to local sync path` | 远端构建产物拉回本地后的根目录，可被多个工程共用 |

### iFT

先打开 iFT 设置页，输入 Pin+Token 后查看本机和云开发机的同步配置，再按顺序填写：

| 参数 | 含义 |
|---|---|
| `Local To remote IFT config name` | 本地源码同步到云开发机的 iFT 配置名 |
| `Local to remote sync path` | 本地源码根目录 |
| `Remote root directory (optional)` | 云开发机源码根目录，默认是 `$HOME/remote` |
| `Remote to local IFT config name` | 远端构建产物同步回本地的 iFT 配置名 |
| `Remote to local sync path` | 构建产物拉回本地后的根目录 |

## 配置代理

需要走 iOA 代理时填写：

| 参数 | 值 |
|---|---|
| `HTTP proxy host` | `127.0.0.1` |
| `HTTP proxy port` | `12639` |

不需要代理时留空。

## 常见问题

| 报错或现象 | 先检查什么 |
|---|---|
| `Run configuration argument xxxx is empty / is invalid` | 配置项漏填或填错，按本页重新检查 |
| `Login to remote ssh failed. Please check your login info.` | 账号、密码、IP、端口、代理或内网登录状态 |
| `Local to remote IFT sync path must be the parent of project path` | 当前工程不在本地同步根目录下面 |
| `Sync file from local to remote failed` | iFT 是否打开、密码是否正确、网络是否可用 |
| `Fetch output from remote to local failed` | iFT 客户端是否正常，远端产物是否生成 |
| `find apk name with pattern ... failed` | `Output APK name` 和 `Remote to local sync path` 是否匹配 |
| `set cmd failed: get volume failed: dir-config not found` | iFT 同步配置名是否填错 |
| `No space left on device` | 云开发机磁盘不足，需要清理或扩容 |

如果扩容后换了新磁盘路径，记得把 `Remote root directory` 改到新路径，否则仍然会同步到旧盘。

## 下一步

- [首次运行](./first-run.md)
- [远端 Gradle](../guide/remote-gradle.md)
- [日志文件](../reference/log-files.md)
