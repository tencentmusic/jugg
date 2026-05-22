# Logger.getInstance 测试分支

## 背景

测试侧 `StdLogger` 注入后，`logger.getInstance("SubTag")` 原先返回 `this`，子 tag 不生效。

## 实现

- `ITestStdoutLogger`：`deriveTag(tag)` 返回带新 tag 的 logger。
- `Logger.getInstance`：`LogDispatcher` → `JuggLogger`；`ITestStdoutLogger` → `deriveTag`；否则 `this`。
- `StdLogger`（main/idea/cmd_line test）实现该接口，默认 `deriveTag` → `StdLogger(tag)`。

## 验证

```bash
./gradlew :main:compileKotlin :idea:compileKotlin
```

按需定向测试 deploy/run 等使用 `TestGlobal.logger` + `getInstance` 的用例。
