# 系统应用部署约束

> 最后核对：2026-09-10
> 一致性规则：文档与代码冲突时，以代码为准。

## 1. 文档定位

本页只回答：

- Jugg 当前部署链路能不能把 App 变成系统应用 / 特权应用。
- 普通系统应用与特权应用在设备上的充分条件分别是什么。
- 首次落盘失败时，哪些现象能证明是安装路径问题，而不是 Jugg compile / Apply Changes 故障。

不展开 Jugg install / overlay 的一般机制，见 `03_deploy_core.md`。

Jugg **没有内置**把普通 APK 首次安装成系统应用的 installer、`priv-app` push 或权限白名单逻辑。Run Configuration 可启用 `Enable custom APK install script`，让项目自己的 Gradle task 或脚本接管普通 App 的 install/reinstall；系统分区写入、白名单、签名和重启仍完全由该脚本负责。已经安装的 debuggable 应用如果不满足 Android Studio Deployer 的 `run-as`、普通 UID 与 SELinux label 前提，但 shell、root adbd 或非交互 `su` 能完整访问其 data 目录，class、资源和 assets 增量部署可走 Jugg Direct transport。

## 2. 核心源码索引

| 类/接口 | 文件 | 作用 |
|---|---|---|
| `JuggDeployer.install()` | `idea/src/main/java/com/sickworm/intellij/jugg/deploy/run/applychanges/JuggDeployer.kt` | 统一安装入口。普通 App 可选择自定义脚本，否则调用 Android Studio deployer；成功后统一写 deployment cache 与 overlay id。 |
| `CustomApkInstallScriptRunner` | `idea/src/main/java/com/sickworm/intellij/jugg/deploy/run/applychanges/CustomApkInstallScriptRunner.kt` | 在本地工程根目录执行用户脚本，补齐 Android SDK platform-tools 路径，并在脚本后校验已安装 APK。 |
| `IAsDeployerCompat.install()` | `deploy_compat/*/AsDeployerCompat.kt` | 实际执行 AS install session。失败文案来自 PackageManager，不能据此推断“已经按系统应用安装”。 |
| `AppSandboxExecutor` | `main/src/main/java/com/sickworm/intellij/jugg/deploy/AppSandboxExecutor.kt` | app 私有目录统一入口。以唯一成功标记、UID 范围和探针 SELinux context 判断 Apply Changes 兼容性；不兼容时依次探测普通 shell、root adbd 和非交互 `su`，并固定本轮使用的真实 `dataDir` 与权限模式。 |
| `DirectAppSandboxDeployTransport` | `idea/src/main/java/com/sickworm/intellij/jugg/deploy/hotreload/DirectAppSandboxDeployTransport.kt` | 在 AS deployer 前接管 `run-as` 不兼容应用的 class、资源和 assets 增量部署：先写 Direct Overlay，纯方法体尝试在线 redefine；Android 11+ 的普通资源及混合变化刷新运行中 Resources，并按 deploy mode 重建 Activity，失败时请求重启应用。 |
| `DirectOverlayWriter` | `main/src/main/java/com/sickworm/intellij/jugg/deploy/direct/DirectOverlayWriter.kt` | 通过 `AppSandboxExecutor` 原子写 `code_cache/.overlay`，新 overlay id 最后提交。 |

## 3. 关键模型

### 3.1 三类系统应用

| 类型 | 落盘路径 | 设备可见结果 | 特权权限（如 `INSTALL_PACKAGES`） |
|---|---|---|---|
| 普通系统应用 | `/system/app/<Name>/<Name>.apk` | `FLAG_SYSTEM=true`，无 `PRIVILEGED` | 证书不匹配平台时 denied |
| 特权应用 | `/system/priv-app/<Name>/<Name>.apk` + `/system/etc/permissions/privapp-permissions-*.xml` | `FLAG_SYSTEM=true` 且 `privateFlags` 含 `PRIVILEGED` | `signature\|privileged` 可经 priv-app + 白名单 granted |
| sharedUserId 系统应用 | `/system/app/<Name>/<Name>.apk` 且 `android:sharedUserId="android.uid.system"` | 证书匹配时 UID 1000；无 `PRIVILEGED` | 凭 **平台签名** granted，不依赖 priv-app |

判定入口：

```text
dumpsys package <pkg>
  -> codePath=
  -> flags=[ SYSTEM ... ]
  -> privateFlags=[ ... PRIVILEGED ... ]
  -> android.permission.INSTALL_PACKAGES: granted=true   # priv-app 白名单或平台签名匹配时会出现
```

`FLAG_SYSTEM` 只证明扫描到了系统分区 APK，不证明特权权限。`sharedUserId="android.uid.system"` 只证明想加入 UID 1000；证书必须与设备 `android` 共享用户一致，否则包会被拒绝扫描，而不是装上后 UID 不是 1000。UID 1000 验证应使用 AOSP `default` 镜像；Google APIs 镜像上 AOSP test-keys 对不上。

签名一致的 `pm install -r` 成功后，`dumpsys package` 会同时出现 `/system/...` 基线和 `/data/app/...` 更新项，更新项带 `UPDATED_SYSTEM_APP`。此时有效 `codePath` 指向 `/data/app`，**仍是系统应用更新**，不是变回第三方应用；`FLAG_SYSTEM` / `PRIVILEGED` 应保留。

### 3.2 权限保护级与签名

- `signature`：必须与声明该权限的平台证书一致。
- `signature|privileged`：平台签名 **或** 特权应用（`priv-app` + Android 8+ 白名单）。
- 仅 platform 签名、仍用 `pm install` 装到 `/data/app`，得到的是第三方应用，不会出现 `FLAG_SYSTEM`。

白名单 XML 只作用于 `priv-app`。普通 `/system/app` 即使声明同一权限，也不会因为这份 XML 被授权。

## 4. 核心调用链路

默认 installer 场景下，系统应用要先“成为系统包”，Jugg 才能按普通包做后续更新或 overlay。启用自定义 APK 安装脚本后，首次系统化可以由项目脚本在 Jugg install 边界完成。

```text
首次成为系统/特权应用（由外部流程或 Jugg 调用的自定义脚本负责）
  -> 设备必须是 userdebug/eng，且模拟器以 -writable-system 冷启动
  -> adb root + adb remount 让 /system 可写
  -> 把 platform 签名后的 APK push 到 /system/app 或 /system/priv-app
  -> 特权应用额外 push privapp-permissions XML 到 /system/etc/permissions/
  -> restorecon 后 reboot，让 PackageManager 扫描系统分区
  -> dumpsys 确认 FLAG_SYSTEM / PRIVILEGED / 权限 granted

Jugg 部署
  -> JuggDeployerHelper.deploy
  -> JuggDeployer.install / codeSwap / fullSwap
  -> 启用自定义安装脚本且目标是普通 App APK: 项目脚本执行系统化或厂商安装流程
  -> 否则 AS deployer: pm install 到 /data/app
  -> 增量阶段: Direct Overlay / Apply Changes
  -> 系统应用结论仍必须由 dumpsys 的 codePath / flags / privateFlags 证明
```

未启用自定义脚本时，先 `Jugg deploy` / `adb install` 再期望它变成系统应用不会发生；系统化必须由外部流程先 push 进系统目录并重启。启用自定义脚本时，Jugg 只负责在 install/reinstall 边界调用脚本并验证结果，不替脚本决定系统目录、白名单或重启方式。

要**每次都改写 `/system` 基线**，不能用 `adb install` / IDE Run。正确循环是：卸掉 `/data/app` 更新层（`adb uninstall` 对系统应用只删更新）→ `remount` + `push` 覆盖 `/system/.../*.apk` → reboot 或 `stop`/`start` 让 PackageManager 重新扫描。中间如果点过 Run，不先 uninstall 就 push，运行中的仍是 `/data/app` 那份。

系统包已经存在后，Android 允许 `pm install` 作为更新并保留原 `FLAG_SYSTEM`。这条路径要求**新 APK 与 `/system` 里那份基线 APK 签名一致**。Android Studio / Jugg 默认 debug keystore 与 platform 签名不同，会得到 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`，看起来像“无法 update”。处理是让 debug/release 都使用首次 push 时的同一套 platform 密钥，而不是 uninstall 后改用 debug 包重装（系统分区 APK 卸不掉）。

Jugg 默认 install 走同一条 AS installer，签名对齐后**预期**可以更新已有系统包。启用自定义安装脚本后，Gradle install、APK 更新和 recover reinstall 都可重新执行项目脚本；脚本必须安装 Jugg 本轮提供的 APK，否则 checksum 校验失败。校验成功后，Jugg 会在 app sandbox 可用时清理旧 `code_cache/.overlay`，再记录新 base deployment cache，避免系统应用重装保留 app data 后反复出现 overlay state mismatch。class、资源和 assets 可进入 Direct app sandbox transport；Manifest/native library 继续由既有 APK 更新、重签和安装流程处理，随后重放 overlay。

### 4.1 自定义 APK 安装脚本契约

- UI 开关未启用时仅显示 switch，默认安装行为不变；启用后显示单行高度的输入面板和项目脚本示例占位提示。
- 脚本在本地工程根目录执行。macOS/Linux 使用 Bash shell，Windows 使用 `cmd.exe`；远程编译产物拉取完成后仍在本地主机执行。
- Jugg 不注入设备、applicationId 或 APK 路径变量。脚本继承 IDE/Gradle 环境，Android SDK 的 `platform-tools` 会加入 `PATH`；Bash 不加载用户 shell 启动文件。
- 普通 App APK 的 install/reinstall 按每台设备、每个 applicationId 执行脚本；androidTest APK 继续使用默认 installer。
- 脚本安装结果校验成功后，Jugg 会在 app sandbox 可用时清理旧 Direct Overlay；系统应用脚本不能假设 `adb uninstall` 会删除 app data。
- 脚本触发 reboot 时应自行等待设备启动和 PackageManager 扫描完成后再退出；Jugg 只复用现有短暂 ADB offline 恢复窗口。
- 退出码非零、用户取消、ADB 未恢复、包不存在或实际 APK checksum 不匹配时失败。脚本自身失败不可 deploy retry 或 Gradle fallback；脚本成功后的其它部署失败沿用原有 retry/fallback 策略，可能重新执行脚本，重复执行的处理由业务方负责。

### 4.2 run-as 不兼容应用增量链路

```text
run-as package 执行可回滚写入探测
  -> 唯一成功标记、UID 在 10000..19999，且探针与 code_cache 的 SELinux context 一致：保留 Android Studio Apply Changes
  -> 无成功标记、UID 越界或 SELinux context 不一致：进入 Direct app sandbox transport
      -> 解析 PackageManager 真实 dataDir
      -> 依次探测普通 shell、一次 adb root + 重连、非交互 su
      -> 固定并复用本轮 AppSandboxExecutor
      -> 安装现有 Jugg startup agent，并把 instrumentation JAR 复制为 app 可读文件
      -> 写 code_cache/.overlay
      -> 纯方法体变更：am attach-agent + RedefineClasses
          -> APPLY_CHANGES：成功后仅更新 class
          -> APPLY_CHANGES_AND_RESTART_ACTIVITY：成功后在主线程调度 Activity.recreate()，保留进程
          -> 失败：重启应用，由 startup agent 加载 overlay
      -> Android 11+ 普通资源/asset 或方法体与资源混合变化：刷新宿主 Resources，再重建 Activity
          -> 失败：重启应用，由 startup agent 加载已提交 overlay
      -> 新类、结构变化、APK 根目录资源或 Android 8～10 普通资源：重启并加载 overlay
      -> 兼容部署继续加载资源 APK
```

能力判断不依赖 system/privileged flag、`sharedUserId` 或具体 `run-as` 错误文本。ADB transport/offline 异常直接传播；Direct 权限不可用或 deployment cache 缺失时提前失败，不再进入必然失败的 Android Studio Deployer。Activity relaunch 也由 Direct JVMTI 请求独立完成，不重新调用 Android Studio `fullSwap/overlaySwap`，且不使用会杀进程的 `am start -S`。该路径只接管已经安装后的增量部署；首次系统化仍只能由外部流程或自定义 APK 安装脚本完成。Direct 与官方 Apply Changes 的剩余能力差异统一见 `03_deploy_core.md` §6.4。

## 5. 隐形约束

- Play Store 镜像不能作为系统应用试验场：通常不能 `adb root`，`/system` 只读。应使用 Google APIs 或 AOSP `userdebug` 镜像。
- 未加 `-writable-system` 时，`adb remount` 在 API 35 Google APIs 模拟器上会失败，文案可以是 `Device must be bootloader unlocked`。这不能证明镜像选错，只证明本次启动没有可写 system overlay。
- `disable-verity` 后 overlayfs remount 可能提示 `Now reboot your device for settings to take effect`。push 前若 `/system` 仍只读，必须先 reboot 再 `adb root && adb remount`，不能把 APK 推到会在重启时丢掉的临时挂载。
- Android Studio 自带 Google APIs 镜像的 platform 证书 **不是** 公开 AOSP test-keys。API 35 Google APIs 镜像（`android-35/google_apis/arm64-v8a`）上，`framework-res.apk` SHA-256 为 `301aa3cb081134501c45f1422abc66c24224fd5ded5fdc8f17e697176fd866aa`，AOSP `platform.x509.pem` 为 `c8a2e9bccf597c2fb6dc66bee293fc13f2fc47ec77bc6b2b0d52c11f51192ab8`。DN 都写成 Android/android.com，不能凭 DN 判断私钥匹配。
- 证书不匹配时，`android:sharedUserId="android.uid.system"` 会在扫描期被拒，表现为包根本没装上，而不是 UID 不是 1000。Google APIs 镜像上应先去掉 sharedUserId，用路径验证 `FLAG_SYSTEM` / `PRIVILEGED`。UID 1000 需要能拿到该镜像真正的 platform 私钥，或改用 AOSP `default` / 自编译 userdebug 镜像。
- AOSP platform test-key 是 MD5withRSA。Java 17 `jarsigner` / Gradle signing 可能拒签；应用 Android SDK `apksigner --key platform.pk8 --cert platform.x509.pem`。
- AGP debug 包默认 `android:testOnly="true"`，系统扫描后可能无法从启动器打开。首次落盘到系统分区应使用非 testOnly 的 release 包（可保持 `debuggable`）。
- 隐藏 API / `framework.jar` 只影响编译能否引用 `@hide` 接口，不能代替系统目录安装，也不是 `FLAG_PRIVILEGED` 的充分条件。
- Apply Changes 兼容性只由 `run-as` 可回滚探测的唯一成功标记、原始 UID `10000..19999`，以及探针与既有 `code_cache` 相同的 SELinux context 决定。仅 UID 相同不足以证明应用进程能读取 `run-as` 创建的文件。未兼容时 Direct transport 必须实际验证 data 目录写入、owner 修复、SELinux label 恢复和清理能力；普通文件继承既有 `code_cache` 的动态 MCS context，JVMTI `.so` 使用 appdomain 可执行的 `apk_data_file:s0`。不能用 root 输出文本、应用 flags 或错误字符串代替能力探测。
- 系统包已存在后，Android Studio Run / `adb install` / Jugg install 都是更新，不是首次安装。签名必须与 `/system` 内 APK 相同。`adb uninstall` 只能去掉 `/data` 里的更新，系统分区基线仍在；随后再用 debug 证书安装，照样会签名冲突。
- Google APIs 镜像上，按 `/system/app` / `/system/priv-app` 路径可以得到 `FLAG_SYSTEM` / `PRIVILEGED`，但证书不匹配平台时 `signature|privileged` 权限仍会 denied。AOSP `default` / `test-keys` 镜像上，平台签名匹配的 `/system/app` 也可获得 `INSTALL_PACKAGES`，不依赖 priv-app；`sharedUserId="android.uid.system"` 且证书匹配时进程 UID 为 1000。

## 6. 排查入口

| 观察结果 | 能证明 | 不能证明 | 下一项区分证据 |
|---|---|---|---|
| `adb install` / Jugg 默认 install 成功 | 包进入 `/data/app` | 它是系统应用 | `dumpsys package` 的 `codePath` 是否以 `/system/` 开头 |
| 自定义 APK 安装脚本成功 | 脚本退出为 0、包存在且 APK checksum 与输入一致 | 它是系统应用或特权应用 | `dumpsys package` 的 `codePath`、`flags`、`privateFlags` 与权限状态 |
| `FLAG_SYSTEM=true` 但特权权限 denied | 落在系统分区 | 它是特权应用 | `privateFlags` 是否含 `PRIVILEGED`，路径是否 `/system/priv-app` |
| `priv-app` 仍无 `INSTALL_PACKAGES` | 扫描到了 priv-app APK | 白名单已生效 | `/system/etc/permissions/` 是否有对应 package 的 XML；logcat `privapp-permissions` |
| `sharedUserId` 后包消失或扫描失败 | 签名与 `android.uid.system` 不一致，或解析失败 | Jugg compile 失败 | 对比 `framework-res.apk` 与 APK 的 cert SHA-256；去掉 sharedUserId 后能否被扫描 |
| `adb remount` 失败 / `/system` 只读 | 本次启动没有可写 system | Play 镜像或 root 方案整体不可用 | 是否 `-writable-system` 冷启动；`getprop ro.debuggable`；`pm path com.android.vending` |
| Direct Overlay / app sandbox 失败 | overlay transport 写不进该包数据目录 | 系统化没做成 | `dumpsys package` flags、`debuggable`、`run-as <pkg> id`、`adb shell id -u` |
| `INSTALL_FAILED_UPDATE_INCOMPATIBLE` / 无法 update | 设备上已有同名包，且签名与本次 APK 不同 | compile 失败或 `/system` 不可写 | 对比 `pm path` 指向 APK 与本地产物的 cert SHA-256；确认 Gradle `signingConfig` 不是 debug keystore |
| 更新成功但 `codePath` 变成 `/data/app` | 这是系统应用的 data 更新（应有 `UPDATED_SYSTEM_APP`） | 系统化丢失 | `flags` 是否仍含 `SYSTEM`；特权应用是否仍含 `PRIVILEGED` |

结论前反证：

- 领先结论如果是“Jugg 不会装系统应用”，反例是 `codePath` 已在 `/system/` 且本轮只是更新已有系统包。
- 领先结论如果是“已经是特权应用”，反例是 `privateFlags` 无 `PRIVILEGED`，或特权权限没有 `granted=true`。
- 缺少 `dumpsys package` 时，只报告安装命令成功，不要升级成系统应用结论。

## 7. 关联文档

- 部署 install / overlay：`03_deploy_core.md`
- Run 到部署完成：`03_deploy_complete.md`
- 运行时排查入口：`09_plugin_runtime_debug.md`
