@file:Suppress("IncorrectParentDisposable")

package com.sickworm.intellij.jugg.gradle

import com.intellij.openapi.module.Module
import com.intellij.openapi.module.ModuleManager
import com.sickworm.intellij.jugg.compiler.JuggCompilerHelper
import com.sickworm.intellij.jugg.gradle.compile.IGradleCompileClient
import com.sickworm.intellij.jugg.gradle.compile.LocalGradleCompileClient
import com.sickworm.intellij.jugg.ide.JuggGradleCompileOptions
import com.sickworm.intellij.jugg.ide.JuggRunConfigurationOptions
import com.sickworm.intellij.jugg.ide.toCompileOptions
import com.sickworm.intellij.jugg.logger.JuggLogger
import com.sickworm.intellij.jugg.manager.changeAndRevert
import com.sickworm.intellij.jugg.mock.*
import com.sickworm.intellij.jugg.project.JuggPathManager
import com.sickworm.intellij.jugg.project.dependency.DependencyDiffResultSet
import org.junit.BeforeClass
import org.junit.Test
import org.mockito.Mockito
import java.io.File
import kotlin.test.assertEquals
import kotlin.test.assertFalse
import kotlin.test.assertTrue

open class LocalGradleCompileClientTest {

    companion object {

        lateinit var juggGradleCompileOptions: JuggGradleCompileOptions
        private lateinit var project: JuggMockProject

        @JvmStatic
        @BeforeClass
        fun beforeClass() {
            project = JuggMockProject(projectInfo.projectRoot)

            val moduleManager = Mockito.mock(ModuleManager::class.java)
            Mockito.doReturn(arrayOf<Module>()).`when`(moduleManager).modules
            project.registerService(ModuleManager::class.java, moduleManager)

            val pathManager = JuggPathManager(projectInfo.projectRoot)
            JuggLogger.register(project, pathManager.logDir)


            val options = JuggRunConfigurationOptions().also {
                it.compileCommand = "./gradlew :app:assembleDebug"
                it.outputApkName = "app-debug.apk"
                it.isRemoteCompile = false
            }
            juggGradleCompileOptions = options.toCompileOptions(pathManager)

            val initGradleFile = File(juggGradleCompileOptions.projectRootPath, juggGradleCompileOptions.initGradleFileRelativePath)
            JuggCompilerHelper::class.java.getResource("/gradle/readProjectInfo.gradle.kts")!!.openStream().use { ins ->
                val text = ins.reader().readText()
                initGradleFile.parentFile.mkdirs()
                initGradleFile.writeText(text)
            }
        }
    }

    @Test
    open fun testCompile() {
        val localClient = LocalGradleCompileClient(project, buildDir, logger)
        localClient.login(juggGradleCompileOptions)
        val remoteCompileResult = localClient.compileAndFetchResult()
        assertTrue(remoteCompileResult.isSuccess)
    }

    @Test
    open fun testCancel() {
        val localClient = LocalGradleCompileClient(project, buildDir, logger)
        localClient.terminalOutputListener = object : IGradleCompileClient.TerminalOutputListener {

            override fun onOutput(line: String, isNeedPrint: Boolean) {
                if (isNeedPrint) {
                    println(line)
                }
                if (line.contains(":preBuild")) {
                    localClient.cancelAction(true)
                }
            }

            override fun onOutputErr(line: String) {
                System.err.println(line)
            }
        }
        localClient.login(juggGradleCompileOptions)
        val remoteCompileResult = localClient.compileAndFetchResult()
        assertFalse(remoteCompileResult.isSuccess)
        assertTrue(remoteCompileResult.isCanceled)
    }

    @Test
    open fun testFetchLibraryChanges() {
        val client = getClient()
        client.login(juggGradleCompileOptions)
        val compileResult = client.compileAndFetchResult()
        assertTrue(compileResult.isSuccess)

        client.fetchLibraryChanges(0).checkChanges(
            hasChanges = false
        )

        var incDeployTimes = 0
        val buildFile = projectInfo.projectRoot.resolve("app/build.gradle")
        // update to 2.8.1
        changeAndRevert(buildFile,
            "com.google.code.gson:gson:2.8.0",
            "com.google.code.gson:gson:2.8.1",
        ) {
            client.fetchLibraryChanges(incDeployTimes).checkChanges(
                hasChanges = true,
                updateLibraries = listOf(
                    "com.google.code.gson:gson:2.8.0" to "com.google.code.gson:gson:2.8.1",
                ),
                fullUpdateLibraries = listOf(
                    "com.google.code.gson:gson:2.8.0" to "com.google.code.gson:gson:2.8.1",
                ),
            )
        }
        // mark as incremental compile
        incDeployTimes++

        // update to 2.8.2
        changeAndRevert(buildFile,
            "com.google.code.gson:gson:2.8.0",
            "com.google.code.gson:gson:2.8.2",
        ) {
            // compare with last incremental build
            client.fetchLibraryChanges(incDeployTimes).checkChanges(
                hasChanges = true,
                updateLibraries = listOf(
                    "com.google.code.gson:gson:2.8.1" to "com.google.code.gson:gson:2.8.2",
                ),
                fullUpdateLibraries = listOf(
                    "com.google.code.gson:gson:2.8.0" to "com.google.code.gson:gson:2.8.2",
                )
            )
        }
        // mark as incremental compile
        incDeployTimes++

        // stay to 2.8.2
        changeAndRevert(buildFile,
            "com.google.code.gson:gson:2.8.0",
            "com.google.code.gson:gson:2.8.2",
        ) {
            // compare with last incremental build
            client.fetchLibraryChanges(incDeployTimes).checkChanges(hasChanges = false)
        }
        // mark as incremental compile
        incDeployTimes++


        // start update second library fastjson
        // keep version unchanged
        changeAndRevert(buildFile,
            "com.google.code.gson:gson:2.8.0",
            "com.google.code.gson:gson:2.8.2",
        ) {
            changeAndRevert(buildFile,
                "com.alibaba:fastjson:2.0.2.android",
                "com.alibaba:fastjson:2.0.3.android",
            ) {
                // compare with last incremental build
                client.fetchLibraryChanges(incDeployTimes).checkChanges(hasChanges = true,
                    updateLibraries = listOf(
                        "com.alibaba:fastjson:2.0.2.android" to "com.alibaba:fastjson:2.0.3.android",
                        "com.alibaba.fastjson2:fastjson2-extension:2.0.2.android" to "com.alibaba.fastjson2:fastjson2-extension:2.0.3.android",
                        "com.alibaba.fastjson2:fastjson2:2.0.2.android" to "com.alibaba.fastjson2:fastjson2:2.0.3.android",
                    ),
                    fullUpdateLibraries = listOf(
                        "com.alibaba:fastjson:2.0.2.android" to "com.alibaba:fastjson:2.0.3.android",
                        "com.alibaba.fastjson2:fastjson2-extension:2.0.2.android" to "com.alibaba.fastjson2:fastjson2-extension:2.0.3.android",
                        "com.alibaba.fastjson2:fastjson2:2.0.2.android" to "com.alibaba.fastjson2:fastjson2:2.0.3.android",
                    ),
                )
            }
        }
        // mark as incremental compile
        incDeployTimes++

        // update together
        changeAndRevert(buildFile,
            "com.google.code.gson:gson:2.8.0",
            "com.google.code.gson:gson:2.8.3",
        ) {
            changeAndRevert(buildFile,
                "com.alibaba:fastjson:2.0.2.android",
                "com.alibaba:fastjson:2.0.4.android",
            ) {
                // compare with last incremental build
                client.fetchLibraryChanges(incDeployTimes).checkChanges(hasChanges = true,
                    updateLibraries = listOf(
                        "com.alibaba:fastjson:2.0.3.android" to "com.alibaba:fastjson:2.0.4.android",
                        "com.alibaba.fastjson2:fastjson2-extension:2.0.3.android" to "com.alibaba.fastjson2:fastjson2-extension:2.0.4.android",
                        "com.alibaba.fastjson2:fastjson2:2.0.3.android" to "com.alibaba.fastjson2:fastjson2:2.0.4.android",
                        "com.google.code.gson:gson:2.8.2" to "com.google.code.gson:gson:2.8.3",
                    ),
                    fullUpdateLibraries = listOf(
                        "com.alibaba:fastjson:2.0.2.android" to "com.alibaba:fastjson:2.0.4.android",
                        "com.alibaba.fastjson2:fastjson2-extension:2.0.2.android" to "com.alibaba.fastjson2:fastjson2-extension:2.0.4.android",
                        "com.alibaba.fastjson2:fastjson2:2.0.2.android" to "com.alibaba.fastjson2:fastjson2:2.0.4.android",
                        "com.google.code.gson:gson:2.8.0" to "com.google.code.gson:gson:2.8.3",
                    ),
                )
            }
        }
        // mark as incremental compile
        incDeployTimes++

        // keep version unchanged
        changeAndRevert(buildFile,
            "com.google.code.gson:gson:2.8.0",
            "com.google.code.gson:gson:2.8.3",
        ) {
            changeAndRevert(buildFile,
                "com.alibaba:fastjson:2.0.2.android",
                "com.alibaba:fastjson:2.0.4.android",
            ) {
                // compare with last incremental build
                client.fetchLibraryChanges(incDeployTimes).checkChanges(hasChanges = false)
            }
        }
    }

    private fun DependencyDiffResultSet?.checkChanges(
        hasChanges: Boolean,
        updateLibraries: List<Pair<String, String?>> = emptyList(),
        fullUpdateLibraries: List<Pair<String, String?>> = emptyList(),
    ) {
        assertTrue(this != null)
        assertEquals(hasChanges, this.hasChanges)
        updateLibraries.sortedBy { it.first }.forEachIndexed { index, it ->
            val sortedUpdatedLibraries = this.diffResultForLastBuild.updatedLibraries.sortedBy { it.oldDependency?.declaration }
            assertEquals(it.first, sortedUpdatedLibraries[index].oldDependency?.declaration)
            assertEquals(it.second, sortedUpdatedLibraries[index].dependency?.declaration)
        }
        assertEquals(updateLibraries.size, this.diffResultForLastBuild.updatedLibraries.size,
            "actual: ${diffResultForLastBuild.updatedLibraries.map { it.dependency?.declaration }}",
        )

        fullUpdateLibraries.sortedBy { it.first }.forEachIndexed { index, it ->
            val sortedUpdatedLibraries = this.diffResultForFullBuild.updatedLibraries.sortedBy { it.oldDependency?.declaration }
            assertEquals(it.first, sortedUpdatedLibraries[index].oldDependency?.declaration)
            assertEquals(it.second, sortedUpdatedLibraries[index].dependency?.declaration)
        }
        assertEquals(fullUpdateLibraries.size, this.diffResultForFullBuild.updatedLibraries.size)

        // just check full build files exist, lastBuild is just for show
        this.diffResultForFullBuild.updatedLibraries.forEach {
            it.dependency?.libraries?.forEach { libraryDependency ->
                assertTrue(libraryDependency.file.exists())
            }
        }
    }

    open fun getClient(): IGradleCompileClient {
        return LocalGradleCompileClient(project, buildDir, logger)
    }
}