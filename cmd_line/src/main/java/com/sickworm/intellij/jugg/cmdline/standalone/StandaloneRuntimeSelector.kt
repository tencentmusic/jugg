package com.sickworm.intellij.jugg.cmdline.standalone

import java.io.File

/** Installs an extracted standalone Bundle. */
object StandaloneBundleInstallerMain {
    @JvmStatic
    fun main(args: Array<String>) {
        val bundleDir = args.firstOrNull { !it.startsWith("--") }?.let(::File) ?: File(".")
        val managedBy = args.firstOrNull { it.startsWith("--managed-by=") }?.substringAfter('=') ?: "external"
        val rootDir = System.getProperty("jugg.root.dir")?.let(::File) ?: File(System.getProperty("user.home"), ".jugg")
        StandaloneRuntimeInstaller(rootDir, rootDir.resolve("standalone/bin")).install(bundleDir, managedBy)
        println("Jugg standalone installed from ${bundleDir.absolutePath}")
    }
}
