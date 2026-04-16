# MCP 测试失败修复方案

## 1. 背景

基于 `docs/task/mcp_test_case.md` 的 64 条测试用例，执行结果见 `jugg-mcp-test-result.md`。其中 12 条 FAIL、4 条 BLOCKED、9 条 SKIP。本文档针对 FAIL 和 BLOCKED 用例，按优先级给出代码级修复方案。

### 统计

| 优先级 | 问题数 | 影响 TC |
|--------|--------|---------|
| P0 | 2 | TC-31, TC-32, TC-33, TC-34 |
| P1 | 3 | TC-10~12, TC-28, TC-44~46, TC-63~64 |
| P2 | 5 | TC-22~24, TC-39, TC-47~54, TC-55, TC-60 |

---

## 2. P0 - 严重问题

### 2.1 编译失败时 status 返回 OK 且缺少错误详情

**影响 TC**: TC-31, TC-32, TC-33, TC-34, TC-37

**现象**: 所有编译类工具（`compile` / `deploy` / `gradle-build`）在编译失败时：
1. 外层 `status` 返回 `"OK"` 而非 `"ERROR"`
2. 不返回出错文件名、行号、错误描述

**根因分析**:

`CompileAndDeployMcpToolAction.deployAction()` 中控制流如下：

```
triggerJuggCompile()
  └─ runFirstConfiguration(isRpcMode=true)
       └─ JuggRunInvocationResult(isSuccess, runResult, detail, errorMessage)
```

当 `runResponse.isSuccess == true`（调用过程成功）但 `runResult.isCompileSuccess == false`（编译结果失败）时，代码走入 `buildRunToolResult()` 分支。该方法中 message 设为 `"compile_only finished with status=failed."`，但 **status 始终为 `McpToolStatus.OK`**（`CompileAndDeployMcpToolAction.kt:220`）。

对于 `ForceGradleCompileMcpToolAction`，`forceGradleCompileAction()` 中无论 `trigger.status` 是什么值，都统一返回 `McpToolStatus.OK`（`ForceGradleCompileMcpToolAction.kt:53`）。

错误详情缺失的原因：`JuggRunInvocationResult.detail` 字段在编译失败时可能为空字符串，未被填充编译器的诊断输出。

**涉及文件**:

| 文件 | 修改点 |
|------|--------|
| `mcp/actions/CompileAndDeployMcpToolAction.kt` | `buildRunToolResult()` 根据 status 区分 OK/ERROR |
| `mcp/actions/ForceGradleCompileMcpToolAction.kt` | `forceGradleCompileAction()` 根据 trigger.status 区分 OK/ERROR |
| `IJuggConfigurationRunner` 实现类 | 确保编译失败时 `detail` 填充编译错误输出 |

**修复方案 A**: 修改 `CompileAndDeployMcpToolAction.buildRunToolResult()`

```kotlin
// CompileAndDeployMcpToolAction.kt - buildRunToolResult()
private fun buildRunToolResult(
    toolName: String,
    successMessage: String,
    runResultObject: JsonObject?,
    detail: String,
    extraData: Map<String, Any>,
): McpToolResult {
    if (runResultObject == null) {
        // ... null 处理保持不变 ...
    }

    val jobStatus = extraData["status"] as? String
    val isRealSuccess = (jobStatus == "success")

    // 编译失败：返回 ERROR 并附带详情
    if (!isRealSuccess) {
        val detailResult = resolveDetailResult(toolName, detail)
        val data = mutableMapOf<String, Any>("runResult" to runResultObject)
        attachDetailData(data, detailResult)
        data.putAll(extraData)
        val message = if (detailResult.hasDetail) {
            "$toolName finished with status=$jobStatus. See data.detail for error info."
        } else {
            "$toolName finished with status=$jobStatus."
        }
        return McpToolResult(
            status = McpToolStatus.ERROR,
            message = message,
            data = data,
            artifacts = detailResult.artifacts,
            errorCode = McpErrorCode.INTERNAL_ERROR,
        )
    }

    // 编译成功：保持原逻辑
    val data = mutableMapOf<String, Any>("runResult" to runResultObject)
    data.putAll(extraData)
    return McpToolResult(
        status = McpToolStatus.OK,
        message = successMessage,
        data = data,
        artifacts = emptyList(),
        errorCode = null,
    )
}
```

**修复方案 B**: 修改 `ForceGradleCompileMcpToolAction.forceGradleCompileAction()`

```kotlin
// ForceGradleCompileMcpToolAction.kt - forceGradleCompileAction()
private fun forceGradleCompileAction(runtime: IMcpRuntime): McpToolResult {
    return try {
        val trigger = CompileJobManager.triggerForceGradleCompile(runtime)
        val isFinalSuccess = trigger.isFinal && trigger.status == "success"
        val isStillRunning = !trigger.isFinal
        val status = if (isFinalSuccess || isStillRunning) McpToolStatus.OK else McpToolStatus.ERROR
        val errorCode = if (status == McpToolStatus.ERROR) McpErrorCode.INTERNAL_ERROR else null

        McpToolResult(
            status = status,
            message = trigger.message,
            data = mapOf(
                "accepted" to trigger.accepted,
                "jobId" to trigger.jobId,
                "executionType" to trigger.executionType,
                "logPath" to trigger.logPath,
                "isFinal" to trigger.isFinal,
                "status" to trigger.status,
                "triggered" to trigger.accepted,
            ),
            artifacts = emptyList(),
            errorCode = errorCode,
        )
    } catch (e: Exception) {
        McpToolResult.internalErrorResult("force_gradle_compile", e.message ?: "unknown error")
    }
}
```

**修复方案 C**: 确保编译错误详情被填充

需要检查 `IJuggConfigurationRunner.runFirstConfiguration()` 的实现类，确保在编译失败时将编译器诊断输出（文件名、行号、错误描述）写入 `JuggRunInvocationResult.detail` 字段。参考路径：`idea/src/main/java/com/sickworm/intellij/jugg/` 中 `IJuggConfigurationRunner` 的实现。

若编译器输出在日志文件 `build/jugg/log/compile_latest.log` 中已有，可在编译失败时读取该文件内容作为 `detail`：

```kotlin
// 在 CompileJobManager.triggerJuggCompile() 的失败分支或
// CompileAndDeployMcpToolAction.deployAction() 的失败分支
val logFile = File(runtime.project.basePath, COMPILE_LATEST_LOG_PATH)
val logContent = if (logFile.exists()) logFile.readText() else ""
// 将 logContent 作为 detail 传递
```

---

## 3. P1 - 重要问题

### 3.1 record / layout_dump 工具 adb 路径问题

**影响 TC**: TC-10, TC-11, TC-12, TC-63, TC-64 (BLOCKED)

**现象**: `record` 工具报错 `Cannot run program "adb": error=2, No such file or directory`。

**根因分析**:

`RecordMcpToolAction` 中 `adb.execAdbShellScript()` 最终调用 `IdeaDeviceAdb.execAdbShellScript()` → `execAdbShellCmdByCli()`，该方法通过 `Runtime.getRuntime().exec(arrayOf(adbBin, ...))` 直接执行系统 adb 二进制。当 `ANDROID_HOME`/`ANDROID_SDK_ROOT` 环境变量不存在时，回退到裸 `"adb"`，导致 `No such file or directory`。

`screenshot`、`layout-dump`、`tap` 等工具正常是因为它们使用的 `execAdbShellCmd()` 走的是 ddmlib 的 `AdbClient.shell()` 内部 API（不依赖系统 PATH）。

**涉及文件**:

| 文件 | 修改点 |
|------|--------|
| `mcp/actions/RecordMcpToolAction.kt` | `recordAction()` 中将 `execAdbShellScript(flowCommand)` 改为 `execAdbShellCmd("sh -c '...'")` |
| `idea/src/main/java/.../deploy/IdeaDeviceAdb.kt` | `execAdbShellScript()` 改为走 ddmlib 而非系统 adb CLI |

**修复方案**（采用方案2 — 修改 `IdeaDeviceAdb.execAdbShellScript()`）:

将 `execAdbShellScript()` 的实现从直接调用系统 adb CLI 改为通过 ddmlib 的 `execAdbShellCmd()` 传递，用 `sh -c '...'` 包装以支持 shell 语法（`&`、`$!`、`wait` 等）：

```kotlin
// IdeaDeviceAdb.kt - execAdbShellScript()
override fun execAdbShellScript(cmd: String): String {
    synchronized(IdeaDeviceAdb::class.java) {
        logger.debug("adb script in: sh -c '...'")
        // 转义单引号，用 sh -c 包装以支持 shell 语法（管道、后台进程等）
        val escaped = cmd.replace("'", "'\\''")
        return execAdbShellCmd("sh -c '$escaped'", retryCount = 0)
    }
}
```

这样所有 shell 脚本命令都走 ddmlib 的内部 API，不再依赖系统 PATH 中的 adb 二进制。

### 3.2 无设备时编译部署类工具的测试用例修正

**影响 TC**: TC-44, TC-45, TC-46 (FAIL)

**现象**: `deploy` / `gradle-build` / `reinstall` 在无设备时不立即返回 `NO_DEVICE`，而是先开始编译，在部署阶段才失败。

**结论**: 这是**符合预期的行为**，不是 bug。这三个工具支持无设备调用：
- `deploy`：无设备时仍可编译，编译成功后在部署阶段会失败
- `gradle-build`：纯编译操作，不依赖设备
- `reinstall`：底层调用 `deployAction()`，行为同 `deploy`

**修复方案**: 更新 `mcp_test_case.md` 中 TC-44~46 的预期结果，从 `NO_DEVICE` 改为允许正常编译执行。同步更新附录工具清单中相关工具的"需要设备"标记。

### 3.3 无效 jobId 查询返回 status=OK

**影响 TC**: TC-28 (FAIL)

**现象**: `get_compile_status` 传入不存在的 jobId 返回 `status=OK`, `data.status=unknown`。

**涉及文件**: `mcp/actions/GetCompileStatusMcpToolAction.kt`

**修复方案**:

```kotlin
// GetCompileStatusMcpToolAction.kt - execute()
override fun execute(arguments: Map<String, Any?>, runtime: IMcpRuntime): McpToolResult {
    val jobId = arguments["jobId"] as? String
    if (jobId.isNullOrBlank()) {
        return McpToolResult(
            status = McpToolStatus.ERROR,
            message = "get_compile_status failed. Reason: jobId is required.",
            data = emptyMap<String, Any>(),
            artifacts = emptyList(),
            errorCode = McpErrorCode.INVALID_PARAMS,
        )
    }
    val state = CompileJobManager.getStatus(jobId)
    val data = mutableMapOf<String, Any?>(
        "jobId" to state.jobId,
        "status" to state.status,
        "executionType" to state.executionType,
        "message" to state.message,
    )
    state.finishedAt?.let { data["finishedAt"] = it }

    // jobId 不存在时返回 ERROR
    if (state.status == "unknown") {
        return McpToolResult(
            status = McpToolStatus.ERROR,
            message = "get_compile_status failed. Reason: Compile job not found for jobId=$jobId.",
            data = data,
            artifacts = emptyList(),
            errorCode = McpErrorCode.INVALID_PARAMS,
        )
    }

    return McpToolResult(
        status = McpToolStatus.OK,
        message = "get_compile_status executed successfully.",
        data = data,
        artifacts = emptyList(),
        errorCode = null,
    )
}
```

---

## 4. P2 - 一般问题

### 4.1 restart_app 返回数据不完整

**影响 TC**: TC-22 (FAIL)

**现象**: 成功时 `data` 为空对象 `{}`，缺少 `deviceSerial` 和 `restarted` 字段。

**结论**: `restart` 不需要返回额外字段，`data` 保持空对象即可。

**实际修复**: 更新测试用例 TC-22，移除对 `deviceSerial` 和 `restarted` 的预期。更新 `mcp_test_case.md`。

### 4.2 restart_app schema 缺少 serial 参数

**影响 TC**: TC-23, TC-24 (SKIP)

**现象**: `restart` 的 inputSchema 中无 `serial` 参数，但文档 `08_mcp_usage.md` 标注其支持可选 `serial`。

**结论**: `restart` 不需要 `serial` 参数，始终使用 IDE 当前选中设备。

**实际修复**:
- 更新 `08_mcp_usage.md`，移除 `restart` 的可选 `serial` 标注
- 更新 `mcp_test_case.md` TC-23/TC-24，标记为不适用

### 4.3 长耗时 compile_and_deploy 失败时未走降级路径

**影响 TC**: TC-39 (FAIL)

**跳过**: 用户已手动更新测试用例，本轮不修复。

### 4.4 设备选择信息未体现在 message 中

**影响 TC**: TC-55 (FAIL)

**现象**: 不传 serial 时 message 中无 "Serial not provided; selected device" 字样。

**结论**: 为精简 MCP response，不再在 message 中提供设备选择说明。

**实际修复**: 更新 `mcp_test_case.md` TC-55，移除对设备选择信息的预期。

### 4.5 返回结构不含 errorCode 字段

**影响 TC**: TC-60 (FAIL)

**现象**: 正常返回仅 4 个字段（status/message/data/artifacts），缺少 `errorCode`。

**根因**: Gson 默认不序列化 null 值，成功时 `errorCode = null` 导致 JSON 中不出现该字段。

**实际修复**: 在 `McpLocalServer.kt` 中将 `Gson()` 改为 `GsonBuilder().serializeNulls().create()`，使 Gson 序列化时保留 null 值字段。Action 代码无需修改。

### 4.6 无设备错误返回格式不统一

**影响 TC**: TC-47~54（标记为 PASS，但备注格式问题）

**现象**: `restart` / `screenshot` 等工具在无设备时返回的是 MCP 框架级错误字符串，而非标准 JSON `{status, errorCode}`。

**结论**: 已在 P0 修复中统一解决。`McpToolInvoker.handleToolsCall()` 现在所有业务结果（包括 `McpToolStatus.ERROR`）都通过 `resultMapper.toolSuccess(isError=false)` 返回，客户端通过 `structuredContent.status` 判断成功/失败。

---

## 5. SKIP 用例说明

以下 TC 因 MCP 客户端 schema 校验拦截了非法参数，无法发送到服务端：

| TC | 原因 | 是否需要修复 |
|----|------|-------------|
| TC-04 | `reason` 在 schema 中非 required，客户端不允许不传已定义参数 | 服务端可选加防御校验，优先级低 |
| TC-13 | `durationSec` 的 `maximum: 180` 被客户端校验 | 客户端已保护，无需服务端修复 |
| TC-21 | `x`/`y` 为 required，客户端校验 | 同上 |
| TC-56 | 单设备环境无法测试 | 环境限制，非代码问题 |
| TC-57 | `projectDir` required 被客户端校验 | 同上 |
| TC-58 | `projectDir` pattern 被客户端校验 | 同上 |
| TC-59 | 需要直接构造 JSON-RPC 请求 | 超出 MCP 客户端工具能力 |

---

## 6. 实施顺序建议

| 阶段 | 修复项 | 涉及文件 |
|------|--------|----------|
| 第一阶段 | 2.1 编译失败 status + 错误详情 | `CompileAndDeployMcpToolAction.kt`, `ForceGradleCompileMcpToolAction.kt`, Runner 实现类 |
| 第二阶段 | 3.2 无设备前置检查 | `CompileAndDeployMcpToolAction.kt`, `ForceGradleCompileMcpToolAction.kt` |
| 第二阶段 | 3.3 无效 jobId 返回 ERROR | `GetCompileStatusMcpToolAction.kt` |
| 第三阶段 | 3.1 adb 路径问题 | `IDeviceAdb` 实现类 |
| 第四阶段 | 4.1~4.6 P2 问题 | 各相关文件 |

---

## 7. 关键代码路径参考

| 模块 | 文件路径 |
|------|----------|
| MCP actions 目录 | `main/src/main/java/com/sickworm/intellij/jugg/mcp/actions/` |
| 工具调用路由 | `mcp/McpToolInvoker.kt` |
| 返回结构映射 | `mcp/McpResultMapper.kt` |
| 数据模型 | `mcp/McpToolModels.kt` |
| 设备选择 | `mcp/DeviceSelectionResolver.kt` |
| 编译任务管理 | `mcp/actions/CompileJobManager.kt` |
| Runner 接口 | `ide/logic/IJuggConfigurationRunner.kt` |
| 错误码 | `mcp/McpErrorCode.kt` |
