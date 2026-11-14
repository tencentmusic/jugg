# 修复 Android 17 上 JVMTI agent 启动时 abort

## 问题与依据

Pixel 9（Android 17 Beta，API 37，DP11.261016.003）部署 HOT_FIX 后，App 每次冷启动都在 startup agent 中 SIGABRT。崩溃栈：`RetransformClasses` → `Agent_ClassFileLoadHook` → `abort`，发生在 `Load instrument jar` 之后、第一个 transform（`android/app/Instrumentation`）之前。不经 Android Studio、只把 agent 放进 `code_cache/startup_agents` 也必现。

从设备拉取 `/system/framework/framework.jar` 检查：其 dex 为 `dex\n041` 容器格式，header_size 为 0x78，多个 dex 共用一个容器，string_ids_off、map_off 等偏移都相对容器起点。Jugg 内置 slicer 的 `Reader::ValidateHeader` 要求 `header_size == sizeof(dex::Header)`（0x70），`SLICER_CHECK` 失败直接 `abort()`。

## 实施范围

- 移植 AOSP `platform/tools/dexter` 提交 68fef98（Add dex container support to dexter），只改 `jvmti_agent/src/main/cpp/dexer/` 下的 `slicer/dex_format.h`、`dex_format.cc`、`reader.cc`、`writer.cc`。
- Reader 接受 0x70 和 0x78 两种 header，遇到容器时把解析基址退回容器起点；Writer 按 dex 版本写出对应 header，v41 输出单 dex 容器。
- 保持 Jugg 现有的 `SLICER_CHECK` 宏写法，不引入上游 `SLICER_CHECK_EQ` 等新宏；hook 列表与降级逻辑不变。
- THIRD_PARTY_NOTICES 注明移植来源。

## 验证

- 构建：NDK 28.2.13676358 + CMake 3.18.1，arm64-v8a，`AGENT_VERSION_ARG=1.0.90`（与设备上 instruments jar 版本一致；1.0.90 至当前 main 的 agent native 与 instrument 代码无差异）。
- 设备：Pixel 9，Android 17 Beta（API 37）。debuggable App，把 agent 拷到 `code_cache/startup_agents/1.0.90-jugg_jvmti_agent.so` 后冷启动。
- 修复前：原版 1.0.90 agent 在 `Load instrument jar` 后立即 SIGABRT。
- 修复后：Instrumentation、AppComponentFactory、ResourcesManager（两种签名）、ActivityThread、ClassLoader、ContextImpl、LoadedApk、AssetManager 共 9 次 retransform 全部成功，日志 `Finished instrumenting`；App 主进程和子进程正常运行，无 Fatal signal。
- 未验证：通过插件完整走一次 Java/Kotlin 增量 HOT_FIX；Android 14 及以下设备回归（这些系统的 dex 是 0x70 header，走原有路径）；`./gradlew :idea:buildPlugin`（本机缺 NDK 27.0.12077973 与 CMake 3.22.1，交由 CI）。
