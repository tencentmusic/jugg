<!--
Thanks for contributing to Jugg. Please fill in this template.
See CONTRIBUTING.md / CONTRIBUTING.zh-CN.md.

感谢贡献 Jugg。请填写本模板。
说明见 CONTRIBUTING.md / CONTRIBUTING.zh-CN.md。
-->

## Summary / 变更说明

<!--
What user-visible problem or capability does this change?
Describe the scenario and observable result, not the internal implementation.

改了什么用户可观察问题或能力？请写场景和可观察结果，不要写内部实现。
-->

## Linked issue / 关联 Issue

<!-- Fixes #123 -->

## Type / 类型

<!-- Match the commit prefix. / 与提交前缀保持一致。 -->

- [ ] `[bugfix]` existing behavior is wrong / 既有行为不符合预期
- [ ] `[feature]` new user-visible capability / 新增用户可感知能力
- [ ] `[optimize]` existing behavior is correct, but clearer, more reliable, or easier to use / 原行为正确，但更清晰、更稳或更好用
- [ ] `[refactor]` / `[docs]` / `[test]` / `[other]`

## Verification / 验证

<!--
Every change needs verification evidence that matches the risk.
Automated tests are one kind of evidence, not a requirement for every patch.
Do not run unfiltered `:main:test` or `:idea:test`.

每次改动都要提供与风险匹配的验证证据。自动化测试只是验证方式之一。
禁止无 `--tests` 过滤的全量 `:main:test` / `:idea:test`。

If you added or ran tests, paste the targeted command, for example:
如果你新增或跑了测试，请粘贴定向命令，例如：

./gradlew :main:test --tests "com.sickworm.intellij.jugg.deploy.data.DeployDataGeneratorTest"
./gradlew :idea:test --tests "com.sickworm.intellij.jugg.manager.TopLevelFlowTest"

If the change cannot be asserted automatically without binding private implementation, include:
如果无法在不绑定私有实现的前提下写出有价值的自动化断言，请说明：
- reproduction evidence / 复现证据
- why a test was not added / 未加测试的原因
- what you verified instead / 实际做了哪些替代验证
-->

## Documentation / 文档

- [ ] No user-facing or architecture change / 不涉及用户文档或架构变更
- [ ] Updated user Wiki (`docs/wiki/zh/` source, English pages in sync) / 已更新用户 Wiki（中文为基准，英文同步）
- [ ] Updated maintainer notes (`docs/ai_knowledge`) / 已更新维护者说明

## Checklist / 检查项

- [ ] The pull request is focused: one problem, one fix, one verification story / PR 聚焦：一个问题、一个修复、一套验证说明
- [ ] No secrets, local IDE files, `build/` outputs, or unrelated formatting / 未包含密钥、本地 IDE 文件、`build/` 产物或无关格式化
- [ ] Commit titles follow `[prefix] subject` / 提交标题符合 `[prefix] subject`
