# 运行时与 JVMTI 支持

> 最后核对：2026-05-23
> 一致性规则：文档与代码冲突时，以代码为准。

---

## 1. 文档定位

本页描述 Jugg JVMTI agent 在部署链路中的职责：如何准备 startup agent、如何判断设备是否可用 JVMTI、何时触发兼容部署重试。

本页不展开 Direct Overlay 的传输细节、ViewHierarchy LocalSocket 协议、完整 install/code swap 流程；对应入口见 `03_deploy_core.md`、`03_deploy_complete.md`、`08_mcp_layout_verify_design.md`。

---

## 2. 核心源码索引

| 类/文件 | 路径 | 作用 |
|---|---|---|
| `JuggJvmtiAgentManager` | `main/src/main/java/com/sickworm/intellij/jugg/deploy/JuggJvmtiAgentManager.kt` | 管理 Jugg agent bundle 的 push、app sandbox setup、attach 与清理 |
| `JuggJvmtiAgentManagerHelper` | `main/src/main/java/com/sickworm/intellij/jugg/deploy/JuggJvmtiAgentManagerHelper.kt` | 决定部署后是否需要补 push agent，读取 flag 文件判断 JVMTI 可用性 |
| `JuggDeployerHelper` | `idea/src/main/java/com/sickworm/intellij/jugg/deploy/run/JuggDeployerHelper.kt` | 在 deploy 前后串联 async agent 检查、push、restart、JVMTI compat 检测和 retry |
| `DeployRetryHandler` | `idea/src/main/java/com/sickworm/intellij/jugg/deploy/run/flow/DeployRetryHandler.kt` | deploy 失败后通过 run host 触发 JVMTI 检测，必要时切换 compat deploy |
| `AsStartupAgentPusher` | `idea/src/main/java/com/sickworm/intellij/jugg/deploy/direct/AsStartupAgentPusher.kt` | Direct Overlay 路径推 Android Studio Apply Changes startup agent，不依赖 app 进程在线 |
| `native-lib.cpp` | `jvmti_agent/src/main/cpp/native-lib.cpp` | `Agent_OnAttach` 入口，写 `.jugg_jvmti_available` / `.jugg_jvmti_not_available` flag，并启动 instrumentation |
| `instrumenter.cc` | `jvmti_agent/src/main/cpp/instrumenter.cc` | 加载 `jugg-instruments.jar`，设置 class file load hook 并 retransform 目标类 |
| `jugg_agent_setup.sh` | `jvmti_agent/src/main/script/jugg_agent_setup.sh` | 在 app `code_cache/startup_agents` 中放置版本化 agent so，并处理 HarmonyOS fix flag |
| `buildAgentBundle.gradle` | `jvmti_agent/buildAgentBundle.gradle` | 打包 `jugg-instruments.jar`、64/32 位 so 和 setup script，生成 plugin resource |

---

## 3. 核心状态模型

| 状态/文件 | 所在位置 | 语义 |
|---|---|---|
| Jugg agent bundle | `/data/local/tmp/jugg/{AGENT_VERSION}` | 设备全局临时目录，包含 `jugg-instruments.jar`、64/32 位 so、setup script |
| App startup agent | `{app}/code_cache/startup_agents/{version}-jugg_jvmti_agent(.so/_alt.so)` | app sandbox 内真正被系统加载的 startup agent |
| Apply Changes agent | `{app}/code_cache/startup_agents/{versionHash}-{dollName}` | Direct Overlay 复用的 AS startup agent；由 `AsStartupAgentPusher` 推送 |
| `.jugg_jvmti_available` | `{app}/code_cache/.jugg_jvmti_available` | native `Agent_OnAttach` 成功取得 JVMTI/JNI 后写入 |
| `.jugg_jvmti_not_available` | `{app}/code_cache/.jugg_jvmti_not_available` | native 无法取得 JVMTI/JNI 时写入，触发 compat device record |
| compat device record | `CompatDeployHelper` 管理 | 某 app/device 已知 JVMTI 不可用后，后续直接进入兼容部署 |

`JuggJvmtiAgentManagerHelper.isJvmtiAvailable()` 优先读 not-available flag，再读 available flag；两个 flag 都没有时返回 `null`，表示 app 启动或 agent 初始化状态尚不确定。

---

## 4. 核心调用链路

### 4.1 常规部署后的 Jugg agent 协同

```text
JuggDeployerHelper.runTask()
  -> 异步调用 JuggJvmtiAgentManagerHelper.isNeedPushAgentAfterDeploy()
     install 直接跳过；增量部署检查 app sandbox 中 Jugg agent 和 AS apply changes agent 是否齐备
  -> JuggDeployTask.run()
     先完成 install / apply changes / apply changes and restart activity
  -> detectJob.await() 后按需 JuggJvmtiAgentManagerHelper.pushAgentToApps()
     push bundle 到 /data/local/tmp/jugg/{AGENT_VERSION}，再 run-as app 执行 setup script
  -> 根据部署数据和用户设置决定 restart/start/no-op
  -> 若本轮 push 过 agent 且会 restart app，调用 isHasJvmtiCompatIssue()
     等待 native flag 文件，失败时记录 compat device 并抛出 redeploy-with-compat 信号
```

push agent 放在部署之后，是为了避免 Android Studio Apply Changes 首次部署清理 startup agents 后把 Jugg agent 删掉。JVMTI 检测必须等 restart 后进行，因为 startup agent 只有 app 进程启动时才会被系统加载。

### 4.2 失败重试中的兼容检测

```text
DeployRetryHandler.tryRetry()
  -> deployRunHost.detectJvmtiCompatIssue()
  -> JuggJvmtiAgentManagerHelper.isNeedPushAgentAfterDeploy()
     如缺 agent，先 push 再 restart app
  -> isHasJvmtiCompatIssue()
     命中 not-available flag 时记录 compat device，下一轮切 compat deploy
```

这条链路只判断“当前失败是否可能由 JVMTI 兼容性导致”。已经处于 compat deploy 的设备会跳过检测，避免重复记录和循环重试。

### 4.3 Native agent 启动

```text
系统加载 startup agent
  -> Agent_OnAttach(vm, options = app data dir)
  -> 尝试取得 JVMTI 和 JNI
     失败写 .jugg_jvmti_not_available
  -> 成功写 .jugg_jvmti_available
  -> HandleStartupAgent()
     AddCapabilities，加载 jugg-instruments.jar，instrument Application / AppComponentFactory / Resources
```

`options[0] == '/'` 才按 startup agent 处理；shell attach 场景不会进入完整 instrumentation。

---

## 5. 构建与版本约束

- native 目标库是 `jugg_jvmti_agent`，构建入口是 `jvmti_agent/CMakeLists.txt`。
- `jvmti_agent/buildAgentBundle.gradle` 生成 `BuildConfig.AGENT_VERSION`、`AGENT_BUNDLE_PATH`、flag 文件名，并把 agent bundle 放到 plugin resource 路径。
- 工程根 `build.gradle` 的 `agentVersion` 是设备目录、startup agent 文件名前缀和 bundle 文件名的共同版本源。
- 修改 `jvmti_agent` 里的 native、Java runtime、setup script 或 bundle 内容后，必须递增 `agentVersion`；否则设备端已有 `{AGENT_VERSION}` 目录会让 `isAgentBundlePushed()` 误认为无需更新。
- 32 位 app 使用 `_alt.so`：bundle 打包时把 armeabi-v7a so 改名为 `jugg_jvmti_agent_alt.so`，`attachAgentToApp()` / setup script 都依赖这个约定。

---

## 6. 隐形约束

- `pushAgentToApps()` 和 `attachAgentToApps()` 都受 `JuggSettings.finalIsEnableCompatibleDeploymentMode` 控制；功能关闭时不会做 agent 操作。
- install 没有增量部署文件，`isNeedPushAgentAfterDeploy()` 直接返回 false；不要用 install 后缺 agent 判断为 push 失败。
- `isNeedPushAfterDeploy()` 要同时看到 Jugg agent 和非 Jugg 的 `.so` startup agent；缺任意一类都会要求重新 push，因为 Apply Changes 首次写入 agent 时可能清空目录。
- `isHasJvmtiCompatIssue()` 最多等待 3 秒，每 100ms 轮询一次；返回 `null` 的 app 会继续等，全部 app 都非 null 才收口。
- not-available flag 优先级高于 available flag；排查时如果两个都存在，应先按不可用处理并清理 app `code_cache` 后复测。
- `AsStartupAgentPusher` 推 AS agent 的路径不要求 app 进程在线；它用 host matryoshka 解析出的 agent so，经 `run-as cp` 放进 app sandbox。
- `jugg_agent_setup.sh` 在 HarmonyOS 4.2 及以上会写 `code_cache/.need_fix_dex_path_list`，这是兼容修复信号，不是 JVMTI 可用性 flag。

---

## 7. 排查入口

| 现象 | 优先入口 |
|---|---|
| agent bundle 未更新 | 根 `build.gradle` 的 `agentVersion`，再查 `/data/local/tmp/jugg/{version}` 文件数 |
| app sandbox 中没有 Jugg agent | `JuggJvmtiAgentManager.pushAgentToApp()`、`setupAgent()`、`jugg_agent_setup.sh` |
| 32/64 位 so 选错 | `JuggJvmtiAgentManager.attachAgentToApp()` 的 `_alt.so` 判断和 `adb.getArch(packageName)` |
| 部署后被判 JVMTI 不可用 | `JuggJvmtiAgentManagerHelper.isHasJvmtiCompatIssue()`，检查 `.jugg_jvmti_not_available` |
| 检测一直不收口 | app 是否 restart、`code_cache` 是否存在、native `Agent_OnAttach` 是否写 flag |
| Direct Overlay 缺 AS startup agent | `AsStartupAgentPusher.hasApplyChangesStartupAgent()` 与 `pushApplyChangesStartupAgent()` |
| HarmonyOS 兼容异常 | `jugg_agent_setup.sh` 的 `.need_fix_dex_path_list` 逻辑 |

---

## 8. 关联文档

- 部署核心：`03_deploy_core.md`
- 完整流程：`03_deploy_complete.md`
- Direct Overlay 与兼容层入口：`04_engineering_compat.md`
- ViewHierarchy / MCP 布局验证：`08_mcp_layout_verify_design.md`
