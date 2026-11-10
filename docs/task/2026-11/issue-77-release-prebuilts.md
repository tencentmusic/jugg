# Issue 77：更新各平台 inclink 预编译工具

Purpose: Deliver the retained-resource linking fix through packaged native tools.
Decision: Rebuild macOS Universal, Linux x86_64 and Windows x86_64 Release binaries from current maintained source; store all three with Git LFS.
Impact: Existing tool paths and version metadata remain compatible with the runtime resource loader.

## 构建与验证

复用 scripts/dependencies.json 锁定的依赖及既有构建脚本。macOS 使用 Xcode 工具链，Linux 和 Windows 使用已有 Zig 交叉工具链与 host protoc。Release 产物移除调试信息，不提交 dSYM。

验证覆盖 macOS Universal 发布检查、两个架构的普通资源 smoke 和悬空引用 daemon 回归、Linux ELF / Windows PE 格式检查、Git LFS 指针与内容完整性，以及 Gradle 资源 JAR 中三份工具的 SHA-256 对照。

Linux 和 Windows 在 macOS 上仅取得构建与目标格式证据，未在相应系统执行运行时测试。生产逻辑未在本次改动中修改，沿用前项源码任务的 46 个 native 单元测试证据。

## 结果

- 三个平台 Release 构建成功，保留 `2.19.16` 文件名及原有版本输出元数据。
- macOS 发布门禁通过：Universal x86_64 / arm64，仅依赖系统运行库，无旁置 dSYM。
- macOS x86_64、arm64 及 Universal 的 smoke 与各 3 个悬空引用 daemon 回归全部通过。
- Linux 为 x86-64 ELF，Windows 为 x86-64 PE32+；两者均确认没有 `.debug_` 调试节。
- Git index 中三份文件均为有效 LFS 指针，OID 与工作区文件及共享 Git LFS 对象 SHA-256 一致。
- 使用 JDK 17 执行 `./gradlew :aapt2-inclink:jar` 成功，LFS guard 执行成功；JAR 内三份资源逐项 SHA-256 与新二进制一致。
- `git diff --check` 通过。未推送远程。

| 平台 | SHA-256 |
|---|---|

| darwin | `cf0198a16fdafa95542fb15716b44256ffe8d29d8641d2918cd3838be870d8fb` |
| linux | `882ea64187324ebd4d7e4ea8130d19003bc2343af1e9554bf62fa170c6147871` |
| windows | `69462cf3c153ed0e50b6defef24471c179df20cfa95def05a044cb26f06a8fbd` |
