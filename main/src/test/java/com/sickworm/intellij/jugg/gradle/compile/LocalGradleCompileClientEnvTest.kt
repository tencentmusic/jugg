package com.sickworm.intellij.jugg.gradle.compile

import com.intellij.openapi.project.Project
import com.sickworm.intellij.jugg.mock.TestGlobal
import com.sickworm.intellij.jugg.platform.IPlatformApi
import com.sickworm.intellij.jugg.platform.PlatformApi
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever
import kotlin.test.assertEquals

class LocalGradleCompileClientEnvTest {

    @Test
    fun `build compile env should use platform environment and configured tool homes`() {
        val logger = TestGlobal.logger
        val originalPlatformApi = PlatformApi.impl
        val platformApi = mock<IPlatformApi>()
        val project = mock<Project>()
        whenever(platformApi.getEnvironmentVariables(logger)).thenReturn(linkedMapOf(
            "PATH" to "/shell/node/bin:/usr/bin",
            "JAVA_HOME" to "/shell/jdk",
            "ANDROID_HOME" to "/shell/android-sdk",
            "CUSTOM_ENV" to "value",
        ))
        whenever(platformApi.getGradleJdkPath(project, logger)).thenReturn("/gradle/jdk")
        whenever(platformApi.getAndroidHomePath(logger)).thenReturn("/ide/android-sdk")

        try {
            PlatformApi.impl = platformApi

            val result = LocalGradleCompileClient.buildCompileEnv(project, logger)
                .associate { entry -> entry.substringBefore('=') to entry.substringAfter('=') }

            assertEquals("/shell/node/bin:/usr/bin", result["PATH"])
            assertEquals("value", result["CUSTOM_ENV"])
            assertEquals("/gradle/jdk", result["JAVA_HOME"])
            assertEquals("/ide/android-sdk", result["ANDROID_HOME"])
        } finally {
            PlatformApi.impl = originalPlatformApi
        }
    }
}
