# 公共工具模块（Utilities）

> 最后核对：2026-02-23  
> 一致性规则：文档与代码冲突时，以代码为准。

---

## 1. 文档定位

本页汇总 `main` 中与主流程协作的公共能力模块，便于快速定位复用点。

---

## 2. 模块速览

| 模块 | 目录 | 关键类 | 作用 |
|------|------|--------|------|
| aapt2 | `main/.../aapt2/` | `Aapt2DaemonInvoker` | aapt2 守护进程调用与结果封装 |
| apk | `main/.../apk/` | `ApkFileModifier`, `ApkInfoReader`, `ResourceApkModifier` | APK 读取、修改、签名相关辅助 |
| git | `main/.../git/` | `GitManager`, `FileMatcher` | Git 变更识别 |
| logger | `main/.../logger/` | `JuggLogger`, `FileLogger`, `TimeLogger` | 统一日志与耗时埋点 |
| server | `main/.../server/` | `JuggServer`, `JuggRemoteCompileApplier` | 远端能力、版本检测、下发支持 |
| platform | `main/.../platform/` | `PlatformApi` | 平台能力注入抽象 |
| ide bean/logic (core side) | `main/.../ide/` | `JuggSettings`, `JuggGradleCompileOptions` | 跨层配置与运行参数模型 |

---

## 3. 复用建议

- 需要日志：优先用 `JuggLogger` / `TimeLogger`，避免散落 `println`。  
  `FileLogger` 主日志文件为 `build/jugg/log/compile_*.log`，`compile_latest.log` / `compile_latest-1.log` 为 best-effort 快捷入口。  
- 需要 APK 变更：优先复用 `ApkFileModifier` 与 `ResourceApkModifier`。  
- 需要设备平台能力：经 `PlatformApi` 获取，避免直接耦合 IDE 实现。  
- 需要 Git 变更判断：先看 `GitManager` 与 `WorktreeFileRepository`。

---

## 4. 常见问题定位

- “日志看不到关键阶段耗时”：检查 `TimeLogger.start/end` 是否成对。  
- “APK 修改后无效”：检查输出文件替换与签名链路。  
- “平台 API 在测试环境报错”：检查 `platform_compat` 对应桩。

---

## 5. 关联文档

- 编译：`02_compile_core.md`
- 部署：`03_deploy_core.md`
- 兼容层：`04_engineering_compat.md`
