package com.sickworm.intellij.jugg.compiler.context

import com.intellij.openapi.project.Project
import com.sickworm.intellij.jugg.mock.TestGlobal
import com.sickworm.intellij.jugg.platform.IPlatformApi
import com.sickworm.intellij.jugg.platform.PlatformApi
import org.junit.Assert.assertTrue
import org.junit.Test
import org.mockito.kotlin.mock
import org.mockito.kotlin.whenever

class CompileEnvironmentSourceTest {

    @Test
    fun ideaSource_readsCompileEnvironmentOnEveryCall() {
        TestGlobal.init()
        val originalPlatformApi = PlatformApi.impl
        val project = mock<Project>()
        val platformApi = mock<IPlatformApi>()
        whenever(platformApi.getGradleJdkPath(project, TestGlobal.logger)).thenReturn("/jdk/first", "/jdk/second")
        whenever(platformApi.getAndroidHomePath(TestGlobal.logger)).thenReturn("/android/sdk")
        PlatformApi.impl = platformApi
        try {
            val source = IdeaCompileEnvironmentSource(project)

            val first = source.buildCompileEnv(TestGlobal.logger)
            val second = source.buildCompileEnv(TestGlobal.logger)

            assertTrue(first.contains("JAVA_HOME=/jdk/first"))
            assertTrue(second.contains("JAVA_HOME=/jdk/second"))
        } finally {
            PlatformApi.impl = originalPlatformApi
        }
    }
}
