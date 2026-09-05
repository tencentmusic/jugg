# Standalone Deployer 资源目录收敛方案

## 背景

Standalone Deployer 当前按 Jugg 版本解压到 `~/.jugg/runtime/<runtimeVersion>/deployer/quail/`。历史版本目录不会自动清理，而每份资源包含四个 ABI 的可执行文件，导致用户目录持续增长。

Jugg 已有统一的用户级资源目录 `~/.jugg/resources/`，AAPT2 和 JVMTI Agent 等内置资源都通过该目录管理。Standalone Deployer 也属于随 Jugg 发布的内置资源，不需要独占 `runtime` 顶层目录。

## 目标

- Standalone Deployer 不再区分 Jugg 版本，只保留一份资源。
- 资源固定存放在 `~/.jugg/resources/deployer/quail/`。
- 每次准备资源时都使用临时文件加原子替换刷新目标文件，避免进程读取到半写入内容。
- 新版 tooling 安装完成并停止旧 daemon 后，删除历史 `~/.jugg/runtime/` 目录。
- 保持 AAPT2 现有解压路径和替换策略不变。

## 兼容性约束

单份覆盖要求后续版本严格向后兼容。当前资源契约固定为：

- metadata `schemaVersion` 为 `1`。
- deployer protocol version 与 `Version.hash()` 一致，当前值为 `c52d6b25`。
- 保留 `arm64-v8a`、`armeabi-v7a`、`x86`、`x86_64` 四个 ABI 路径。
- 后续只能增加旧版本可忽略的可选内容，不得改变已有文件语义、协议或目录结构。
- 无法向后兼容的升级不能复用单份覆盖通道，必须设计新的迁移边界，并通过 tooling 完整安装或 Bundle 重装交付。

## 实现

1. `JuggResourceManager` 根据 classpath resource root 直接映射到 `~/.jugg/resources/<resourceRoot>/`，不再接受任意目标相对路径。
2. 文件准备逻辑始终复制到同目录临时文件，设置可执行权限后再原子替换目标文件；文件系统不支持原子移动时沿用替换移动降级。共享资源根和目标父目录拒绝符号链接，resource root 拒绝绝对路径与 `.` / `..` 路径段，末端文件链接由原子移动替换为真实文件，避免逃逸或不同 ABI 路径互相指向。
3. Standalone Deployer 调用方移除 runtime version 参数，统一准备 `deployer/quail`。
4. `StandaloneRuntimeInstaller` 在发布新版 active manifest、停止旧 daemon 后，重新取得全局锁并删除 `~/.jugg/runtime/`。删除使用不跟随符号链接的目录遍历，且不影响 `~/.jugg/resources/`。

## 验证

- `JuggResourceManagerTest`：验证资源落在统一目录、已有内容会被覆盖、可执行权限保持，以及路径穿越、资源根/父目录/末端文件符号链接边界。
- `StandaloneDeployerResourceTest`：验证目录、固定协议版本和四个 ABI 契约。
- `StandaloneRuntimeInstallerTest`：验证完整安装后删除旧 runtime，同时保留 resources。
- 执行相关定向测试、Kotlin 编译、Wiki 校验与 Wiki production build。

## 非目标

- 不修改 AAPT2 的版本化文件名、加载流程或 Windows 替换行为。
- 不合并或清理 `~/.jugg/resources/deploy/` 下的 JVMTI Agent 资源。
- 不为资源清理增加后台定时任务、保留数量配置或通用缓存框架。
