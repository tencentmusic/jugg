# 工程化：项目模型与 Gradle 集成

> 最后核对：2026-02-23  
> 一致性规则：文档与代码冲突时，以代码为准。

---

## 1. 文档定位

本页关注“项目信息从哪里来、如何用于编译/依赖判断”。

---

## 2. 关键类

| 类 | 文件 | 作用 |
|----|------|------|
| `JuggProjectInfo` / `ModuleInfo` | `main/.../project/data/JuggProjectInfo.kt` | 项目与模块快照模型 |
| `ModuleBuildPathInfo` | `main/.../project/data/JuggProjectInfo.kt` | AGP 版本兼容路径推断 |
| `GradleProjectInfoReader` | `main/.../gradle/script/GradleProjectInfoReader.kt` | 通过 Gradle 反射读取模块信息 |
| `GradleProjectInfoReaderManager` | `main/.../gradle/script/GradleProjectInfoReaderManager.kt` | 读取流程与缓存管理 |
| `LocalGradleCompileClient` | `main/.../gradle/compile/LocalGradleCompileClient.kt` | 本地 Gradle 构建执行与 APK 搜索 |
| `RemoteGradleCompileClient` | `main/.../gradle/compile/RemoteGradleCompileClient.kt` | 远端 Gradle 编译链 |
| `ProjectInfoSerializer` | `main/.../project/ProjectInfoSerializer.kt` | 项目信息序列化 |

---

## 3. 项目信息读取流程

1. Gradle 侧执行读取逻辑，生成模块信息快照。  
2. `ModuleBuildPathInfo` 提供多 AGP 路径兼容访问。  
3. 编译/部署侧读取该快照用于 classpath、manifest、依赖解析。  
4. 同步后可触发差异对比与上下文重建。

---

## 4. Gradle 编译客户端职责边界

- `LocalGradleCompileClient`：本机执行命令、收集输出 APK、同步 classpath。  
- `RemoteGradleCompileClient`：远程执行构建、拉取结果与日志。  
- `CmdExecutor`：统一命令执行与输出监听。

---

## 5. 常见问题定位

- “模块路径识别异常”：看 `ModuleBuildPathInfo` 的路径推断。  
- “找不到 APK 输出”：看 `LocalGradleCompileClient.findApk`。  
- “依赖变化未感知”：看 `GradleDependencyDiffer` 与 dependency manager。

---

## 6. 关联文档

- 编译核心：`02_compile_core.md`
- IDE 编排：`04_engineering_ide.md`
- 兼容层：`04_engineering_compat.md`
