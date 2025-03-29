package com.sickworm.intellij.jugg.ide.logic

import com.intellij.execution.DefaultExecutionResult
import com.intellij.execution.ExecutionResult
import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.compiler.source.kotlin.PriorityURLClassLoader
import com.sickworm.intellij.jugg.platform.PlatformApi
import java.io.File
import java.nio.file.Files
import java.nio.file.StandardCopyOption
import java.util.zip.ZipFile
import kotlin.io.path.createTempDirectory

object RuntimeMockUtils {

    val isTestMode = File(System.getProperty("user.home"), ".jugg_test_mode").exists()

    fun isNeedRunTest(): Boolean {
        return File("${System.getProperty("user.home")}/.jugg_runtime_test").exists()
    }

    fun runTest(logger: Logger): ExecutionResult {
        doRunTest(logger)
        val result = DefaultExecutionResult()
        result.processHandler.detachProcess()
        return result
    }

    private fun doRunTest(logger: Logger) {
        try {
            val distributionsDir = File("${System.getProperty("user.home")}/IdeaProjects/jugg/idea/build/distributions")
            val zipFiles = distributionsDir.listFiles { file -> file.extension == "zip" }

            if (zipFiles.isNullOrEmpty()) {
                logger.error("No zip files found in distributions directory")
                return
            }

            val zipFile = zipFiles.first()
            logger.info("Found zip file: ${zipFile.name}")

            val tempDir = createTempDirectory("jugg_runtime_test")
            val jarFiles = mutableListOf<File>()

            ZipFile(zipFile).use { zip ->
                zip.entries().asSequence().forEach { entry ->
                    if (!entry.isDirectory && entry.name.endsWith(".jar")) {
                        val jarFile = tempDir.resolve(File(entry.name).name).toFile()
                        zip.getInputStream(entry).use { input ->
                            Files.copy(input, jarFile.toPath(), StandardCopyOption.REPLACE_EXISTING)
                        }
                        jarFiles.add(jarFile)
                        logger.info("Extracted jar: ${jarFile.name}")
                    }
                }
            }

            if (jarFiles.isEmpty()) {
                logger.error("No jar files found in zip")
                return
            }

            val jarUrls = jarFiles.map { it.toURI().toURL() }.toTypedArray()
            val customClassLoader = PriorityURLClassLoader(jarUrls, this::class.java.classLoader)

            val clazz = customClassLoader.loadClass("com.sickworm.intellij.jugg.deploy.run.NarwhalAsDeployerCompat")
            val instance = clazz.getDeclaredConstructor().newInstance()
            val testMethod = clazz.getMethod("test")
            val result = testMethod.invoke(instance)
            PlatformApi.showDialog("Result", result.toString())
        } catch (e: Throwable) {
            logger.error("Error in runTest: $e ", e)
            PlatformApi.showDialog("Error", "Error in runTest: $e")
        }
    }
}