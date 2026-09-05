# 系统应用部署约束

> 最后核对：2026-09-05
> 一致性规则：文档与代码冲突时，以代码为准。

## 1. 文档定位

本页只回答：

- Jugg 当前部署链路能不能把 App 变成系统应用 / 特权应用。
- 普通系统应用与特权应用在设备上的充分条件分别是什么。
- 首次落盘失败时，哪些现象能证明是安装路径问题，而不是 Jugg compile / Apply Changes 故障。

不展开 Jugg install / overlay 的一般机制，见 `03_deploy_core.md`。不把本机 demo 工程当成 Jugg 仓库内模块。

当前代码**没有**系统应用专用 installer、`priv-app` push 或权限白名单写入。本专题记录的是部署边界和设备侧约束，不是已实现能力。

## 2. 核心源码索引

| 类/接口 | 文件 | 作用 |
|---|---|---|
| `JuggDeployer.install()` | `idea/src/main/java/com/sickworm/intellij/jugg/deploy/run/applychanges/JuggDeployer.kt` | 现有安装入口。调用 Android Studio deployer，语义等于 `pm install` 到 `/data/app`，不选择 `/system/app` 或 `/system/priv-app`。 |
| `IAsDeployerCompat.install()` | `deploy_compat/*/AsDeployerCompat.kt` | 实际执行 AS install session。失败文案来自 PackageManager，不能据此推断“已经按系统应用安装”。 |
| `DirectOverlayWriter` | `main/src/main/java/com/sickworm/intellij/jugg/deploy/direct/DirectOverlayWriter.kt` | 增量 overlay 通过 `run-as <package>` 写 `code_cache/.overlay`。系统应用即使已在 `/system`，未 `debuggable` 或 `run-as` 失败时这条路径不可用。 |
| 本机验证工程 | `~/IdeaProjects/demo/SystemAppDemo` | 2026-09-05 用 Google APIs API 35 模拟器验证过路径、flag 和特权权限；不在 Jugg git 树内。 |

## 3. 关键模型

### 3.1 两类系统应用

| 类型 | 落盘路径 | 设备可见结果 | 特权权限（如 `INSTALL_PACKAGES`） |
|---|---|---|---|
| 普通系统应用 | `/system/app/<Name>/<Name>.apk` | `FLAG_SYSTEM=true`，无 `PRIVILEGED` | 即使 Manifest 声明了也会 denied |
| 特权应用 | `/system/priv-app/<Name>/<Name>.apk` + `/system/etc/permissions/privapp-permissions-*.xml` | `FLAG_SYSTEM=true` 且 `privateFlags` 含 `PRIVILEGED` | `signature\|privileged` 可 granted |

判定入口：

```text
dumpsys package <pkg>
  -> codePath=
  -> flags=[ SYSTEM ... ]
  -> privateFlags=[ ... PRIVILEGED ... ]
  -> android.permission.INSTALL_PACKAGES: granted=true   # 只有特权应用会出现
```

`FLAG_SYSTEM` 只证明扫描到了系统分区 APK，不证明特权权限。`sharedUserId="android.uid.system"` 只证明想加入 UID 1000，不证明安装路径正确。

签名一致的 `pm install -r` 成功后，`dumpsys package` 会同时出现 `/system/...` 基线和 `/data/app/...` 更新项，更新项带 `UPDATED_SYSTEM_APP`。此时有效 `codePath` 指向 `/data/app`，**仍是系统应用更新**，不是变回第三方应用；`FLAG_SYSTEM` / `PRIVILEGED` 应保留。

### 3.2 权限保护级与签名

- `signature`：必须与声明该权限的平台证书一致。
- `signature|privileged`：平台签名 **或** 特权应用（`priv-app` + Android 8+ 白名单）。
- 仅 platform 签名、仍用 `pm install` 装到 `/data/app`，得到的是第三方应用，不会出现 `FLAG_SYSTEM`。

白名单 XML 只作用于 `priv-app`。普通 `/system/app` 即使声明同一权限，也不会因为这份 XML 被授权。

## 4. 核心调用链路

系统应用要先“成为系统包”，Jugg 才能按普通包做后续更新或 overlay。顺序不能反。

```text
首次成为系统/特权应用（Jugg 不负责）
  -> 设备必须是 userdebug/eng，且模拟器以 -writable-system 冷启动
  -> adb root + adb remount 让 /system 可写
  -> 把 platform 签名后的 APK push 到 /system/app 或 /system/priv-app
  -> 特权应用额外 push privapp-permissions XML 到 /system/etc/permissions/
  -> restorecon 后 reboot，让 PackageManager 扫描系统分区
  -> dumpsys 确认 FLAG_SYSTEM / PRIVILEGED / 权限 granted

Jugg 现有部署（成为系统包之后才可能接上）
  -> JuggDeployerHelper.deploy
  -> JuggDeployer.install / codeSwap / fullSwap
  -> AS deployer: pm install 到 /data/app，或 Direct Overlay: run-as 写 overlay
  -> 成功只证明“这个包名可以被 installer/overlay 更新”，不能证明首次系统化完成
```

不能乱序的点：先 `Jugg deploy` / `adb install` 再期望它变成系统应用，不会发生。系统化必须先 push 进系统目录并重启。

系统包已经存在后，Android 允许 `pm install` 作为更新并保留原 `FLAG_SYSTEM`。这条路径要求**新 APK 与 `/system` 里那份基线 APK 签名一致**。Android Studio / Jugg 默认 debug keystore 与 platform 签名不同，会得到 `INSTALL_FAILED_UPDATE_INCOMPATIBLE`，看起来像“无法 update”。处理是让 debug/release 都使用首次 push 时的同一套 platform 密钥，而不是 uninstall 后改用 debug 包重装（系统分区 APK 卸不掉）。

Jugg install 走同一条 AS installer，签名对齐后**预期**可以更新已有系统包；本专题没有把 Jugg Run 在系统应用上的增量/overlay 作为已验证事实。

## 5. 隐形约束

- Play Store 镜像不能作为系统应用试验场：通常不能 `adb root`，`/system` 只读。应使用 Google APIs 或 AOSP `userdebug` 镜像。
- 未加 `-writable-system` 时，`adb remount` 在 API 35 Google APIs 模拟器上会失败，文案可以是 `Device must be bootloader unlocked`。这不能证明镜像选错，只证明本次启动没有可写 system overlay。
- `disable-verity` 后 overlayfs remount 可能提示 `Now reboot your device for settings to take effect`。push 前若 `/system` 仍只读，必须先 reboot 再 `adb root && adb remount`，不能把 APK 推到会在重启时丢掉的临时挂载。
- Android Studio 自带 Google APIs 镜像的 platform 证书 **不是** 公开 AOSP test-keys。2026-09-05 在 `SystemApp_API35`（`android-35/google_apis/arm64-v8a`）上，`framework-res.apk` SHA-256 为 `301aa3cb081134501c45f1422abc66c24224fd5ded5fdc8f17e697176fd866aa`，AOSP `platform.x509.pem` 为 `c8a2e9bccf597c2fb6dc66bee293fc13f2fc47ec77bc6b2b0d52c11f51192ab8`。DN 都写成 Android/android.com，不能凭 DN 判断私钥匹配。
- 证书不匹配时，`android:sharedUserId="android.uid.system"` 会在扫描期被拒，表现为包根本没装上，而不是 UID 不是 1000。Google APIs 镜像上应先去掉 sharedUserId，用路径验证 `FLAG_SYSTEM` / `PRIVILEGED`。UID 1000 需要能拿到该镜像真正的 platform 私钥，或改用 AOSP `default` / 自编译 userdebug 镜像。
- AOSP platform test-key 是 MD5withRSA。Java 17 `jarsigner` / Gradle signing 可能拒签；应用 Android SDK `apksigner --key platform.pk8 --cert platform.x509.pem`。
- AGP debug 包默认 `android:testOnly="true"`，系统扫描后可能无法从启动器打开。系统应用 demo 应使用非 testOnly 的 release 包（可保持 `debuggable`）。
- 隐藏 API / `framework.jar` 只影响编译能否引用 `@hide` 接口，不能代替系统目录安装，也不是 `FLAG_PRIVILEGED` 的充分条件。
- Direct Overlay 依赖 `run-as`。系统应用若不可调试，或将来使用 `android.uid.system`，不能默认 overlay 可用；失败时应先看 `run-as` 输出，而不是改 compile。
- 系统包已存在后，Android Studio Run / `adb install` / Jugg install 都是更新，不是首次安装。签名必须与 `/system` 内 APK 相同。`adb uninstall` 只能去掉 `/data` 里的更新，系统分区基线仍在；随后再用 debug 证书安装，照样会签名冲突。

本机一次通过的对照（2026-09-05，`SystemApp_API35` + `~/IdeaProjects/demo/SystemAppDemo`）：

| 包名 | codePath | SYSTEM | PRIVILEGED | `INSTALL_PACKAGES` |
|---|---|---|---|---|
| `com.jugg.demo.systemapp` | `/system/app/SystemAppDemo` | true | false | denied |
| `com.jugg.demo.privapp` | `/system/priv-app/PrivAppDemo` | true | true | granted |

两个包 Manifest 声明了同一组特权权限；差异只来自路径和白名单。

## 6. 排查入口

| 观察结果 | 能证明 | 不能证明 | 下一项区分证据 |
|---|---|---|---|
| `adb install` / Jugg install 成功 | 包进入 `/data/app` | 它是系统应用 | `dumpsys package` 的 `codePath` 是否以 `/system/` 开头 |
| `FLAG_SYSTEM=true` 但特权权限 denied | 落在系统分区 | 它是特权应用 | `privateFlags` 是否含 `PRIVILEGED`，路径是否 `/system/priv-app` |
| `priv-app` 仍无 `INSTALL_PACKAGES` | 扫描到了 priv-app APK | 白名单已生效 | `/system/etc/permissions/` 是否有对应 package 的 XML；logcat `privapp-permissions` |
| `sharedUserId` 后包消失或扫描失败 | 签名与 `android.uid.system` 不一致，或解析失败 | Jugg compile 失败 | 对比 `framework-res.apk` 与 APK 的 cert SHA-256；去掉 sharedUserId 后能否被扫描 |
| `adb remount` 失败 / `/system` 只读 | 本次启动没有可写 system | Play 镜像或 root 方案整体不可用 | 是否 `-writable-system` 冷启动；`getprop ro.debuggable`；`pm path com.android.vending` |
| Direct Overlay / `run-as` 失败 | overlay transport 写不进该包数据目录 | 系统化没做成 | `dumpsys package` flags、`debuggable`、`run-as <pkg> id` |
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
