package com.sickworm.intellij.jugg.compiler

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.JuggException
import com.sickworm.intellij.jugg.gradle.compile.GradleCompileResult
import com.sickworm.intellij.jugg.gradle.compile.IGradleCompileClient
import com.sickworm.intellij.jugg.ide.bean.JuggGradleCompileOptions
import com.sickworm.intellij.jugg.ide.bean.SyncMode
import com.sickworm.intellij.jugg.project.dependency.DependencyDiffResultSet
import com.sickworm.intellij.jugg.project.info.ModuleBuildPathInfo
import com.sickworm.intellij.jugg.project.runtime.LocalClasspathStoragePathManager
import org.junit.Rule
import org.junit.Test
import org.junit.rules.TemporaryFolder
import java.io.File
import kotlin.test.assertFalse
import kotlin.test.assertNull
import kotlin.test.assertSame
import kotlin.test.assertTrue

class JuggGradleCompileTaskTest {

    @get:Rule
    val temporaryFolder = TemporaryFolder()

    @Test
    fun `login failure returns failed and releases resources`() {
        val parser = object : IGradleCompileClient.TerminalOutputListener {
            override fun onOutput(line: String, isNeedPrint: Boolean) = Unit
            override fun onOutputErr(line: String) = Unit
        }
        val client = FailingCompileClient()
        var cancelListener: (() -> Unit)? = null
        val uiHandler = object : CompileUiHandler by CompileUiHandler.DEFAULT {
            override fun createOutputParser(): IGradleCompileClient.TerminalOutputListener = parser
            override fun listenCancelAction(listener: (() -> Unit)?) {
                cancelListener = listener
            }
        }

        val result = JuggGradleCompileTask(
            client,
            createOptions(temporaryFolder.newFolder("project")),
            uiHandler,
            isOnlyFetchResult = false,
            Logger.getInstance("JuggGradleCompileTaskTest"),
        ).run()

        assertFalse(result.isSuccess)
        assertFalse(result.isCanceled)
        assertTrue(result.failedReason.orEmpty().contains("non-interactive"))
        assertTrue(client.isDisposed)
        assertSame(IGradleCompileClient.TerminalOutputListener.DEFAULT, client.terminalOutputListener)
        assertNull(cancelListener)
    }

    private fun createOptions(projectDir: File): JuggGradleCompileOptions {
        return JuggGradleCompileOptions(
            projectRootPath = projectDir.absolutePath,
            localClasspathStoragePath = LocalClasspathStoragePathManager(File(projectDir, "build/jugg/classpath")),
            initGradleFilePath = File(projectDir, ".gradle/jugg/readProjectInfo.gradle.kts").absolutePath,
            compileCommand = "./gradlew :app:assembleDebug",
            outputApkName = "app/build/outputs/apk/debug/*.apk",
            isRemoteCompile = false,
            isSyncAllProjects = false,
            remoteSshUser = "",
            remoteSshPassword = "",
            remoteSshIp = "",
            remoteSshPort = 22,
            localToRemoteIftConfigName = "",
            localToRemoteSyncPath = "",
            remoteSyncPath = "",
            remoteToLocalIftConfigName = "",
            remoteToLocalSyncPath = "",
            httpProxyIp = "",
            httpProxyPort = 0,
            syncMode = SyncMode.IFT,
            environmentVariables = "",
        )
    }

    private class FailingCompileClient : IGradleCompileClient {
        override var terminalOutputListener = IGradleCompileClient.TerminalOutputListener.DEFAULT
        var isDisposed = false

        override fun login(juggGradleCompileOptions: JuggGradleCompileOptions) {
            throw JuggException(
                "Standalone Runtime is non-interactive. Configure SSH credentials or authenticate iFT before retrying."
            )
        }

        override fun compileAndFetchResult(isOnlyFetchResult: Boolean): GradleCompileResult =
            error("compile should not run after login failure")

        override fun fetchClasspathResult(buildDirs: List<ModuleBuildPathInfo>): File? = null
        override fun fetchLibraryChanges(incDeployTimes: Int): DependencyDiffResultSet? = null
        override fun cancelAction(isByUser: Boolean) = Unit
        override fun dispose() {
            isDisposed = true
        }
    }
}
