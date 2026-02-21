# 运行时与 JVMTI 支持

---

## 一、模块概述

- 目录: `jvmti_agent/`
- 角色: 运行时热修复支持（类重定义、资源替换），与 `main/deploy` 的 `JuggDeployer` 协作。

### 关联文档
- 部署核心: `03_deploy_core.md`
- 完整部署: `03_deploy_complete.md`

---

## 二、核心文件

| 文件 | 说明 |
|------|------|
| `agent.cpp` | JVMTI Agent 入口，注册回调、处理类重定义 |
| `overlay/*.cpp` | Overlay/资源相关处理（如资源路径、映射） |
| `CMakeLists.txt` | 构建配置 |

> 实际文件名/子目录请以仓库为准，新增文件后需同步更新此表。

---

## 三、与部署流程的衔接

1. 编译产物准备：`DeployFileManager` 生成 DEX/资源差分。
2. 部署请求：`JuggDeployer` 将产物下发设备，触发 JVMTI 热更新。
3. 类重定义：JVMTI Agent 接收新字节码，调用 `RedefineClasses`；必要时处理资源 Overlay。
4. 状态反馈：将成功/失败状态反馈给上层（IDE/命令行）。

---

## 四、构建与调试

- 构建方式：通常通过 CMake/NDK 构建，具体命令参考项目根脚本或 `build.gradle` 的 native 部分。
- Android 版本要求：JVMTI 支持需 Android 8.0+。
- 调试建议：
  - 开启 JVMTI 日志（如存在日志开关，确保 logger 定向到设备日志）。
  - 使用 `adb logcat` 观察部署阶段日志。
  - 如类重定义失败，检查签名/类加载器隔离问题。

---

## 五、扩展与注意事项

- 如需扩展自定义资源覆盖策略，请在 `overlay` 相关文件中扩展，并同步更新文档。
- 对于多架构支持 (arm64/armeabi-v7a/x86_64)，请确认 CMake 配置的 ABI 列表。
- 保持与 `deploy` 模块接口一致：若协议/数据格式变动，需同时更新 `JuggDeployer` 与此文档。
