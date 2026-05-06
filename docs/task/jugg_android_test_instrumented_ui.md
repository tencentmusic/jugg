# Jugg Android Test Instrumented UI 方案

## 1. 背景与目标

Jugg Android Test Run Configuration 需要在 General 页对齐 Android Studio 原生 Android Instrumented Tests 的 Test scope UI。

本方案只覆盖 General 页的 Android Test 配置区域：

- 对齐四种 Test scope：All in Module、All in Package、Class、Method。
- 保持 Jugg 现有 androidTest 编译、部署、SM Test Runner 运行链路不重写。
- 不调整其他 tab。
- 不实现 `...` chooser。
- `Instrumentation arguments` 保持当前现状，不纳入本轮改造。

## 2. UI 布局

General 页按原生 Android Instrumented Tests 的顺序组织：

```text
Module: [app module]

Test: ( ) All in Module   ( ) All in Package   ( ) Class   ( ) Method

[scope-specific row]

Instrumentation class: [editable text]
Instrumentation arguments: [keep current behavior]
```

动态字段规则：

| Scope | 显示字段 | 必填 |
| --- | --- | --- |
| All in Module | `Regex:` | 否 |
| All in Package | `Package:` | 是 |
| Class | `Class:` | 是 |
| Method | `Class:` + `Method:` | 是 |

补充约束：

- 本轮不做任何 `...` chooser。
- 切换 scope 不自动清空其他 scope 字段，避免用户来回切换时丢失输入。
- 运行时只读取当前 scope 对应字段。
- `Instrumentation class` 是可编辑文本框。
- `Instrumentation arguments` 保持当前 UI 与行为，不因本轮改动新增、删除或重排入口。

## 3. 配置模型

`JuggAndroidTestRunConfiguration` 按四种 scope 建模：

| 字段 | 说明 |
| --- | --- |
| `testScope` | `ALL_IN_MODULE` / `ALL_IN_PACKAGE` / `CLASS` / `METHOD` |
| `regex` | All in Module 下的 Regex |
| `packageName` | All in Package 下的 Package |
| `testClass` | Class / Method 下的 Class |
| `testMethod` | Method 下的 Method |
| `instrumentationRunner` | 可编辑 runner override；空值使用 manifest/default runner |
| `extraArgs` | 保持当前现状 |

功能尚未发布，因此不考虑旧配置兼容和迁移逻辑。

默认值：

- 手动新建配置：`testScope = ALL_IN_MODULE`。
- class gutter：`testScope = CLASS`，填充 `testClass`。
- method gutter：`testScope = METHOD`，填充 `testClass` 和 `testMethod`。
- rerun failed：继续走现有 failed filters 机制，不反写 General 页 scope。

## 4. 运行映射与校验

运行时从 `testScope` 生成 `AndroidTestRunSpec`，只读取当前 scope 对应字段。

| Scope | 运行语义 | 校验 |
| --- | --- | --- |
| `ALL_IN_MODULE` | 跑当前 app module 的 androidTest；`regex` 非空时按原生 Android Instrumented Tests / AndroidJUnitRunner 参数语义传递 | `regex` 可空 |
| `ALL_IN_PACKAGE` | 跑 `packageName` 指定 package 下测试 | `packageName` 必填 |
| `CLASS` | 跑 `testClass` 指定测试类 | `testClass` 必填 |
| `METHOD` | 跑 `testClass#testMethod` | `testClass` / `testMethod` 都必填 |

严格校验在运行前阻断：

- All in Package scope 下 `packageName` 为空时阻断运行。
- Class scope 下 `testClass` 为空时阻断运行。
- Method scope 下 `testClass` 或 `testMethod` 为空时阻断运行。
- 非当前 scope 字段不参与校验。
- `instrumentationRunner` 为空时不阻断，继续使用 manifest/default runner；非空时作为 runner override。

Regex 不由 Jugg 自定义匹配对象。实施前需要实锤原生 Android Instrumented Tests 最终传给 AndroidJUnitRunner 的 instrumentation 参数形式，再写入实现、测试和知识库文档。

## 5. 测试策略

本功能涉及业务代码，实施时必须按 TDD：先写失败测试，再改实现。

### 5.1 RunSpec / 参数映射测试

覆盖：

- `ALL_IN_MODULE` 无 regex。
- `ALL_IN_MODULE` 有 regex。
- `ALL_IN_PACKAGE` 生成 package 过滤参数。
- `CLASS` 生成 class 过滤参数。
- `METHOD` 生成 `class#method` 过滤参数。
- `instrumentationRunner` 空值不覆盖 runner。
- `instrumentationRunner` 非空覆盖 runner。

### 5.2 严格校验测试

覆盖：

- Package scope 空 package 阻断。
- Class scope 空 class 阻断。
- Method scope 空 class 阻断。
- Method scope 空 method 阻断。
- 非当前 scope 字段为空不阻断。
- Regex 为空不阻断。

### 5.3 UI editor 状态测试

覆盖：

- 四个 radio 切换后只显示当前 scope 对应字段。
- `Instrumentation class` 可编辑。
- `Instrumentation arguments` 未被本轮改动。
- 不出现 `...` chooser。

## 6. 落地步骤

1. 确认当前实现文件和测试入口：`JuggAndroidTestRunConfiguration.kt`、相关 editor、factory、RunSpec factory。
2. 先写 scope model、校验、RunSpec 生成的失败测试。
3. 实现配置模型：增加 `testScope`、`regex`、`packageName`，保留 `testClass`、`testMethod`、`instrumentationRunner`、`extraArgs`。
4. 实现 General 页动态 UI：四个 radio 与 scope-specific row。
5. 实现运行参数映射和严格校验。
6. 同步 `docs/ai_knowledge/06_android_test.md`：RunConfig 四种 scope、runner override、严格校验与 Regex 参数口径。
7. 如果路径或入口类发生变化，同步 `docs/ai_knowledge/98_code_map.md`。
8. 运行相关定向测试，必要时运行 `./gradlew :idea:compileKotlin`。
