package com.sickworm.intellij.jugg.project.runtime

import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.sickworm.intellij.jugg.compiler.CompileFile
import com.sickworm.intellij.jugg.compiler.CompileResult
import com.sickworm.intellij.jugg.compiler.CompileTask
import com.sickworm.intellij.jugg.compiler.ICompileContext
import com.sickworm.intellij.jugg.compiler.ICompiler
import com.sickworm.intellij.jugg.compiler.context.CompileContextManager
import com.sickworm.intellij.jugg.compiler.custom.CustomCompilerManager
import com.sickworm.intellij.jugg.compiler.custom.ICompilerCreator
import com.sickworm.intellij.jugg.deploy.IDeployHistoryManager
import com.sickworm.intellij.jugg.mock.TestGlobal
import com.sickworm.intellij.jugg.project.change.IFileChangesHandler
import com.sickworm.intellij.jugg.server.JuggServer
import com.sickworm.intellij.jugg.server.protocols.CustomCompilerInfo
import com.sickworm.intellij.jugg.server.protocols.ModuleCustomConfig
import com.sickworm.intellij.jugg.server.protocols.ProjectCustomConfig
import com.sickworm.intellij.jugg.server.protocols.ServerRule
import org.junit.Assert.assertTrue
import org.junit.Assert.assertFalse
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import org.mockito.kotlin.clearInvocations
import org.mockito.kotlin.doNothing
import org.mockito.kotlin.doThrow
import org.mockito.kotlin.mock
import org.mockito.kotlin.times
import org.mockito.kotlin.verify
import org.mockito.kotlin.whenever
import java.io.File
import java.security.MessageDigest
import java.util.jar.JarEntry
import java.util.jar.JarOutputStream

/** Verifies the project custom-config lifecycle across all runtime collaborators. */
class ProjectCustomConfigurationFlowTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `default update applies immediately while local config keeps priority`() {
        val configDir = temporaryFolder.newFolder("config")
        val defaultConfig = config("default")
        val localConfig = config("local")
        val juggServer = mock<JuggServer>()
        val fileChangesHandler = mock<IFileChangesHandler>()
        val deployHistoryManager = mock<IDeployHistoryManager>()
        val compileContextManager = mock<CompileContextManager>()
        val customCompilerManager = mock<CustomCompilerManager>()
        val manager = ProjectCustomConfigManager(configDir, TestGlobal.logger, juggServer, fileChangesHandler, deployHistoryManager, compileContextManager, customCompilerManager)

        assertTrue(manager.updateDefaultConfig(defaultConfig))
        verifyApplied(defaultConfig, juggServer, fileChangesHandler, deployHistoryManager, compileContextManager, customCompilerManager)

        clearInvocations(juggServer, fileChangesHandler, deployHistoryManager, compileContextManager, customCompilerManager)
        configDir.resolve("custom_config.json").writeText(com.google.gson.Gson().toJson(localConfig))
        assertTrue(manager.refresh())
        verifyApplied(localConfig, juggServer, fileChangesHandler, deployHistoryManager, compileContextManager, customCompilerManager)

        clearInvocations(juggServer, fileChangesHandler, deployHistoryManager, compileContextManager, customCompilerManager)
        configDir.resolve("custom_config.json").delete()
        assertTrue(manager.refresh())
        verifyApplied(defaultConfig, juggServer, fileChangesHandler, deployHistoryManager, compileContextManager, customCompilerManager)
    }

    @Test
    fun `failed config apply retries unchanged config`() {
        val configDir = temporaryFolder.newFolder("retry_config")
        val config = config("retry")
        val fileChangesHandler = mock<IFileChangesHandler>()
        doThrow(IllegalStateException("first apply failed")).doNothing().whenever(fileChangesHandler).updateBuildFileRules(config.buildFileRules, listOf(":retry"))
        val manager = ProjectCustomConfigManager(configDir, TestGlobal.logger, mock(), fileChangesHandler, mock(), mock(), mock())
        configDir.resolve("custom_config.json").writeText(com.google.gson.Gson().toJson(config))

        assertFalse(manager.refresh())
        assertTrue(manager.refresh())

        verify(fileChangesHandler, times(2)).updateBuildFileRules(config.buildFileRules, listOf(":retry"))
    }

    @Test
    fun `custom compiler config change disposes old compiler and loads replacement`() {
        val projectDir = temporaryFolder.newFolder("project")
        val configDir = temporaryFolder.newFolder("compiler_config")
        val customCompilerDir = temporaryFolder.newFolder("compiler_cache")
        val firstJar = createServiceJar("first.jar", FirstCompilerCreator::class.java.name)
        val secondJar = createServiceJar("second.jar", SecondCompilerCreator::class.java.name)
        val juggServer = mock<JuggServer>()
        val customCompilerManager = CustomCompilerManager(projectDir, customCompilerDir, juggServer, TestGlobal.logger)
        val manager = ProjectCustomConfigManager(configDir, TestGlobal.logger, juggServer, mock(), mock(), mock(), customCompilerManager)
        val configFile = File(configDir, "custom_config.json")
        configFile.writeText(com.google.gson.Gson().toJson(config("first", CustomCompilerInfo(firstJar.name, firstJar.absolutePath, firstJar.md5()))))
        manager.refresh()
        customCompilerManager.init(mock<ICompileContext>())
        assertTrue(customCompilerManager.getCustomCompilers().single() is FirstCompiler)

        configFile.writeText(com.google.gson.Gson().toJson(config("second", CustomCompilerInfo(secondJar.name, secondJar.absolutePath, secondJar.md5()))))
        configFile.setLastModified(System.currentTimeMillis() + 2_000L)
        manager.refresh()

        assertTrue(FirstCompilerCreator.lastCompiler!!.disposed)
        assertTrue(customCompilerManager.getCustomCompilers().single() is SecondCompiler)
        customCompilerManager.close()
        assertTrue(SecondCompilerCreator.lastCompiler!!.disposed)
    }

    private fun verifyApplied(
        config: ProjectCustomConfig,
        juggServer: JuggServer,
        fileChangesHandler: IFileChangesHandler,
        deployHistoryManager: IDeployHistoryManager,
        compileContextManager: CompileContextManager,
        customCompilerManager: CustomCompilerManager,
    ) {
        val moduleCustomConfigs = config.moduleCustomConfigs!!
        verify(juggServer).updateServer(config.servers)
        verify(fileChangesHandler).updateBuildFileRules(config.buildFileRules, moduleCustomConfigs.map { it.moduleStdPath })
        verify(deployHistoryManager).updateDontFilterIgnoredFileRules(config.dontFilterIgnoredFileRules)
        verify(compileContextManager).updateCustomClasspath(moduleCustomConfigs)
        verify(customCompilerManager).updateCustomCompilers(config.customCompilers)
    }

    private fun config(name: String, customCompilerInfo: CustomCompilerInfo = CustomCompilerInfo("$name.jar", "libs/$name.jar", "md5")): ProjectCustomConfig {
        return ProjectCustomConfig(
            servers = listOf(ServerRule("https://$name.example.com", null)),
            buildFileList = emptyList(),
            buildFileRules = listOf("$name.gradle"),
            dontFilterIgnoredFileRules = listOf("$name/**"),
            moduleCustomConfigs = listOf(ModuleCustomConfig(":$name", listOf("libs/$name.jar"), emptyList(), false)),
            customCompilers = listOf(customCompilerInfo),
            embeddedApksSearchRules = listOf("assets/$name.apk"),
        )
    }

    private fun createServiceJar(name: String, creatorName: String): File {
        val file = temporaryFolder.newFile(name)
        JarOutputStream(file.outputStream()).use { output ->
            output.putNextEntry(JarEntry("META-INF/services/${ICompilerCreator::class.java.name}"))
            output.write(creatorName.toByteArray())
            output.closeEntry()
        }
        return file
    }

    private fun File.md5(): String {
        val digest = MessageDigest.getInstance("MD5").digest(readBytes())
        return digest.joinToString("") { "%02x".format(it) }
    }

    class FirstCompilerCreator : ICompilerCreator {
        override fun create(context: ICompileContext, parent: Disposable): ICompiler {
            return FirstCompiler().also {
                lastCompiler = it
                Disposer.register(parent, it)
            }
        }

        companion object {
            var lastCompiler: FirstCompiler? = null
        }
    }

    class SecondCompilerCreator : ICompilerCreator {
        override fun create(context: ICompileContext, parent: Disposable): ICompiler {
            return SecondCompiler().also {
                lastCompiler = it
                Disposer.register(parent, it)
            }
        }

        companion object {
            var lastCompiler: SecondCompiler? = null
        }
    }

    class FirstCompiler : TestCompiler()

    class SecondCompiler : TestCompiler()

    abstract class TestCompiler : ICompiler {
        var disposed = false
        override val supportedTypes: List<CompileFile.Type> = emptyList()
        override fun compile(task: CompileTask): CompileResult = CompileResult(task, emptyList(), emptyList())
        override fun dispose() {
            disposed = true
        }
    }
}
