---
title: 自定义 APK 安装脚本
description: 说明如何让 Jugg 在普通 App 的安装和重装阶段调用项目脚本，以支持系统应用、厂商任务或其它非标准安装流程。
status: active
tags:
  - capability
  - deploy
  - install
---

# 自定义 APK 安装脚本

系统应用、厂商工程或定制设备可能不能通过 Android Studio 默认 installer 安装。Jugg 可以在需要安装普通 App APK 时执行项目提供的脚本，同时保留后续增量部署需要的 deployment cache 和 overlay checkpoint。

## 启用方式

打开 Jugg Run Configuration，勾选 `Enable custom APK install script`。勾选后，下方会出现单行高度的脚本输入框；未勾选时显示开关及其右侧帮助按钮，现有安装行为不变。

脚本在本地工程根目录执行。macOS/Linux 使用 Bash，Windows 使用 `cmd.exe`。即使启用了远程编译，脚本也会在连接 Android 设备的本地 IDE 主机执行。

macOS/Linux 中，配置文本包含 `$1` 或 `$2` 时，Jugg 设置下面的位置参数并执行命令，不在末尾追加；没有引用时，自动在末尾追加 APK 路径和设备 serial，脚本只需填写路径：

| 参数 | 内容 |
|---|---|
| `$1` | 当前 APK 的绝对路径，每次只传一个 APK |
| `$2` | 当前设备的 serial；多设备逐台调用 |

可以直接填写安装命令，无需新增脚本文件：

```bash
adb -s "$2" install -r -d "$1"
```

调用项目脚本时只填写脚本路径，无需手动追加参数：

```bash
./scripts/install-system-app.sh
```

多条命令可以使用 `&&` 连接：

```bash
adb -s "$2" install -r -d "$1" && echo "Installed"
```

Jugg 会安全转义追加的两个参数，保留空格、引号、Unicode 和路径中的 `;`。直接命令中的 `$1`、`$2` 需使用双引号。检测只识别完整字面 `$1/$2`，不会把 `$10` 当作 `$1`，也不解析引号、转义或注释。此前 `bash -c '… "$1" "$2" …' --` 的嵌套写法不再自动获得参数，请改成上面的直接命令。脚本有固定选项时，这两个参数会追加在选项之后。

Windows 使用 `cmd.exe`，不提供 Bash `$1/$2`。配置项目脚本路径即可，Jugg 仍在命令末尾追加当前 APK 绝对路径和 serial。Windows 参数包含 `%` 或 `!` 时在执行前报错，避免环境变量展开。

install/sign 开关文字右侧始终显示圆形 `?`，提示浮窗沿用 IDE 默认字体、颜色和宽度，展示参数说明、直接命令及脚本调用示例；sign 按是否引用 `$1` 选择位置参数或末尾追加。

Jugg 不额外注入设备、applicationId 或 APK 路径环境变量。脚本继承 Android Studio 进程环境以及 Jugg 已解析的 Gradle JDK、Android SDK 环境；当前 Android SDK 的 `platform-tools` 会加入 `PATH`，因此脚本可以直接调用 `adb`。Bash 不加载 `.zshrc`、`.bashrc` 等用户 shell 启动文件，项目需要的其它变量应由 Android Studio 的启动环境或脚本自身提供。

## 生效范围

自定义脚本会接管普通 App APK 的 install/reinstall，包括：

- Gradle 编译完成后的安装。
- Clean Reinstall 和 recover 触发的重装。
- Manifest、native library 等 APK 更新后的安装。
- Embedded APK 安装。

每台设备上的每个普通 App APK 分别执行一次命令。同一 applicationId 的全部 APK 命令成功后，Jugg 再统一校验并更新 deployment cache；任意一次失败都会停止后续调用。split APK 需要脚本管理安装会话，逐次 `adb install` 不能代替 split 安装。

纯 class 或 overlay 增量部署不会执行脚本。androidTest APK 也继续使用 Android Studio 默认 installer，避免系统应用脚本误处理测试包。

## 成功与失败判定

脚本必须安装 Jugg 本轮提供的 APK，并以退出码 `0` 结束。如果脚本触发 reboot，应自行等待设备启动和 PackageManager 完成包扫描后再退出。Jugg 随后会处理短暂的 ADB transport 恢复，确认目标包已经安装，并校验设备上的 APK 与本轮输入 APK 的 checksum；全部通过后才写入新的 deployment cache 和 overlay checkpoint。

以下情况会让本轮部署失败：

- 脚本退出码非零。
- 用户取消 Run，脚本进程被终止。
- 脚本执行后设备没有恢复连接。
- 目标 applicationId 未安装。
- 脚本安装了不同 APK，导致 checksum 不匹配。

脚本执行或安装结果校验失败时，Jugg 不会通过 deploy retry 或 Gradle fallback 自动重跑脚本。脚本安装成功后，其它部署步骤失败仍按原有规则重试或回退。例如，后续 androidTest APK 安装报 `INSTALL_FAILED_INVALID_APK` 时，卸载重试会再次执行普通 App 的安装脚本。默认 installer 自身的短暂 ADB transport 恢复仍然保留。脚本重复执行时的安装逻辑、副作用和错误处理由业务方负责。

## 系统应用边界

Jugg 只负责调用脚本和校验安装结果，不内置以下行为：

- `adb root`、`adb remount` 或 system 分区写入。
- `/system/app`、`/system/priv-app` 路径选择。
- shared UID 或特权权限白名单。
- reboot、zygote restart 或厂商刷机流程。

安装脚本始终只负责安装动作。Gradle 完整构建产出的 APK 由 Gradle 自己签名；Jugg 增量改写 APK 后的签名步骤由 [自定义 APK 签名脚本](./custom-apk-sign-script.md) 独立接管，两者互不替代。

脚本执行成功也不能单独证明 App 已成为系统应用。仍应通过 `dumpsys package <applicationId>` 检查 `codePath`、`SYSTEM`、`PRIVILEGED` 和权限授予状态。

## 相关页面

- [Clean Reinstall](./clean-reinstall.md)
- [自定义 APK 签名脚本](./custom-apk-sign-script.md)
- [APK 更新与安装](../../concepts/apk-update-and-install.md)
- [多 APK](./multi-apk.md)
- [多设备](./multi-device.md)
- [部署历史与缓存](./deploy-history-cache.md)
- [无法安装、部署、启动或 Debug](../../troubleshooting/app-cannot-run.md)
