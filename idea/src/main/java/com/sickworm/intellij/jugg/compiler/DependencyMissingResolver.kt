package com.sickworm.intellij.jugg.compiler

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.logger.TimeLogger
import com.sickworm.intellij.jugg.project.CompileContextManager
import com.sickworm.intellij.jugg.project.dependency.GradleProjectInfoLocalFetchManager


class DependencyMissingResolver(
    private val compileContextManager: CompileContextManager,
    private val gradleProjectInfoLocalFetchManager: GradleProjectInfoLocalFetchManager,
    private val logger: Logger
) : IDependencyMissingResolver {

    private var lastCheckTime = 0L

    /**
     * @return is can retry
     */
    override fun resolve(compileResult: CompileResult): Boolean {
        if (compileResult.isAllSuccess) {
            return false
        }
        val isNeedCheck = System.currentTimeMillis() - lastCheckTime > CHECK_DURATION
        if (!isNeedCheck) {
            logger.debug("DependencyMissingResolver: Skip check.")
            return false
        }

        TimeLogger.start("DependencyMissingResolver.resolve")
        var isMissingDependency = false
        compileResult.details.forEach root@{ detail ->
            if (!detail.isFailed) {
                return@root
            }
            detail.getFailureOrNull()?.errors?.forEach { error ->
                val isMatchKeyword = keywords.any { error.second.contains(it) }
                if (isMatchKeyword) {
                    isMissingDependency = true
                    return@root
                }
            }
        }
        if (!isMissingDependency) {
            logger.debug("DependencyMissingResolver: error log show no missing dependency.")
            return false
        }
        logger.debug("DependencyMissingResolver: error log shows may has missing dependency.")
        lastCheckTime = System.currentTimeMillis()

        var isUpdate = compileContextManager.updateCompileContext(isAfterSync = false) {
            gradleProjectInfoLocalFetchManager.runUpdateIfNeeded(isForce = true)
        }
        logger.debug("DependencyMissingResolver: updateCompileContext isUpdate=$isUpdate")
        if (!isUpdate) {
            isUpdate = compileContextManager.triggerMerge()
            logger.debug("DependencyMissingResolver: triggerMerge isUpdate=$isUpdate")
        }
        TimeLogger.end("DependencyMissingResolver.resolve", logger)
        return isUpdate
    }

    companion object {

        private const val CHECK_DURATION = 2 * 3600 * 1000L // 2 hours

        private val keywords = listOf(
            "不存在", "找不到", "unresolved reference", "cannot find symbol", "cannot resolve symbol",
        )
    }
}