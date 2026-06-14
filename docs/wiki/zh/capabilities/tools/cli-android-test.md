# Android Test

`jugg instrument` 用于从 androidTest 源文件锚点运行 class 或 method 级测试。它会复用 Jugg 的编译和部署链路，把 app 与 test APK 更新到设备后再执行 instrumentation。

## 运行入口与支持范围

| 运行入口 | 当前支持情况 | 输入边界 |
|---|---|---|
| 按 androidTest 源文件运行 | 支持 | `--source-path` 必填 |
| 按测试类运行 | 支持 | `--source-path` + `--class` |
| 按测试方法运行 | 支持 | `--source-path` + `--class` + `--method` |
| 指定 instrumentation runner | 支持 | `--runner` |
| 传入 `-e` extras | 支持批量参数 | `--extras 'k=v;k2=v2'` |
| package / regex 作为 Jugg target 入口 | 不支持 | 先用 `sourcePath` 刷新 APK，再按需使用原生 `adb shell am instrument` 做广泛过滤 |

## 命令格式

```text
jugg instrument --source-path app/src/androidTest/kotlin/com/example/FooTest.kt
jugg instrument --source-path app/src/androidTest/kotlin/com/example/FooTest.kt --class com.example.FooTest
jugg instrument --source-path app/src/androidTest/kotlin/com/example/FooTest.kt --class com.example.FooTest --method testSomething
jugg instrument --source-path app/src/androidTest/kotlin/com/example/FooTest.kt --runner androidx.test.runner.AndroidJUnitRunner --extras 'size=large;clearPackageData=true'
```

`sourcePath` 用来解析测试所在 module、目标 test APK、测试类和方法归属。多 test APK 场景必须通过它确定目标，不能只传 package 或正则。

## 前置条件

项目需要已经建立 AndroidTest full-build baseline。可以用：

```text
jugg status --console=json
```

读取 `data.enabledAndroidTest`。如果为 `false`，需要先在 Jugg App Run Configuration 中开启 Android Test / `enableAndroidTest`，执行一次 full build 或 `gradle-build`，再重新检查状态。

> [!IMPORTANT]
> 当 `enabledAndroidTest=false` 时，`instrument` 会返回 `INVALID_PARAMS`，不会自动推断或补建 AndroidTest baseline。

## 运行结果

`instrument` 是构建类命令，会阻塞到终态。一次成功的 `jugg instrument` 表示 app 源码和 androidTest 源码已经被编译并部署到对应 APK，然后执行了 instrumentation。后续如果只需要更大范围的原生测试过滤，可以在 APK 已刷新后使用原生 `adb shell am instrument`。

## 关联能力

- [Application Android Test](../test/application-android-test.md)
- [Library Android Test](../test/library-android-test.md)
- [Test Results UI](../test/test-results-ui.md)
- [Logcat 归因](../test/logcat-attribution.md)
