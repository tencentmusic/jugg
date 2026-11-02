# installer 路径判断兼容修复

## 问题与方案

Marketplace 在 263.7031.23 报告 `CopyEmbeddedDistributionPaths.get()` 引用了无法解析的 `IdeInfo`。修复前 `javap` 确认其直接调用 `IdeInfo.getInstance()` 和 `isAndroidStudio()`，调用先于 Kotlin 断言开关检查。

按用户要求，优先反射调用原 API；反射失败时记录 debug 日志，再通过 `ApplicationInfo.getInstance().build.productCode == "AI"` 判断。原 API 正常返回 false 时不触发回退。保留目录查找顺序、开发模式断言和返回路径。

## 验证与结果

- `./gradlew :idea:compileKotlin` 通过。
- `javap -verbose -p` 确认编译产物中 `IdeInfo` 仅剩反射字符串，无 Class/Methodref 静态引用。
- 使用临时 Java fixture 和隔离 URLClassLoader 调用编译产物的公开 `get()`，开启断言。验证原 API 返回 true + IU、返回 false + AI、类缺失 + AI/IU、调用抛错 + AI/IU 共 6 个场景，全部通过；返回路径或 AssertionError 符合预期。
- 不新增仓库自动化测试：此处依赖 IDE 静态 API 与类加载环境，直接单测需要绑定私有方法或引入测试 seam。以编译、字节码和临时隔离环境运行作为替代验证。
- `git diff --check` 通过。目标 263.7031.23 的 Marketplace Verifier 尚未重跑。

## 文档影响

同步 `04_engineering_compat.md`。用户操作入口、支持范围和使用步骤未变化，无需更新 Wiki。
