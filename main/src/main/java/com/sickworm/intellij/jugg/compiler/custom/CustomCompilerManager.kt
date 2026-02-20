package com.sickworm.intellij.jugg.compiler.custom

import com.intellij.openapi.Disposable
import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.compiler.ICompileContext
import com.sickworm.intellij.jugg.compiler.ICompiler
import com.sickworm.intellij.jugg.logger.getInstance
import com.sickworm.intellij.jugg.server.JuggServer
import com.sickworm.intellij.jugg.server.protocols.CustomCompilerInfo
import java.io.File
import java.net.URLClassLoader
import java.security.MessageDigest
import java.util.*

/**
 * CustomCompilerManager coordinates custom workflows.
 */
class CustomCompilerManager(
    private val projectDir: File,
    private val customCompilerDir: File,
    private val juggServer: JuggServer,
    logger: Logger,
) {

    private val logger = logger.getInstance("CustomCompilerManager")

    private var customCompilerJars = listOf<File>()

    fun updateCustomCompilers(customCompilers: List<CustomCompilerInfo>?) {
        logger.debug("updateCustomCompilers $customCompilers")
        if (customCompilers == null) {
            logger.debug("updateCustomCompilers with null config, exit.")
            return
        }
        customCompilerJars = customCompilers.mapNotNull {
            updateCustomCompiler(it)
        }
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

    fun setCustomCompilerJars(jars: List<File>) {
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

    private var compileContext: ICompileContext? = null
    private var compileParentDisposable: Disposable? = null

    @Synchronized
    fun init(context: ICompileContext, parent: Disposable) {
        logger.debug("init")
        this.compileContext = context
        this.compileParentDisposable = parent
        this.customCompilers = emptyList()
    }

    private fun initCompilers(): List<ICompiler> {
        logger.debug("initCompilers")
        val context = compileContext ?: return emptyList()
        val parent = compileParentDisposable ?: return emptyList()
        val urls = customCompilerJars.map { it.toURI().toURL() }.toTypedArray()
        val classLoader = URLClassLoader(urls, this::class.java.classLoader)
        val customCompilers = mutableListOf<ICompiler>()
        ServiceLoader.load(ICompilerCreator::class.java, classLoader).forEach {
            val compiler = it.create(context, parent)
            customCompilers.add(compiler)
        }
        logger.debug("initCompilers finished: $customCompilers")
        return customCompilers
    }

    @Synchronized
    fun getCustomCompilers(): List<ICompiler> {
        if (customCompilerJars.isNotEmpty() && customCompilers.isEmpty()) {
            customCompilers = initCompilers()
        }
        return customCompilers
    }

    private fun resetCompilerJars() {
        customCompilerJars = customCompilerDir.listFiles()?.filter { it.name.endsWith(".jar") } ?: emptyList()
        this.customCompilers = emptyList() // recreate next time
        logger.debug("resetCompilerJars: $customCompilerJars")
    }

    private fun File.md5(): String {
        val md = MessageDigest.getInstance("MD5")
        md.update(readBytes())
        return md.digest().joinToString("") { "%02x".format(it) }
    }

}
