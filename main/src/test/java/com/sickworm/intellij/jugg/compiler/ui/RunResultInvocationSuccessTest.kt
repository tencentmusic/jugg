package com.sickworm.intellij.jugg.compiler.ui

import org.junit.Assert
import org.junit.Test

/**
 * Tests for [RunResult.isInvocationSuccess] to ensure compile-only (isSkipDeploy=true) scenarios
 * return success when compile succeeds, even if isDeploySuccess is false.
 */
class RunResultInvocationSuccessTest {

    @Test
    fun `incremental compile success with skip deploy returns success`() {
        val result = RunResult(
            isGradleCompile = false,
            isCompileSuccess = true,
            isDeploySuccess = false,
            isCancel = false,
            isNeedResetHasRun = true,
        )
        Assert.assertTrue(result.isInvocationSuccess(isSkipDeploy = true))
    }

    @Test
    fun `incremental compile success with deploy returns success`() {
        val result = RunResult(
            isGradleCompile = false,
            isCompileSuccess = true,
            isDeploySuccess = true,
            isCancel = false,
        )
        Assert.assertTrue(result.isInvocationSuccess(isSkipDeploy = false))
    }

    @Test
    fun `incremental compile success but deploy failed returns failure`() {
        val result = RunResult(
            isGradleCompile = false,
            isCompileSuccess = true,
            isDeploySuccess = false,
            isCancel = false,
        )
        Assert.assertFalse(result.isInvocationSuccess(isSkipDeploy = false))
    }

    @Test
    fun `incremental compile failed with skip deploy returns failure`() {
        val result = RunResult(
            isGradleCompile = false,
            isCompileSuccess = false,
            isDeploySuccess = false,
            isCancel = false,
        )
        Assert.assertFalse(result.isInvocationSuccess(isSkipDeploy = true))
    }

    @Test
    fun `gradle compile success returns success`() {
        val result = RunResult(
            isGradleCompile = true,
            isCompileSuccess = true,
            isDeploySuccess = false,
            isCancel = false,
        )
        Assert.assertTrue(result.isInvocationSuccess(isSkipDeploy = false))
    }

    @Test
    fun `gradle compile failed returns failure`() {
        val result = RunResult(
            isGradleCompile = true,
            isCompileSuccess = false,
            isDeploySuccess = false,
            isCancel = false,
        )
        Assert.assertFalse(result.isInvocationSuccess(isSkipDeploy = false))
    }
}
