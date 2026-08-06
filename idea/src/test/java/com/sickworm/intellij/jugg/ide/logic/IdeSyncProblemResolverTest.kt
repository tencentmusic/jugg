package com.sickworm.intellij.jugg.ide.logic

import com.intellij.ide.util.PropertiesComponent
import com.intellij.mock.MockProject
import com.sickworm.intellij.jugg.logger.JuggLogger
import com.sickworm.intellij.jugg.mock.DummyPropertiesComponent
import com.sickworm.intellij.jugg.mock.TestGlobal
import org.junit.BeforeClass
import org.junit.Test
import java.nio.file.Files
import kotlin.test.assertFalse
import kotlin.test.assertTrue

class IdeSyncProblemResolverTest {

    companion object {
        @BeforeClass
        @JvmStatic
        fun initTestEnv() {
            TestGlobal.init()
        }
    }

    @Test
    fun `sync success should disable full build fallback for current IDE version`() {
        val projectDir = Files.createTempDirectory("ide-sync-problem-resolver-test").toFile()
        val project = object : MockProject(null, {}) {
            override fun getBasePath(): String = projectDir.absolutePath
        }.apply {
            registerService(PropertiesComponent::class.java, DummyPropertiesComponent())
        }
        JuggLogger.register(project, projectDir.resolve("logs"))
        try {
            val resolver = IdeSyncProblemResolver(project)

            assertTrue(resolver.isNeedSyncAfterBuild())

            resolver.onIdeSyncSucceeded()

            assertFalse(resolver.isNeedSyncAfterBuild())
        } finally {
            JuggLogger.unregister(project)
            project.dispose()
            projectDir.deleteRecursively()
        }
    }
}
