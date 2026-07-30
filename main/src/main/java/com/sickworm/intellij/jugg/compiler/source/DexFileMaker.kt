package com.sickworm.intellij.jugg.compiler.source

import com.android.tools.r8.D8Command
import com.android.tools.r8.origin.Origin
import com.intellij.openapi.diagnostic.Logger
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.net.URLClassLoader


/**
 * DexFileMaker invokes D8 to compile class/jar inputs into dex output for incremental workflows.
 */
class DexFileMaker(private val logger: Logger) {

    fun dex(outputDir: File,
            classFilesOrDir: List<File>,
            classpath: Collection<String>,
            androidJar: File,
            minApi: Int,
            isFilePerClass: Boolean = true,
            desugaredLibraryConfiguration: String? = null,
            agpR8Classpath: File? = null,
    ) {
        outputDir.mkdirs()

        // see https://developer.android.com/studio/command-line/d8
        val args = mutableListOf<String>()

        if (isFilePerClass) {
            args.add("--file-per-class")
        }

        args.add("--lib")
        args.add(androidJar.absolutePath)

        args.add("--min-api")
        args.add("$minApi")

        // see:
        // https://developer.android.com/tools/d8#j8
        if (classpath.isNotEmpty()) {
            classpath.forEach {
                args.add("--classpath")
                args.add(it)
            }
        }

        args.add("--output")
        args.add(outputDir.absolutePath)

        val filesPath = classFilesOrDir.map { it.absolutePath }
        args.addAll(filesPath)

        logger.debug("D8Command: d8 ${args.joinToString(" ")}")
        val agpRuntime = agpR8Classpath?.let { AgpD8RuntimeCache.get(it, logger) }
        if (agpRuntime != null) {
            if (agpRuntime.supports(desugaredLibraryConfiguration)) {
                logger.debug("Use AGP R8 ${agpRuntime.version} from ${agpR8Classpath.absolutePath}")
                if (agpRuntime.run(args, desugaredLibraryConfiguration, logger)) {
                    return
                }
            } else {
                logger.debug("AGP R8 does not support the required D8 API, use bundled R8 instead")
            }
        }

        val builder = D8Command.parse(args.toTypedArray(), Origin.root())
        if (desugaredLibraryConfiguration != null) {
            builder.addDesugaredLibraryConfiguration(desugaredLibraryConfiguration)
        }
        val command = builder.build()
        com.android.tools.r8.D8.run(command) // throws exceptions
    }
}

/** Loads one AGP-provided R8 distribution without exposing its classes to the plugin classloader. */
private class AgpD8Runtime(private val classpath: File) {

    private val classLoader = URLClassLoader(
        arrayOf(classpath.toURI().toURL()),
        ClassLoader.getPlatformClassLoader(),
    )
    private val originClass = classLoader.loadClass("com.android.tools.r8.origin.Origin")
    private val commandClass = classLoader.loadClass("com.android.tools.r8.D8Command")
    private val builderClass = classLoader.loadClass("com.android.tools.r8.D8Command\$Builder")
    private val parseMethod = commandClass.getMethod("parse", Array<String>::class.java, originClass)
    private val buildMethod = builderClass.getMethod("build")
    private val addDesugaredLibraryMethod: Method? = runCatching {
        builderClass.getMethod("addDesugaredLibraryConfiguration", String::class.java)
    }.getOrNull()
    private val runMethod = classLoader.loadClass("com.android.tools.r8.D8")
        .getMethod("run", commandClass)

    val version: String = runCatching {
        classLoader.loadClass("com.android.tools.r8.Version")
            .getMethod("getVersionString")
            .invoke(null)
            .toString()
    }.getOrDefault("unknown")

    fun supports(desugaredLibraryConfiguration: String?): Boolean {
        return desugaredLibraryConfiguration == null || addDesugaredLibraryMethod != null
    }

    fun run(args: List<String>, desugaredLibraryConfiguration: String?, logger: Logger): Boolean {
        return try {
            val origin = originClass.getMethod("root").invoke(null)
            val builder = parseMethod.invoke(null, args.toTypedArray(), origin)
            if (desugaredLibraryConfiguration != null) {
                val method = addDesugaredLibraryMethod
                    ?: throw NoSuchMethodException("D8Command.Builder.addDesugaredLibraryConfiguration")
                method.invoke(builder, desugaredLibraryConfiguration)
            }
            val command = buildMethod.invoke(builder)
            runMethod.invoke(null, command)
            true
        } catch (e: InvocationTargetException) {
            logger.warn("AGP D8 $version compile failed (classpath: ${classpath.absolutePath}), " +
                    "use bundled R8 instead", e.targetException)
            false
        } catch (e: Exception) {
            logger.warn("Run AGP D8 $version failed (classpath: ${classpath.absolutePath}), " +
                    "use bundled R8 instead", e)
            false
        } catch (e: LinkageError) {
            logger.warn("Link AGP D8 $version failed (classpath: ${classpath.absolutePath}), " +
                    "use bundled R8 instead", e)
            false
        }
    }
}

private object AgpD8RuntimeCache {

    private val runtimes = mutableMapOf<String, AgpD8Runtime>()

    @Synchronized
    fun get(classpath: File, logger: Logger): AgpD8Runtime? {
        if (!classpath.exists()) {
            logger.debug("AGP R8 classpath not found: ${classpath.absolutePath}")
            return null
        }
        return try {
            val key = classpath.canonicalPath
            runtimes.getOrPut(key) { AgpD8Runtime(classpath) }
        } catch (e: Exception) {
            logger.debug("Load AGP R8 failed, use bundled R8 instead: ${classpath.absolutePath}", e)
            null
        } catch (e: LinkageError) {
            logger.debug("Link AGP R8 failed, use bundled R8 instead: ${classpath.absolutePath}", e)
            null
        }
    }
}
