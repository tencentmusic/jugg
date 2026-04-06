本次排查总结：ktcompanionext NoSuchMethodError 回归测试

  背景

  排查目标是为 Companion object 扩展函数参数类型变更（Int → Int?）导致的 NoSuchMethodError 编写 RED 失败测试，验证 Jugg
  是否能正确检测调用方需要重新编译。

  排查结论

  Bug 已在 commit 9cf659f9a 修复（2023-12-21），但当时未写测试。

  根本原因（历史）：
  DeployDataGenerator 中存在错误逻辑：
  // 旧代码（有 bug）
  if (className.isInnerClass && !result.isCanHotReload) {
      logger.debug("class $className is inner class, no need to find effected classes")
  } else {
      changedMethodRef.addAll(result.effectMethods)  // ← inner class 会跳过这里
      ...
  }
  Kotlin Companion object 扩展函数编译后的 CompanionExtensionsKt 被识别为 isInnerClass = true，导致其方法变更不会被加入 changedMethodRef，进而调用方
  CompanionExtInvoker 不会被触发重编译。

  修复方式（9cf659f9a）：
  // 修复后：无条件收集变更方法
  changedMethodRef.addAll(result.effectMethods)
  changedFieldRef.addAll(result.deletedFields)
  if (result.isAddedAbstractMethodForNonAbstractClass) {
      changedAbstractClasses.add(newClassNode)
  }

  本次工作产出

  补写了回归测试并提交（commit bceb40c67）：
  - 测试类：DeployDataGeneratorTest.testEffectSourceByCompanionExtParamTypeChange
  - 新增 asset：
    - android_demo_project/.../ktcompanionext/ — 基线 APK 源码（3 个文件）
    - idea/src/test/assets/.../ktcompanionext/CompanionExtensions.kt — 修改版（Int → Int?）
  - 测试状态：GREEN ✅（fix 存在，行为正确）

  下一步建议（用户日志分析）

  当前测试覆盖的是 SQLite 路径（两个类都在 APK 中）。如果用户日志显示 crash 仍然存在，需要关注：
  1. 内存路径（IncrementalDeployDataDatabase）是否也正确处理了该场景
  2. 版本问题：用户使用的 Jugg 版本是否包含 9cf659f9a 的修复
  3. 其他触发路径：是否存在不同的 isInnerClass 判断条件导致新的漏检
