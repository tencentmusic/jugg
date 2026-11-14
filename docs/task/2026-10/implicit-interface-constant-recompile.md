# Java 接口隐式常量变化漏跟编修复

## 现场与根因

- Jugg report：`ce89601b`，现场插件 `3.5.0-release`。
- 2026-10-14 17:33、17:37 修改 `IFAKtvH5Code.java`：两个指令常量由 `10494/10495` 改为 `10498/10499`；定义文件编译成功，影响查询却返回空集合。
- 附件确认声明位于 Java 接口，写法为 `int CMD_... = ...;`；`KRKtvRoomGameWebDelegate.kt` 的指令判断、when 分支、订阅列表，以及 `KRKtvRoomGameMediaDebugDialog.kt` 的回调参数均引用这两个常量。
- `JavaConstParser` 要求字段显式包含 `final`，只补偿接口字段的隐式 `static`，因此定义丢失。直接解析附件得到 0 个定义，临时副本补齐显式修饰符后得到全部 6 个定义。
- 旧解析规则的空定义会按 mtime/checksum 缓存，单独修复 parser 不能保证已有缓存恢复。

## 修改范围

1. 接口和注解类型的直接字段按隐式 `static final` 判断；普通类仍要求字段同时具有显式 `static` 和 `final`，沿用类型、可见性和初始化值过滤。
2. ConstRef 缓存版本由 7 更新为 8，使用已有版本不兼容重建流程，清除旧规则产生的分析快照和 mtime/checksum 复用记录。
3. 不改变编译或部署编排、影响查询协议及 Best-effort 降级逻辑。

## 测试价值与 TDD

测试保护常量定义收录、真实引用方集合和旧解析缓存失效，均有稳定的可观察结果；复用现有 owner，无测试专用生产接口。

| Owner | 层级 | 断言 |
|---|---|---|
| `JavaConstParserTest` | L1 | 接口字段省略全部或部分修饰符、嵌套接口、注解隐式常量均能识别；普通类可变/实例/对象字段仍排除 |
| `ConstRefIntegrationTest` | L2 | 使用真实解析器和 SQLite，两个隐式 Java 常量改值后命中 Kotlin when 与回调使用方；仅引用未变常量的文件不跟编 |
| `ConstRefCacheDatabaseTest` | L1 | 真实版本 7 空分析记录及其 mtime/checksum 缓存失效；正常 DB 读写和重新打开仍可用 |
| `ConstRefEngineTest` | L2 | 回归编辑态冲刷、缓存复用、影响确认与异常降级等既有行为 |

### Red

修改生产代码前执行：

```bash
./gradlew :main:test \
  --tests 'com.sickworm.intellij.jugg.compiler.constref.JavaConstParserTest' \
  --tests 'com.sickworm.intellij.jugg.compiler.constref.ConstRefIntegrationTest' \
  --tests 'com.sickworm.intellij.jugg.compiler.constref.ConstRefCacheDatabaseTest' \
  --console=plain
```

39 项测试中 5 项失败：接口隐式常量缺失、注解隐式常量缺失、两个 Kotlin 调用方预期非空但实际为空、版本 7 空分析记录未失效、新缓存版本期望 8 但实际为 7；其余 34 项通过。

### Green 与回归

修复后执行：

```bash
./gradlew :main:test \
  --tests 'com.sickworm.intellij.jugg.compiler.constref.JavaConstParserTest' \
  --tests 'com.sickworm.intellij.jugg.compiler.constref.ConstRefIntegrationTest' \
  --tests 'com.sickworm.intellij.jugg.compiler.constref.ConstRefCacheDatabaseTest' \
  --tests 'com.sickworm.intellij.jugg.compiler.constref.ConstRefEngineTest' \
  --console=plain
```

83 项全部通过，无失败、错误或跳过：parser 8 项、integration 8 项、database 23 项、engine 44 项。两处 Kotlin 使用方进入影响结果，仅引用未变常量的文件保持不受影响。生产代码未增加测试注入点，未改动编译部署编排，因此不增加 L3 Flow 场景。

使用修复后编译出的实际解析器再次读取原始 `IFAKtvH5Code.java` 附件，识别到全部 6 个常量，并断言两条变化指令的值为 `10498/10499`。`git diff --check` 通过；本次未新增或修改日志调用。

## 文档与验证边界

- 已同步 `docs/ai_knowledge/03_deploy_const_ref.md` 的收录规则、缓存版本、排查入口和测试 owner。
- 已检查 Wiki 的常量引用原理与跟编能力页面。页面已描述 Java 可内联 `static final` 常量变化会追加编译使用方，本次修复兑现该既有契约；用户操作、前置要求和限制均不变，不扩写 Wiki。
- 使用附件及最小等价 Java/Kotlin 源码验证，未在用户完整工程或设备上重跑编译部署；不宣称真机验证完成。
