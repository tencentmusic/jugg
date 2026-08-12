package com.sickworm.intellij.jugg.cmdline.standalone

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.compiler.context.ICompileEnvironmentSource
import java.io.File
import java.util.Properties

/** Resolves the SDK and Gradle environment from the standalone daemon process. */
class StandaloneCompileEnvironmentSource(
    private val projectDir: File,
    private val environment: Map<String, String> = System.getenv(),
) : ICompileEnvironmentSource {
    override fun getAndroidHome(logger: Logger): File? {
        val environmentPath = environment["ANDROID_HOME"] ?: environment["ANDROID_SDK_ROOT"]
        environmentPath?.takeIf { it.isNotBlank() }?.let { return File(it) }
        val localProperties = projectDir.resolve("local.properties")
        if (!localProperties.isFile) return null
        return runCatching {
            val properties = Properties().apply { localProperties.inputStream().use(::load) }
            properties.getProperty("sdk.dir")?.takeIf { it.isNotBlank() }?.let { File(it).canonicalFile }
        }.onFailure {
            logger.warn("Read Android SDK from local.properties failed.", it)
        }.getOrNull()
    }

    override fun buildCompileEnv(logger: Logger): List<String> {
        val compileEnvironment = environment.toMutableMap()
        if (compileEnvironment["JAVA_HOME"].isNullOrBlank()) {
            compileEnvironment["JAVA_HOME"] = System.getProperty("java.home")
        }
        getAndroidHome(logger)?.let { compileEnvironment["ANDROID_HOME"] = it.path }
        return compileEnvironment.map { (name, value) -> "$name=$value" }
    }
}
