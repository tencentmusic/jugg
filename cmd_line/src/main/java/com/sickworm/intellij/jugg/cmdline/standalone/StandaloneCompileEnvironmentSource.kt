package com.sickworm.intellij.jugg.cmdline.standalone

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.compiler.context.ICompileEnvironmentSource
import java.io.File

/** Resolves the SDK and Gradle environment from the standalone daemon process. */
class StandaloneCompileEnvironmentSource : ICompileEnvironmentSource {
    override fun getAndroidHome(logger: Logger): File? {
        val path = System.getenv("ANDROID_HOME") ?: System.getenv("ANDROID_SDK_ROOT")
        return path?.takeIf { it.isNotBlank() }?.let(::File)
    }

    override fun buildCompileEnv(logger: Logger): List<String> {
        val environment = System.getenv().toMutableMap()
        if (environment["JAVA_HOME"].isNullOrBlank()) {
            environment["JAVA_HOME"] = System.getProperty("java.home")
        }
        getAndroidHome(logger)?.let { environment["ANDROID_HOME"] = it.path }
        return environment.map { (name, value) -> "$name=$value" }
    }
}
