# 将 main 合入 develop/4.0

**Purpose** — Integrate the 25 main commits through 0ab9a00dc into fbbf58e9c.
**Decision** — Preserve the develop host lifecycle and release line, port the incoming CI and compiler behavior, and retain English maintainer documentation.
**Impact** — Both histories remain reachable. The merged runtime uses agent bundle 1.0.94.

## 冲突记录

| 冲突位置 | 原因及解决办法 |
| --- | --- |
| AGENTS.md | develop 已将规则迁为英文，main 在中文规则中新增 CI 提交域。保留英文规则，将 CI 编排域同步至中英文贡献指南。 |
| 根 build.gradle、四份 change_log | 两条发布线修改版本和首条日志。保留插件 4.0.6，在其后合入 main 3.6.9 的中英文发布记录。两边 agent 内容不同，合并后的设备缓存使用独立版本 1.0.94。 |
| cmdline-distribution.gradle、demo README 和四个旧 shell 脚本、demo_release/README.md | develop 调整独立分发的启动器，main 将 debug/release 脚本迁入统一的 Python demo。保留 standalone 启动器和统一 demo 目录，移除旧脚本，打包一份示例工程，排除本地缓存和服务数据。 |
| CmdLineContextManager、CmdLineTest | develop 迁移包名及任务执行器，main 新增按需 APK 初始化和 CI 测试。使用当前 Host 生命周期，变更查询只读项目模型，同时保留构建路径的 APK 初始化。 |
| ConstRefEngine、DeployFileManager | develop 改为实例级任务执行器，main 新增 CI 常量引用索引、并发分析和可选共享指纹库。保留任务执行器归属，接入 CI 引擎并在共享指纹库存在时执行其清理。 |
| ReadProjectInfoGradleCompatTestBase | develop 移动项目模型等类型，main 扩充 Gradle 测试夹具。采用现行包名并保留新的测试能力。 |
| docs/ai_knowledge/ 下十个文件 | develop 已迁到 docs/ai/，main 在旧路径更新事实。保留旧路径的删除状态；逐文件落点见[语言审查记录](main-integration-language-review.md)。 |

## 自动合并与独立审查

- 编译与部署审查修复任务执行器旧调用、源码目录测试包名和 CI 与 IDE 宿主接线。
- CI 审查修复候选提交解析，JSON 对象中的字段名和其他元数据不再参与 Git 祖先匹配。样本脚本在失败查询后曾复用上轮结果，现按返回码和本轮结果选路，并对 Unknown 输入默认使用 Gradle。
- Wiki 审查修复项目部署前缀下旧路由寻址、404 首页地址、重复生成重定向，以及文档与 UI 的大小写差异。
- CLI 生命周期回归检查发现基线扫描会等待未结束的子任务，现随宿主释放任务。修正普通依赖重定位误写入注解处理依赖，并使用 JSON 序列化处理文件路径中的控制字符。CLI 测试改用独立的 settings 文件，适配当前分支不可变的 rootDir。

## 验证

- JDK 17 下 142 项核心测试、2 项 IDE 测试通过，覆盖常量引用、工程脚本、源码根目录和增量 DEX 中的 Kotlin 模块方法名。
- 14 项 CLI/standalone 定向测试和 17 项 Python demo 测试通过，包含真实 SQLite 常量索引的发布和任务退出。检查 installDist 的启动器、统一 demo 和工程 ZIP。
- Wiki 结构及生产首页构建通过，129 条重定向规则的站内目标可达。插件保持 4.0.6，中英文 4.0.6、4.0.0、3.6.9 的 YAML 版本及各自条目数一致。
- Gradle 7.3.3 和 9.2.1 的五项兼容性回归均通过，无跳过用例。
