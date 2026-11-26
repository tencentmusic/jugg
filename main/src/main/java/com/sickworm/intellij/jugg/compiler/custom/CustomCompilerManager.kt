package com.sickworm.intellij.jugg.compiler.custom

import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.intellij.openapi.util.Disposer
import com.sickworm.intellij.jugg.compiler.ICompileContext
import com.sickworm.intellij.jugg.compiler.ICompiler
import com.sickworm.intellij.jugg.logger.getInstance
import com.sickworm.intellij.jugg.server.JuggServer
import com.sickworm.intellij.jugg.server.protocols.CustomCompilerInfo
import java.io.File
import java.net.URLClassLoader
import java.security.MessageDigest
import java.util.*

/** Loads custom compiler SPI implementations and owns their classloader and disposable compatibility scope. */
class CustomCompilerManager(
    private val projectDir: File,
    private val customCompilerDir: File,
    private val juggServer: JuggServer,
    logger: Logger,
) : AutoCloseable {

    private val logger = logger.getInstance("CustomCompilerManager")

    private var customCompilerJars = listOf<File>()
    private var customCompilerConfigs = listOf<CustomCompilerInfo>()

    @Synchronized
    fun updateCustomCompilers(customCompilers: List<CustomCompilerInfo>?) {
        logger.debug("updateCustomCompilers $customCompilers")
        if (customCompilers == null) {
            logger.debug("updateCustomCompilers with null config, exit.")
            return
        }
        val configChanged = customCompilerConfigs != customCompilers
        customCompilerConfigs = customCompilers
        if (configChanged) clearLoadedCompilers()
        val updatedJars = customCompilers.mapNotNull {
            updateCustomCompiler(it)
        }
        if (customCompilerJars != updatedJars) {
            clearLoadedCompilers()
        }
        customCompilerJars = updatedJars
        // clear deprecated jars
        customCompilerDir.listFiles()?.forEach { file ->
            if (!customCompilerJars.contains(file)) {
                logger.debug("custom compiler $file deprecated, delete it")
                file.delete()
            }
        }
        logger.debug("updateCustomCompilers finished, compilers: $customCompilerJars")

        // download compilers if not downloaded
        juggServer.launchSafe {
            downloadCompilers(customCompilers)
        }
    }

    @Synchronized
    fun setCustomCompilerJars(jars: List<File>) {
        if (customCompilerJars != jars) clearLoadedCompilers()
        customCompilerJars = jars
    }

    private fun updateCustomCompiler(customCompilerInfo: CustomCompilerInfo): File? {
        val file = getCustomCompiler(customCompilerInfo)
        if (file != null) {
            val md5 = file.md5()
            if (md5 != customCompilerInfo.md5) {
                logger.debug("custom compiler $file md5 mismatch, delete it")
                file.delete()
                return null
            }
        }
        return file
    }

    private fun getCustomCompiler(customCompilerInfo: CustomCompilerInfo): File? {
        val name = customCompilerInfo.jarFileName
        val path = customCompilerInfo.path
        val absFile = File(path)
        if (absFile.isAbsolute && absFile.exists()) {
            logger.debug("custom compiler $absFile exists")
            return absFile
        }
        val relativeFile = File(projectDir, path)
        if (relativeFile.exists()) {
            logger.debug("custom compiler $relativeFile exists")
            return relativeFile
        }

        if (path.startsWith("http")) {
            val targetFile = customCompilerDir.resolve(name)
            if (targetFile.exists()) {
                logger.debug("http target file $targetFile exists")
                return targetFile
            } else {
                logger.debug("http target file $targetFile not exists, download it later")
                return null
            }
        }

        logger.debug("unknown path $path, ignore")
        return null
    }

    private fun downloadCompilers(customCompilers: List<CustomCompilerInfo>) {
        var isNeedReset = false
        customCompilers.forEach {
            if (it.path.startsWith("http")) {
                downloadCompiler(it)
                isNeedReset = true
            }
        }
        if (isNeedReset) {
            resetCompilerJars()
        }
    }

    private fun downloadCompiler(customCompilerInfo: CustomCompilerInfo) {
        val targetFile = customCompilerDir.resolve(customCompilerInfo.jarFileName)
        if (targetFile.exists() && targetFile.length() > 0) {
            return
        }
        try {
            juggServer.downloadFile(customCompilerInfo.path, targetFile)
            val isSuccess = targetFile.exists() && targetFile.length() > 0
            if (!isSuccess) {
                logger.debug("failed to download $customCompilerInfo")
                return
            }
            logger.debug("success download $customCompilerInfo")
            val md5 = targetFile.md5()
            if (md5 != customCompilerInfo.md5) {
                logger.debug("custom compiler $customCompilerInfo md5 mismatch, actual: $md5. delete it")
                targetFile.delete()
            }
        } catch (e: Exception) {
            logger.warn("error downloading $customCompilerInfo, skip. error: $e")
        }
    }

    private var customCompilers: List<ICompiler> = listOf()
    private var compilersInitialized = false
    private var classLoader: URLClassLoader? = null
    private var compilerScope: Disposable? = null

    private var compileContext: ICompileContext? = null

    @Synchronized
    fun init(context: ICompileContext) {
        logger.debug("init")
        clearLoadedCompilers()
        this.compileContext = context
    }

    private fun initCompilers(): List<ICompiler> {
        logger.debug("initCompilers")
        val context = compileContext ?: return emptyList()
        val compilerScope = CompilerDisposableScope().also { this.compilerScope = it }
        val urls = customCompilerJars.map { it.toURI().toURL() }.toTypedArray()
        val classLoader = URLClassLoader(urls, this::class.java.classLoader).also { this.classLoader = it }
        val customCompilers = mutableListOf<ICompiler>()
        try {
            ServiceLoader.load(ICompilerCreator::class.java, classLoader).forEach {
                customCompilers.add(it.create(context, compilerScope))
            }
        } catch (throwable: Throwable) {
            clearLoadedCompilers()
            throw throwable
        }
        logger.debug("initCompilers finished: $customCompilers")
        return customCompilers
    }

    @Synchronized
    fun getCustomCompilers(): List<ICompiler> {
        if (customCompilerJars.isNotEmpty() && !compilersInitialized) {
            customCompilers = initCompilers()
            compilersInitialized = true
        }
        return customCompilers
    }

    @Synchronized
    private fun resetCompilerJars() {
        clearLoadedCompilers()
        customCompilerJars = customCompilerDir.listFiles()?.filter { it.name.endsWith(".jar") } ?: emptyList()
        logger.debug("resetCompilerJars: $customCompilerJars")
    }

    private fun File.md5(): String {
        val md = MessageDigest.getInstance("MD5")
        md.update(readBytes())
        return md.digest().joinToString("") { "%02x".format(it) }
    }

    @Synchronized
    override fun close() {
        clearLoadedCompilers()
        customCompilerJars = emptyList()
        customCompilerConfigs = emptyList()
        compileContext = null
    }

    private fun closeClassLoader() {
        classLoader?.close()
        classLoader = null
    }

    private fun clearLoadedCompilers() {
        compilerScope?.let(Disposer::dispose)
        compilerScope = null
        customCompilers = emptyList()
        compilersInitialized = false
        closeClassLoader()
    }

    private class CompilerDisposableScope : Disposable {
        override fun dispose() = Unit
    }

}
