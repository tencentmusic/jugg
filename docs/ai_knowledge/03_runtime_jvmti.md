# 运行时与 JVMTI 支持

> 最后核对：2026-02-23  
> 一致性规则：文档与代码冲突时，以代码为准。

---

## 1. 文档定位

本页描述 JVMTI 相关运行时能力与部署流程的衔接点。

---

## 2. 代码位置（已校正）

### 2.1 Native 侧

- `jvmti_agent/src/main/cpp/native-lib.cpp`
- `jvmti_agent/src/main/cpp/instrumenter.cc`
- `jvmti_agent/src/main/cpp/native_callbacks.cc`
- `jvmti_agent/src/main/cpp/dexer/*`
- `jvmti_agent/src/main/cpp/jni/*`

### 2.2 Java/Kotlin 协同侧

- `main/src/main/java/com/sickworm/intellij/jugg/deploy/JuggJvmtiAgentManager.kt`
- `main/src/main/java/com/sickworm/intellij/jugg/deploy/JuggJvmtiAgentManagerHelper.kt`
- `idea/src/main/java/com/sickworm/intellij/jugg/deploy/run/JuggDeployerHelper.kt`

---

## 3. 运行时协同流程

1. 部署阶段判断是否需要 push/attach agent。  
2. `JuggJvmtiAgentManager` 负责设备侧 agent 文件管理。  
3. 部署后通过兼容性探测确认 JVMTI 状态。  
4. 若检测异常，记录兼容设备并切换兼容部署路径。

---

## 4. 构建要点

- 构建脚本：`jvmti_agent/CMakeLists.txt`。  
- native 目标库：`jugg_jvmti_agent`。  
- agent 版本由 `BuildConfig.AGENT_VERSION` 与部署逻辑协同。
- 修改本模块任意文件，必须递增工程根目录 `build.gradle` 的 `agentVersion`，否则不会更新 agent

---

## 5. 常见问题定位

- “agent 未推送成功”：`JuggJvmtiAgentManager.pushAgentToApp`。  
- “部署后仍被判兼容问题”：`JuggJvmtiAgentManagerHelper.isHasJvmtiCompatIssue`。  
- “32/64 位不匹配”：检查 `attachAgentToApp` 的 `_alt.so` 选择逻辑。

---

## 6. 关联文档

- 部署核心：`03_deploy_core.md`
- 完整流程：`03_deploy_complete.md`
