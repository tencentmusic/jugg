# 常见任务手册 (Cookbook)

> 文档版本: v1.0
> 更新时间: 2025-01-20
> 目的: 提供高频任务的步骤速查，附涉及类/文件路径，方便 AI/开发者快速落地。

---

## 1. 新增自定义编译器
- 文档参考: `02_compile_custom_ui.md`
- 关键类: `CustomCompilerManager`, `CompileUiHandler`
- 路径: `main/src/main/java/com/sickworm/intellij/jugg/compiler/custom`
- 示例/用法指向: `custom_compilers/src/main/java/.../SampleCompiler.kt`（最小实现）
- 步骤:
  1) 创建编译器实现，使用 `@AutoService(ICompilerCreator::class)` 注册。
  2) 在编译链配置中声明执行顺序与输入/输出。
  3) 如需 UI 配置，扩展 `CompileUiHandler`。

## 2. 调试增量资源编译
- 文档参考: `02_compile_resource.md`
- 关键类: `ResourceCompiler`, `Aapt2Invoker`, `Aapt2DaemonInvoker`
- 路径: `compiler/resource`, `aapt2/`
- 示例/用法指向: 查看 `Aapt2DaemonInvoker` 调用处的增量/全量切换逻辑（同文件）。
- 步骤:
  1) 确认 FileChangesDetector 过滤到资源文件。
  2) 检查 AAPT2 守护进程是否存活（日志）。
  3) 复现时可强制全量/增量开关，验证差异。

## 3. 无 IDE/CI 场景下编译与部署
- 文档参考: `04_engineering_compat.md`
- 关键类: `CmdLine`, `CmdExecutor`, `AsDeployerCompat`
- 路径: `cmd_line/`, `deploy_compat/`
- 示例/用法指向: `cmd_line/src/main/java/.../CmdLine.kt`（命令行入口解析）。
- 步骤:
  1) 使用命令行入口（见文档示例）触发编译/部署。
  2) 依赖解析走 GradleProjectInfoReader，确保 Gradle 可用。
  3) 部署阶段调用 AsDeployerCompat/设备接口。

## 4. 自定义 MCP 工具
- 文档参考: `05_utilities.md`
- 关键类: `McpToolRegistry`, `McpLocalServer`, `IdeMcpRuntime`
- 路径: `mcp/`, `idea/src/main/java/com/sickworm/intellij/jugg/server/`
- 示例/用法指向: `McpToolRegistry` 工具注册与 `IdeMcpRuntime` 实现（同目录文件）。
- 步骤:
  1) 在 `McpToolRegistry` 中新增工具定义与参数 schema。
  2) 在 `IMcpRuntime` / `IdeMcpRuntime` 中新增工具处理逻辑。
  3) 通过 `McpLocalServer` 校验客户端调用与协议版本。

## 5. 远程编译/服务端对接
- 文档参考: `05_utilities.md`
- 关键类: `JuggServer`
- 路径: `server/`
- 示例/用法指向: `server/src/main/java/.../JuggServer.kt`（端口/编译输出配置）。
- 步骤:
  1) 配置 RPC/网络端口与认证（若有）。
  2) 确认编译输出路径与部署端协议一致。
  3) 做压力/稳定性验证。

## 6. 部署失败排查
- 文档参考: `03_deploy_core.md`, `03_deploy_complete.md`
- 关键类: `JuggDeployer`, `DeployFileManager`, `IncrementalDeployHelper`
- 路径: `deploy/`
- 示例/用法指向: `IncrementalDeployHelper` 的步骤日志/降级逻辑（同目录文件）。
- 步骤:
  1) 查看 IDE/命令行日志，确定失败阶段（准备/推送/重定义）。
  2) 检查 JVMTI Agent 日志（`jvmti_agent/`），是否类重定义/资源覆盖失败。
  3) 如为 Overlay 问题，确认 Android 版本/权限；必要时回退到全量安装。

---

> 使用方式：遇到任务先查本手册 → 路径定位 → 再看对应深度文档 (`02/03/04/05` 等)。如有新高频任务，请补充条目并保持格式一致。
