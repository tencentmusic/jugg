# KAPT 长日志解析性能修复

## 问题与证据

2026-10-13，JOOX_Android_2 的 page-main 模块 ARouter 生成阶段耗时 151.40 秒。现场日志为 `compile_2026-10-13_14-39-02.0.log`，KAPT 初始分析 2.800 秒、stub 编译 1.102 秒、Java stub 转换 0.129 秒、注解处理 55.900 秒，三个 ARouter processor 自身累计约 28 毫秒。

`KotlinCompilerOutputParser` 对每行使用未锚定的 `(.*):?(logging|warning|error|output|exception):(.*)`。无标签的长 classpath/参数行导致平方级回溯，同步阻塞编译器日志回调；部分输出在 KAPT 子阶段计时之外，不能只从 55.900 秒中扣除日志耗时。单行真实 classpath 为 51,949 字符，原正则独立对照耗时 36.821 秒。

## 最小修复

直接搜索有限的消息标签，取最后一个匹配，保留原贪婪前缀选择最后一个标签的分类行为。保留全部日志、错误诊断、异常识别和产物映射，不改变 KAPT 输入、processor、verbose、缓存及生成流程。

## 验证

测试价值门禁通过：长行及时处理和诊断/产物映射是可观察的解析契约，owner 为 `KotlinCompilerOutputParserTest`（L1），无需生产测试 seam。原实现先运行五项定向回归，长行用例超过 5 秒失败，其余四项通过；修复后长行用例约 7 毫秒。

最终命令使用本机 JDK 17 和 Android SDK 环境，不修改构建配置：

```shell
env ANDROID_HOME=/Users/wormchen/Library/Android/sdk ./gradlew \
  -Dorg.gradle.java.home=/Users/wormchen/Library/Java/JavaVirtualMachines/jbr-17.0.12/Contents/Home \
  :main:test --tests '*KotlinCompilerOutputParserTest' \
  --tests '*ComposeResourceGeneratorBridgeTest' :idea:compileKotlin
```

12 项定向回归全部通过，`idea:compileKotlin` 通过。首次默认 JDK 21 构建在 JVMTI D8 步骤失败，切换本机已有 JDK 17 后通过；补充 Compose 回归首次因缺少 `ANDROID_HOME` 失败，明确 SDK 环境后通过。未修改这些外部失败的实现。

现场 KAPT 重放使用 Kotlin 2.0.21、78 个 Kotlin 和 21 个 Java 业务文件，以及日志中的插件、classpath 和 processor 参数；仅将所有生成目录重定向到临时目录。使用实际修复后的解析器及同一 compiler invocation，保留 verbose 输出，三次退出码均为 OK，各生成 7 个 Java 文件，文件内容完全一致：

| 次数 | 整体 KAPT invocation | 注解处理 |
| --- | --- | --- |
| 1 | 5.676 秒 | 0.877 秒 |
| 2 | 2.598 秒 | 0.524 秒 |
| 3 | 2.472 秒 | 0.490 秒 |

另外将旧解析器编译到独立临时目录，在相同 JDK 21、源码、compiler classpath、参数和 quiet logger 下启动新 JVM 做对照：整体 KAPT invocation 105.646 秒，注解处理 47.685 秒，退出码 OK。对照与修复首轮都启动新 compiler，整体相差 99.970 秒、注解处理相差 46.808 秒；旧、新解析器生成的 7 个 Java 文件逐文件 SHA-256 完全一致，确认收益来自解析修复。

首轮与后续复用 compiler classloader 的轮次分别报告，不将重放时间直接等同于 Android Studio 中完整增量编译/部署时间。quiet logger 和宿主环境与原 IDE 有差异，因此 151.40 秒现场结果只作为问题证据，性能对照采用上述 105.646 秒基线，不宣称 IDE 实测已变为 5.676 秒。临时重放使用实际生产解析器与 compiler invocation，不修改 JOOX 源码和基线产物。

检查了中英文 Wiki 的编译能力和运行缓慢排查页面；本次只修复内部日志解析，没有改变其能力与操作说明，无需修改 Wiki。
