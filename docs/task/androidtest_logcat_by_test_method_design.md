# Jugg AndroidTest 按 test method 归档设备 logcat 设计

> 创建时间：2026-05-07
> 状态：设计阶段
> 一致性规则：文档与代码冲突时，以代码为准。

---

## 1. 背景

当前 Jugg AndroidTest 已经具备：

- app 模块 `androidTest` 编译与部署
- `am instrument -w -r` 运行
- `InstrumentationOutputParser` 解析测试生命周期
- `InstrumentationSmRunnerBridge` 驱动 Test Results 树
- `AndroidTestResultModel` 维护设备级结果与 log 列表

但现在的设备 log 仍然是 **整机/整场 run 级** 记录：

- `TestLauncher` 只把 `runInstrumentation()` 流式输出喂给 `InstrumentationOutputParser`
- `AndroidTestResultModel.recordLog()` 只按 device 保存原始输出
- method 节点能看到测试结果，但看不到该测试期间的设备 logcat 片段

你要的目标是对齐 Android Studio AndroidTest 的体验：

> 在 Test Results 中，每个 test method 节点下能看到该方法执行期间的设备 logcat。

这不是 JVM 单测 stdout/stderr 问题，而是 **device logcat 按 test lifecycle 切片归档** 问题。

---

## 2. 目标与边界

### 2.1 总目标

在不改变现有 AndroidTest 执行链路的前提下，把设备 logcat 按 test method 归档，并在 Test Results 的 method 视图中可见。

### 2.2 目标效果

- 看到某个 test method 时，能查看该 method 期间的设备 logcat。
- 同一设备上的不同 test method 互不串台。
- 多设备并发/顺序运行时，各设备的 method log 互不污染。
- test 外的 log 仍保留在 device 级，不强行塞进 method。

### 2.3 非目标

- 不改 AndroidTest 编译、部署、`am instrument` 协议。
- 不替换现有 `InstrumentationOutputParser`。
- 不引入新的 Android Studio runner 执行框架。
- 不做 library androidTest 支持。
- 不做 debug executor。
- 不做复杂 logcat 过滤/搜索 UI。

---

## 3. 核心思路

### 3.1 事件分层

现有链路已经能提供 test 生命周期：

```text
logcat / instrumentation output
  -> InstrumentationOutputParser
  -> InstrumentationEvent.TestStarted / TestFinished / Aborted
```

我们要新增一层 **logcat method router**：

```text
设备 logcat 行
  -> per-device logcat reader
  -> 当前活跃 test method
  -> method 级日志缓冲
  -> Test Results method 节点详情
```

### 3.2 关键原则

1. **以 test 生命周期为准，不以 tag 字符串猜测 method**。  
   method 归属必须跟随 `TestStarted` / `TestFinished`。
2. **logcat 采集与 test 结果解析分离**。  
   parser 仍只解析 instrumentation 协议。
3. **按 device 独立采集**。  
   每台设备有自己的当前 test 与缓存。
4. **flush 要可靠**。  
   `TestFinished`、`Aborted`、设备断开、exit 非 0 时都要把尾部缓存落盘/落模型。

---

## 4. 设计方案

### 4.1 推荐方案：在 TestLauncher 内增加 per-device logcat collector

最小侵入方案是把 logcat 采集直接挂在 `TestLauncher` 这一层，因为这里同时掌握：

- 当前 device
- 当前 instrumentation stream
- `InstrumentationEvent`
- 最终 `AndroidTestResultModel`

#### 处理流程

1. `TestLauncher` 启动某个 device 的 instrumentation 前，创建该 device 的 logcat collector。
2. collector 持续读设备 logcat 行。
3. `InstrumentationOutputParser` 触发 `TestStarted(class, test)` 时，切换当前活跃 method。
4. collector 读到的 logcat 行写入当前 method 的缓冲区。
5. `TestFinished` 时，把当前 method 缓冲区 flush 到 `AndroidTestResultModel`。
6. device 结束或 aborted 时，先 flush 当前 method，再关闭 collector。

### 4.2 为什么不放到 parser

`InstrumentationOutputParser` 的职责是解析 `am instrument` 协议，不应该同时承担 logcat 采样和归档，否则职责会混在一起，后续难测也难定位。

### 4.3 为什么不放到 console renderer

`InstrumentationConsoleRenderer` 只负责文本输出展示，不知道 device 生命周期，也不知道哪个 logcat 行属于哪个 test method。

---

## 5. 数据模型改造

### 5.1 AndroidTestResultModel 扩展

当前 `AndroidTestResultModel` 只保存：

- device info
- device logs
- test result matrix
- test stack

需要新增 method 级 log 归档能力。

建议新增：

```text
results[className#testName].logs[deviceName] = list of log lines
```

也就是说：

- device 级保留原始 logcat 汇总
- method 级额外保留该 method 执行窗口内的 logcat

### 5.2 建议新增的读取接口

- `testDetail(className, testName)` 继续保留
- 新增 method 级 log 读取，例如 `testLogs(className, testName)` 或在 `testDetail` 中直接拼接 logs

推荐优先新增独立接口，避免把现有 detail 文本一次性变得难维护。

### 5.3 建议新增的内部结构

- `DeviceActiveTestState`：当前 device 活跃的 test key + 缓冲日志
- `TestKey`：`className + testName`
- `MethodLogBuffer`：按 device 分别缓存当前 method logcat

---

## 6. logcat 采集策略

### 6.1 采集边界

采集窗口定义为：

- 开始：收到 `InstrumentationEvent.TestStarted`
- 结束：收到对应 `InstrumentationEvent.TestFinished`

如果中间出现：

- `Aborted`
- 设备断开
- instrumentation exit 非 0

也要执行 flush。

### 6.2 归档规则

- 只有在当前 method 激活期间收到的 logcat 行，才进入 method 缓冲。
- method 外 logcat 只进入 device 级日志。
- 一个 method 结束后，后续 log 只能进入下一个 method 或 device 级。
- 若 instrumentation 输出乱序，优先按 `TestStarted/TestFinished` 保守处理，不猜测补归属。

### 6.3 多设备

每个 device 各自维护：

- 独立 logcat reader
- 独立 current test
- 独立 method buffer

不共享任何 method 归属状态。

---

## 7. 失败与异常处理

### 7.1 失败场景

- instrumentation 失败退出
- test 失败但 run 继续
- aborted
- 设备断开
- logcat reader 提前结束

### 7.2 处理原则

- 尽量保留当前 method 已采集日志。
- `TestFinished` 时强制 flush 当前 method buffer。
- `Aborted` 时把当前活跃 method 也 flush。
- 设备断开时把 device 级残留日志保留，method 级未完成部分尽量 flush。

### 7.3 不做的事

- 不尝试从 logcat 中反推 method。
- 不在采集失败时重新跑测试。
- 不做跨 device 日志合并。

---

## 8. 测试设计（TDD 前置）

### 8.1 主测试文件

建议优先在已有 `idea/src/test/java/com/sickworm/intellij/jugg/deploy/run/TestLauncherResultTest.kt` 扩展，原因是这类行为归属 `TestLauncher`。

如果 `AndroidTestResultModel` 需要新增独立方法，也可补 `main/src/test/java/com/sickworm/intellij/jugg/deploy/instrument/AndroidTestResultModelTest.kt`。

### 8.2 必须先写的失败测试

1. **单设备单 method**：method 期间的 logcat 能归档到该 method。
2. **单设备多 method**：前一个 method 的 log 不会串到后一个。
3. **method 外 logcat**：只进入 device 级，不进入任一 method。
4. **多设备**：A 设备 log 不进入 B 设备 method。
5. **abort 场景**：当前 method 缓冲会 flush。
6. **失败退出**：即使 instrumentation 非 0，已采集 method log 仍保留。

### 8.3 测试输入方式

测试不直接依赖真实 adb logcat，而是通过可注入的 reader / collector fake 传入行序列，验证最终模型状态。

---

## 9. 实现顺序建议

1. 先为 `AndroidTestResultModel` 增加 method log 结构和查询接口。
2. 再给 `TestLauncher` 增加 per-device logcat collector 接口。
3. 写失败测试，覆盖单设备/多设备/abort/失败退出。
4. 实现 collector 与 flush。
5. 最后补文档同步到 `06_android_test.md`、`98_code_map.md`、`99_index.md`。

---

## 10. 验收标准

满足以下条件即可认为完成：

- AndroidTest run 结束后，Test Results 的每个 method 节点可查看该 method 期间的设备 logcat。
- 同一 run 中 method 间日志不串台。
- 多设备场景彼此隔离。
- abort / fail / disconnect 都不会丢掉已采集的 method logs。
- 定向测试全部通过，且不需要跑全量测试套件。
