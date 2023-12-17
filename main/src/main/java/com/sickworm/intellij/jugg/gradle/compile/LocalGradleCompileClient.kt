package com.sickworm.intellij.jugg.gradle.compile

import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.module.ModuleManager
import com.intellij.openapi.project.Project
import com.intellij.openapi.projectRoots.JavaSdk
import com.intellij.openapi.projectRoots.Sdk
import com.intellij.openapi.roots.ModuleRootManager
import com.sickworm.intellij.jugg.compiler.ModuleBuildPathInfo
import com.sickworm.intellij.jugg.deploy.run.AsDeployerCompat
import com.sickworm.intellij.jugg.compiler.isWindows
import com.sickworm.intellij.jugg.ide.JuggGradleCompileOptions
import com.sickworm.intellij.jugg.logger.JuggLogger
import com.sickworm.intellij.jugg.project.CompileContextManager
import com.sickworm.intellij.jugg.project.JuggInternalException
import java.io.File
import java.io.IOException
import java.io.PrintStream


class LocalGradleCompileClient(
    private val project: Project,
    private val logger: Logger = JuggLogger.getInstance(project, "LocalGradleCompileClient"),
) : IGradleCompileClient {

    private var juggGradleCompileOptions: JuggGradleCompileOptions? = null
    @Volatile
    private var currentRunningProcess: Process? = null

    override var terminalOutputListener = IGradleCompileClient.TerminalOutputListener.DEFAULT

    private var gradleJdkPath: String? = null
    private var androidHomePath: String? = null

    override fun login(juggGradleCompileOptions: JuggGradleCompileOptions) {
        // no need to login
        this.juggGradleCompileOptions = juggGradleCompileOptions

        val javaHome = System.getenv("JAVA_HOME")
        logger.debug("JAVA_HOME: $javaHome")

        gradleJdkPath = AsDeployerCompat.getModuleManager(project).modules.firstNotNullOfOrNull { module ->
            val moduleRootManager = ModuleRootManager.getInstance(module)
            val jdk: Sdk = moduleRootManager.sdk ?: return@firstNotNullOfOrNull null
            if (jdk.sdkType != JavaSdk.getInstance()) {
                return@firstNotNullOfOrNull null
            }
            if (jdk.homePath == null) {
                return@firstNotNullOfOrNull null
            }
            logger.debug("found gradleJdkPath in module: ${module.name}, path: ${jdk.homePath}")
            return@firstNotNullOfOrNull jdk.homePath!!
        }
        if (gradleJdkPath == null) {
            logger.debug("can't find gradleJdkPath in modules, use JAVA_HOME $javaHome instead")
            gradleJdkPath = javaHome
        }

        val androidHome = System.getenv("ANDROID_HOME")
        logger.debug("ANDROID_HOME: $androidHome")
        androidHomePath = CompileContextManager.getAndroidSdkRootDir(logger)?.absolutePath
        if (androidHomePath == null) {
            logger.debug("can't find androidHomePath in modules, use ANDROID_HOME $androidHome instead")
            androidHomePath = androidHome
        } else {
            logger.debug("found androidHomePath $androidHomePath")
        }
    }

    override fun compileAndFetchResult(): GradleCompileResult {
        isCanceled = false
        val juggGradleCompileOptions = juggGradleCompileOptions ?: throw JuggInternalException.notLoginYet()

        val compileProjectCommand = CompileProjectCommand(juggGradleCompileOptions.compileCommand, project.basePath!!)
        val compileProjectResult = invoke(compileProjectCommand)
        if (compileProjectResult != 0) {
            printToStreamErrorIfCanceled("Compile project failed, please check the error message.")
            return GradleCompileResult.failed(isCanceled, "Compile project failed $compileProjectResult")
        }

        // try sub dir first
        val subDir = File(project.basePath!!, "app/build/outputs")
        var apkFile = subDir.findFilesRecursively(juggGradleCompileOptions.outputApkName)
        if (apkFile == null) {
            // find in root dir
            val rootDir = File(project.basePath!!)
            apkFile = rootDir.findFilesRecursively(juggGradleCompileOptions.outputApkName)
        }
        if (apkFile == null) {
            printToStreamError("Can't find apk \"${juggGradleCompileOptions.outputApkName}\" " +
                    "in ${project.basePath}, please make sure your run configuration is right.")
            return GradleCompileResult.failed(isCanceled, "Can't find apk")
        }
        return GradleCompileResult.success(apkFile)
    }

    override fun fetchClasspathResult(buildDirs: List<ModuleBuildPathInfo>): File {
        isCanceled = false
        return File(project.basePath!!)
    }

    @Volatile
    private var isCanceled = false

    override fun cancelAction(isByUser: Boolean) {
        if (isByUser) {
            printToStreamInfo("[Jugg] user cancel")
        }
        currentRunningProcess?.destroy()
        isCanceled = true
    }

    private fun invoke(command: ISshCommand): Int {
        printToStreamInfo("[Jugg] ${command::class.simpleName} exec start")

        val envArray: MutableList<String> = System.getenv().entries
            .filter {
                it.key == "JAVA_HOME" || it.key == "ANDROID_HOME"
            }
            .map {
                "${it.key}=${it.value}"
            }
            .toMutableList()
        if (gradleJdkPath != null) {
            envArray.add("JAVA_HOME=$gradleJdkPath")
        }
        if (androidHomePath != null) {
            envArray.add("ANDROID_HOME=$androidHomePath")
        }

        val commandString = command.getCommand(isNeedSetChineseLanguage = false, isWindows = isWindows)
        logger.debug("invoke command: $commandString")
        val commands = if (isWindows) {
            arrayOf("cmd.exe", "/c", commandString)
        } else {
            arrayOf("/bin/bash", "-c", commandString)
        }
        val process = Runtime.getRuntime().exec(
            commands,
            envArray.toTypedArray(),
        )
        currentRunningProcess = process

        command.beforeInvokeCommand()
        val commander = PrintStream(process.outputStream, false)

        val errorPrintThread = object : Thread() {
            override fun run() {
                val reader = process.errorStream.bufferedReader(Charsets.UTF_8)
                while (!isInterrupted) {
                    try {
                        val line = reader.readLine()
                        if (line != null) {
                            if (line.isNotEmpty()) {
                                printToStreamError(line)
                            }
                        }
                    } catch (e: IOException) {
                        // java.io.IOException: Stream closed
                        break
                    }
                }
            }
        }
        errorPrintThread.start()

        val reader = process.inputStream.bufferedReader(Charsets.UTF_8)
        var result: Int
        while (true) {
            try {
                val line = reader.readLine()
                if (line != null) {
                    if (line.isNotEmpty()) {
                        printToStream(line)
                    }
                    val output = command.getInput(line)
                    if (output != null) {
                        logger.debug("output: $output")
                        commander.println(output)
                        commander.flush()
                    }
                    val currentResult = command.hasFinishWithResult(line)
                    if (currentResult != null) {
                        result = currentResult
                        break
                    }
                }
            } catch (e: IOException) {
                // java.io.IOException: Stream closed
                result = IGradleCompileClient.Error.RESULT_CHANNEL_CLOSED
                break
            }

            if (!process.isAlive) {
                printToStream("[Jugg] exit-status: " + process.exitValue())
                result = IGradleCompileClient.Error.RESULT_CHANNEL_CLOSED
                break
            }
        }
        process.waitFor()
        errorPrintThread.interrupt()
        currentRunningProcess = null

        printToStreamInfo("[Jugg] ${command::class.simpleName} exec finished with result: $result")
        return result
    }

    private fun printToStream(line: String) {
        terminalOutputListener.onOutput(line)
    }

    private fun printToStreamInfo(line: String) {
        terminalOutputListener.onOutput(line, isNeedPrint = false)
        logger.info(line)
    }

    private fun printToStreamError(line: String, e: Exception? = null) {
        terminalOutputListener.onOutput(line, isNeedPrint = false)
        logger.warn(line, e)
    }

    private fun printToStreamErrorIfCanceled(line: String, e: Exception? = null) {
        if (isCanceled) {
            return
        }
        terminalOutputListener.onOutput(line, isNeedPrint = false)
        return printToStreamError(line, e)
    }

    override fun dispose() {
        currentRunningProcess?.destroy()
        currentRunningProcess = null
    }

}
