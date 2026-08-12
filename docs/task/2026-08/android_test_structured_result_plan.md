# AndroidTest 结构化结果输出方案

## 背景

Jugg 已经能够通过 `instrument` 完成 androidTest 源码解析、增量编译、APK 部署、`am instrument` 执行和多设备结果聚合。运行过程中，`AndroidTestResultModel` 已保存设备信息、测试方法状态和失败栈，IDE Test Results 也能展示这些信息。

当前 MCP 返回链路只暴露通用的编译和部署结果：

```text
AndroidTestResultModel
  -> JuggRunningTask 内部持有
  -> RunResult 只保留 compile/deploy flags
  -> JuggRunInvocationResult
  -> CompileJobManager
  -> instrument / get-compile-status
```

因此 Agent 只能从 `status`、`isCompileSuccess`、`isDeploySuccess` 和文本 `detail` 推断测试结果，无法确定地读取：

- 实际执行了哪些 test class / method。
- 每个测试在每台设备上的 Pass / Fail / Ignored 状态。
- 失败测试对应的异常栈。
- 测试是否根本没有开始执行。
- 同步返回和异步轮询是否代表同一份最终测试结果。

## 目标

让 `instrument` 的 MCP JSON 结果直接包含稳定、可机器判定的 androidTest 结果，并保证同步完成和异步 `get-compile-status` 完成时返回同一份结构。

首版需要回答三个问题：

1. 编译、部署和 instrumentation 分别是否成功。
2. 实际执行了哪些测试，各自结果是什么。
3. 失败发生在哪台设备、哪个 test method，失败栈是什么。

## 非目标

- 不自动准备缺失的 AndroidTest full-build baseline。
- 不增加 androidTest module / class / method 发现工具。
- 不生成、修改或删除 AndroidTest 源码。
- 不判断某个测试是否值得保留。
- 不恢复 `layout-verify` 或调整 UI E2E 工具。
- 首版不建立覆盖所有失败原因的细粒度 MCP error code 体系。
- 不把完整设备 logcat 和 method logcat 全量内联到 MCP JSON。
- 不改变普通 `compile`、`deploy`、`gradle-build` 的既有结果语义。

## 设计原则

### 使用真实运行状态

测试明细必须由现有 `AndroidTestResultModel` 生成，编译和部署阶段状态来自对应运行结果。禁止从控制台文本或 `detail` 反向解析任何阶段结果。

### 最终快照不可变

`AndroidTestResultModel` 继续负责运行期聚合，在任务完成时生成不可变结果快照。后续 `RunResult`、MCP 同步返回和异步轮询只透传该快照，不再修改结果。

### 同步与异步契约一致

`instrument` 在 25 秒内完成时直接返回 `data.androidTestResult`；超过 soft timeout 后，最终 `get-compile-status` 返回完全相同的数据结构。

### 保持通用 RunResult 兼容

在 `RunResult` 中增加可空的 `androidTestResult`。普通 App Run、compile 和 deploy 保持 `null`，现有字段与成功判定不变。AndroidTest 新结果必须显式区分 deploy 成功和 instrumentation 失败，不能继续要求 Agent 从兼容字段 `isDeploySuccess` 猜测阶段状态。

### 控制 MCP payload

首版结构化输出包含测试状态和失败栈，不内联完整日志。现有 `detail` 预览和 log artifact 继续承担日志排查职责。

## 结果模型

在 `main` 模块新增精简、不可变的 androidTest 结果 DTO。命名可在实现时按现有模型风格微调，但字段语义保持如下：

```kotlin
data class AndroidTestExecutionResult(
    val status: AndroidTestExecutionStatus,
    val phases: AndroidTestExecutionPhases,
    val summary: AndroidTestExecutionSummary,
    val devices: List<AndroidTestExecutionDevice>,
    val tests: List<AndroidTestExecutionTest>,
)

enum class AndroidTestExecutionStatus {
    PASS,
    FAIL,
    NOT_RUN,
    CANCELED,
}
```

`phases`：

| 字段 | 类型 | 说明 |
|------|------|------|
| `compile` | String | `PASS` / `FAIL` / `CANCELED` |
| `deploy` | String | `PASS` / `FAIL` / `NOT_RUN` / `CANCELED` |
| `instrument` | String | `PASS` / `FAIL` / `NOT_RUN` / `CANCELED` |

`AndroidTestExecutionResult.status` 表示本轮 `instrument` 的总结果：三个阶段均完成且 instrumentation 通过时为 `PASS`；任一已执行阶段失败时为 `FAIL`；用户取消时为 `CANCELED`；尚未进入可判定的 instrumentation 结果时为 `NOT_RUN`。

`summary`：

| 字段 | 类型 | 说明 |
|------|------|------|
| `deviceCount` | Int | 参与本轮 instrumentation 的设备数量 |
| `testCount` | Int | 去重后的 class + method 数量 |
| `passed` | Int | 按设备 cell 统计的 Pass 数量 |
| `failed` | Int | 按设备 cell 统计的 `FAILURE` / `ERROR` / `ASSUMPTION_FAILURE` 数量 |
| `ignored` | Int | 按设备 cell 统计的 `IGNORED` 数量 |
| `notRun` | Int | 多设备矩阵中未运行的 cell 数量 |

`devices[]`：

| 字段 | 类型 | 说明 |
|------|------|------|
| `serial` | String | 设备 serial |
| `name` | String | 当前设备展示名 |
| `api` | Int? | Android API level |
| `status` | String | `PASS` / `FAIL` / `NOT_RUN` / `CANCELED` |
| `failureReason` | String? | instrumentation 未正常完成时的设备级原因 |

`tests[]`：

| 字段 | 类型 | 说明 |
|------|------|------|
| `className` | String | 测试类全名 |
| `methodName` | String | 测试方法名 |
| `devices` | List | 逐设备 cell 结果 |

测试的逐设备 cell：

| 字段 | 类型 | 说明 |
|------|------|------|
| `deviceName` | String | 与 `devices[].name` 对应 |
| `status` | String | `PASS` / `FAILURE` / `ERROR` / `IGNORED` / `ASSUMPTION_FAILURE` / `RUNNING` / `NOT_RUN` |
| `stack` | String? | instrumentation 提供的失败栈 |

逐测试状态必须保留 `InstrumentationEvent.TestResult` 的原始语义。现有 `AndroidTestCellStatus` 将 `FAILURE` / `ERROR` 合并为 Fail，并将 `IGNORED` / `ASSUMPTION_FAILURE` 合并为 Ignored，只适合 IDE 展示，不得直接作为公开协议的数据源。

## 状态判定

最终快照使用以下最小规则：

- 编译失败：`phases.compile=FAIL`，后续阶段为 `NOT_RUN`，总结果为 `FAIL`。
- 部署失败：`phases.compile=PASS`、`phases.deploy=FAIL`、`phases.instrument=NOT_RUN`，总结果为 `FAIL`。
- APK 已成功部署但测试断言失败：`phases.deploy=PASS`、`phases.instrument=FAIL`，总结果为 `FAIL`。
- 存在测试 `FAILURE` / `ERROR` / `ASSUMPTION_FAILURE`、instrumentation aborted、非 0 退出、设备异常或其他设备级失败：`phases.instrument=FAIL`。
- 至少存在一个实际完成的测试，且不存在上述失败：`PASS`。
- 没有任何测试结果，且 instrumentation 未形成可判定的成功结果：`NOT_RUN`。
- `IGNORED` 不单独令整体失败；`ASSUMPTION_FAILURE` 沿用当前 `TestLauncher` 行为，令 instrumentation 失败，并在 cell 中保留原始状态。
- 普通非 androidTest 运行不生成快照，`RunResult.androidTestResult = null`。

设备异常、非 0 退出和 aborted 当前没有统一写入 `AndroidTestResultModel`。实现时只增加最小的设备级完成状态与 `failureReason` 记录，不从日志文本猜测失败类型。

## MCP 返回契约

### 同步完成

`instrument` 进入终态后始终返回结构化结果，包括编译失败、部署失败、测试断言失败和测试通过。整体 MCP `status` 继续反映本轮 instrument 是否通过：任一阶段失败仍为 `ERROR`，但 `data.androidTestResult` 必须保留。

```json
{
  "status": "ERROR",
  "message": "instrument failed. Reason: Instrumentation test run reported failures.",
  "data": {
    "status": "failed",
    "isFinal": true,
    "isCompileSuccess": true,
    "isDeploySuccess": false,
    "androidTestResult": {
      "status": "FAIL",
      "phases": {
        "compile": "PASS",
        "deploy": "PASS",
        "instrument": "FAIL"
      },
      "summary": {
        "deviceCount": 1,
        "testCount": 2,
        "passed": 1,
        "failed": 1,
        "ignored": 0,
        "notRun": 0
      },
      "devices": [
        {
          "serial": "emulator-5554",
          "name": "Pixel_9 API 35",
          "api": 35,
          "status": "FAIL",
          "failureReason": null
        }
      ],
      "tests": [
        {
          "className": "com.example.LoginTest",
          "methodName": "loginShowsHome",
          "devices": [
            {
              "deviceName": "Pixel_9 API 35",
              "status": "FAILURE",
              "stack": "java.lang.AssertionError: ..."
            }
          ]
        }
      ]
    }
  }
}
```

`isDeploySuccess` 首版保持现有兼容语义：instrumentation 失败仍可能使该字段为 false。新增协议以 `androidTestResult.phases.deploy=PASS` 表达 APK 已成功部署，以 `phases.instrument=FAIL` 表达测试失败。Agent 判断 AndroidTest 各阶段结论时必须读取 `androidTestResult.phases`，不再从兼容字段 `isDeploySuccess` 推断。

### 异步完成

`instrument` 返回 `isFinal=false` 时不携带未完成的测试快照。任务终态后，`get-compile-status` 在 `data.androidTestResult` 返回与同步完成完全相同的结构。

```text
instrument
  -> isFinal=false + jobId
get-compile-status
  -> running: androidTestResult 缺省
  -> success/failed/canceled: androidTestResult 按实际结果返回
```

### 无结果场景

- 编译阶段失败：返回 `compile=FAIL`、`deploy=NOT_RUN`、`instrument=NOT_RUN`。
- 部署在 instrumentation 前失败：返回 `compile=PASS`、`deploy=FAIL`、`instrument=NOT_RUN`。
- instrumentation 正常结束但没有形成测试 cell：返回 `status=NOT_RUN`。
- instrumentation 非正常结束且没有形成测试 cell：返回 `status=FAIL`，并保留设备级 `failureReason`。
- 普通 `compile` / `deploy` job：`androidTestResult` 缺省。

## 数据流调整

### 1. 生成不可变快照

修改：

- `main/.../deploy/instrument/AndroidTestResultModel.kt`

新增 `snapshot()` 或等价方法，按当前设备和测试事件生成不可变测试明细。模型需要保留 `FAILURE`、`ERROR`、`IGNORED` 和 `ASSUMPTION_FAILURE` 的原始区别；现有 `matrix()`、`matrixText()` 和 IDE detail 可继续使用合并后的展示状态。

### 2. 记录设备级失败

修改：

- `idea/.../deploy/run/instrument/TestLauncher.kt`
- `main/.../deploy/instrument/AndroidTestResultModel.kt`

在以下已知边界记录设备完成状态和失败原因：

- instrumentation command 非 0 退出。
- `InstrumentationEvent.Aborted`。
- 设备执行异常。
- instrumentation 正常完成。
- `ASSUMPTION_FAILURE` 与普通 `IGNORED` 分别记录。

不新增基于普通业务日志的失败推断。

### 3. 分离 deploy 与 instrument 阶段状态

修改：

- `main/.../deploy/run/LaunchResult.kt`
- `idea/.../deploy/run/JuggDeployHelperBean.kt`
- `idea/.../deploy/run/JuggDeployerHelper.kt`

当前 instrumentation 失败会复用 `LaunchResult.success=false`，导致 `DeployTaskResult.isSuccess=false`，从而丢失“APK 已成功部署，只是测试失败”的事实。增加最小的阶段结果字段，让 androidTest 调用方能够同时获得：

- APK 部署是否已经成功。
- instrumentation 是否执行及其结果。
- 兼容的 overall success 是否成功。

普通 deploy 路径使用安全默认值，保持现有行为。不得通过比较 `failedReason` 文本识别 instrumentation 失败。

### 4. 进入 RunResult

修改：

- `main/.../compiler/ui/RunResult.kt`
- `idea/.../ide/logic/JuggRunningTask.kt`

在 `RunResult` 增加可空 `androidTestResult`。`JuggRunningTask` 只在存在 `androidTestRunSpec` 时，于任务终态结合编译结果、分离后的 deploy/instrument 阶段结果和 `androidTestResultModel` 快照组装最终结果。

所有 compile、deploy、cancel 和 instrumentation fail 的终态出口都必须统一附加快照，避免只有成功路径能返回结果。实现时优先使用一个小型私有收口方法，不复制多处分支逻辑。

### 5. 透传同步结果

修改：

- `main/.../ide/logic/IJuggConfigurationRunner.kt`
- `idea/.../ide/logic/JuggConfigurationRunner.kt`
- `main/.../ai/mcp/actions/CompileAndDeployMcpToolAction.kt`

`JuggRunInvocationResult` 继续携带 `RunResult`。MCP 结果构造器从 `RunResult.androidTestResult` 提取到 `data.androidTestResult`。普通工具没有该字段。

### 6. 透传异步结果

修改：

- `main/.../ai/mcp/actions/CompileJobManager.kt`
- `main/.../ai/mcp/actions/GetCompileStatusMcpToolAction.kt`

`CompileJobExecutionResult` 和 `CompileJobStatus` 保存可空 `androidTestResult`。job 进入终态时写入，`get-compile-status` 在终态响应中返回。running 状态不返回部分结果。

### 7. 更新公开 schema 和文档

修改：

- `main/.../ai/mcp/actions/InstrumentMcpToolAction.kt`
- `main/.../ai/mcp/actions/GetCompileStatusMcpToolAction.kt`
- `docs/ai_knowledge/06_android_test.md`
- `docs/ai_knowledge/08_mcp_tools_list.md`
- `docs/ai_knowledge/08_cli_tools_list.md`
- `docs/skills/jugg-android-dev-loop/references/flow_android_test.md`

`instrument` 和 `get-compile-status` 的 output schema 必须描述 `androidTestResult`。CLI AndroidTest 流程改为优先读取结构化结果，文本 `detail` 只用于补充诊断。

## 兼容性

- 所有新增字段均为可选字段，旧 MCP 客户端可忽略。
- 现有 `runResult`、`isCompileSuccess`、`isDeploySuccess`、`detail` 和 artifact 保留。
- IDE Test Results、rerun failed、console 输出和多设备矩阵行为不变。
- AndroidTest 断言失败仍使 `instrument` 返回 `ERROR`，不会伪装成工具成功。
- 不修改 `AndroidTestRunSpec` 和 `am instrument` 参数契约。

## 测试与验证

### 失败证据

实现前增加以下失败断言，证明当前链路会丢失结构化结果：

| 层级 | Owner | 场景 | 修改前预期 |
|------|-------|------|------------|
| L1 | `AndroidTestResultModelTest` | 多设备原始 test result 生成不可变快照 | `snapshot()` 不存在，Assumption 与 Ignored 被合并 |
| L2 | `TestLauncherResultTest` | 非 0 退出、aborted、设备异常写入设备级失败 | 模型中没有对应结构化状态 |
| L2 | 现有 deploy/run owner | APK 部署成功但 assertion 失败时阶段状态为 deploy PASS、instrument FAIL | 当前只得到整体 deploy false |
| L2 | `InstrumentMcpToolActionTest` | 编译失败、部署失败、测试失败、测试通过均返回对应 phases | MCP data 中没有该字段 |
| L2 | `GetCompileStatusMcpToolActionTest` | 异步 instrument 终态返回相同结果 | job status 丢失测试结果 |

### 定向自动化测试

```bash
./gradlew :main:test --tests "com.sickworm.intellij.jugg.deploy.instrument.AndroidTestResultModelTest"
./gradlew :idea:test --tests "com.sickworm.intellij.jugg.deploy.run.TestLauncherResultTest"
./gradlew :main:test --tests "com.sickworm.intellij.jugg.ai.mcp.actions.InstrumentMcpToolActionTest"
./gradlew :main:test --tests "com.sickworm.intellij.jugg.ai.mcp.actions.GetCompileStatusMcpToolActionTest"
```

### L3 与真实协议验证

- 回归 `AndroidTestTopLevelFlowTest`，确认真实设备的 androidTest 增量部署和 instrumentation 主链路不变。
- 在 `android_demo_project` 使用 JSON 模式定向执行一个通过用例，确认 `androidTestResult.status=PASS` 和计数正确。
- 定向执行一个预期失败用例，确认 MCP 返回 `ERROR`，同时为 `compile=PASS`、`deploy=PASS`、`instrument=FAIL`，并保留失败 method 和 stack。
- 将 soft timeout 调低或使用现有异步测试入口，确认 `get-compile-status` 最终结构与同步结构一致。

示例命令：

```bash
python3 docs/skills/jugg-android-dev-loop/scripts/jugg.py --console=json instrument \
  --source-path app/src/androidTest/java/com/example/myapplication/AppUiInstrumentedTest.kt \
  --class com.example.myapplication.AppUiInstrumentedTest \
  --method mainActivityShowsTitle
```

### 其他验证

```bash
./gradlew :idea:compileKotlin
git diff --check
```

## 实施顺序

1. 在 `AndroidTestResultModelTest` 中先定义最终快照契约并确认失败。
2. 实现不可变结果 DTO、原始 test result 保存、`snapshot()` 和设备级完成状态。
3. 在 `TestLauncherResultTest` 中覆盖正常完成、Failure、Error、Ignored、Assumption Failure、非 0 退出、aborted 和设备异常。
4. 在现有 deploy/run owner 中先写失败断言，再分离 APK deploy 与 instrumentation 阶段结果。
5. 将三个阶段和测试快照组装进 `RunResult`，确保 JuggRunningTask 所有 androidTest 终态出口一致。
6. 增加同步 `instrument` MCP 结果断言，再实现 `data.androidTestResult` 映射。
7. 增加异步 job 结果断言，再实现 `CompileJobManager` 与 `get-compile-status` 透传。
8. 更新 MCP schema、CLI 流程与 AI 知识库。
9. 执行定向测试、L3 Flow、真实 JSON 协议验证和编译验证。

## 后续独立任务

以下方向在本任务完成后再单独评估：

- 自动建立缺失的 AndroidTest baseline。
- 新增测试发现接口。
- 对 assertion、runner start、app crash、device disconnect 等失败建立细粒度 error code。
- 为失败测试提供独立的 method log artifact。
- MCP rerun failed 与指定设备执行。
