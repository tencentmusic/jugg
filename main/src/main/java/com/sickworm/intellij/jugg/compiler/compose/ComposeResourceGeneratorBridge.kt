package com.sickworm.intellij.jugg.compiler.compose

import com.intellij.openapi.Disposable
import com.intellij.openapi.util.Disposer
import com.sickworm.intellij.jugg.project.data.ComposeResourceInfo
import java.io.File
import java.lang.reflect.InvocationTargetException
import java.lang.reflect.Method
import java.net.URLClassLoader
import java.nio.file.Path

/** Generated Compose Kotlin sources split by the source compilation target. */
data class ComposeGeneratedSources(
    val commonFiles: List<File>,
    val platformFiles: List<File>,
) {
    val allFiles: List<File> get() = commonFiles + platformFiles
}

/** Calls Compose pure Kotlin generators without exposing Gradle classes to Jugg. */
class ComposeResourceGeneratorBridge(parent: Disposable) : Disposable {

    private val classLoaderCache = mutableMapOf<ClasspathKey, URLClassLoader>()
    private var disposed = false

    init {
        Disposer.register(parent, this)
    }

    /** Generates the same Kotlin files as the supported Compose resource generation tasks. */
    fun generate(
        info: ComposeResourceInfo,
        resourcesBySourceSet: Map<String, Map<ComposeResourceType, Map<String, List<ComposeResourceItem>>>>,
        commonSourceSetNames: Set<String>,
        outputDir: File,
    ): ComposeGeneratedSources {
        val loader = classLoader(info.generatorClasspath)
        val api = loadApi(loader)
        if (api.isLegacy) {
            require(resourcesBySourceSet.size == 1) { "Legacy Compose resources require one source set." }
            return ComposeGeneratedSources(
                commonFiles = api.writeLegacy(info, resourcesBySourceSet.values.single(), outputDir),
                platformFiles = emptyList(),
            )
        }
        val commonFiles = mutableListOf<File>()
        val platformFiles = mutableListOf<File>()

        commonFiles += api.writeRes(info, File(outputDir, COMMON_RES_CLASS_DIR))
        resourcesBySourceSet.toSortedMap().forEach { (sourceSetName, resources) ->
            val files = api.writeAccessors(info, sourceSetName, resources, File(outputDir, "${sourceSetName}ResourceAccessors"))
            if (sourceSetName in commonSourceSetNames) commonFiles += files else platformFiles += files
        }
        commonFiles += api.writeExpectCollectors(info, File(outputDir, COMMON_COLLECTORS_DIR))
        platformFiles += api.writeActualCollectors(
            info,
            collectorFunctions(resourcesBySourceSet),
            File(outputDir, ANDROID_COLLECTORS_DIR),
        )
        return ComposeGeneratedSources(commonFiles.sortedBy(File::getPath), platformFiles.sortedBy(File::getPath))
    }

    override fun dispose() {
        val failure = synchronized(classLoaderCache) {
            disposed = true
            val firstFailure = classLoaderCache.values.mapNotNull { runCatching { it.close() }.exceptionOrNull() }.firstOrNull()
            classLoaderCache.clear()
            firstFailure
        }
        if (failure != null) throw IllegalStateException("Failed to close Compose resource generator classloader", failure)
    }

    private fun classLoader(classpath: List<File>): URLClassLoader {
        validateClasspath(classpath)
        val key = ClasspathKey(classpath.map { file ->
            val canonical = file.canonicalFile
            ClasspathEntry(canonical.path, canonical.length(), canonical.lastModified())
        })
        return synchronized(classLoaderCache) {
            check(!disposed) { "Compose resource generator bridge is disposed" }
            classLoaderCache.getOrPut(key) {
                URLClassLoader(classpath.map { it.canonicalFile.toURI().toURL() }.toTypedArray(), ClassLoader.getPlatformClassLoader())
            }
        }
    }

    private fun validateClasspath(classpath: List<File>) {
        val pluginJars = classpath.filter { it.name.startsWith("compose-gradle-plugin-") && it.extension == "jar" }
        require(pluginJars.size == 1 && classpath.all(File::isFile)) {
            UNSUPPORTED_MESSAGE
        }
    }

    private fun loadApi(loader: ClassLoader): GeneratorApi = try {
        GeneratorApi(loader)
    } catch (exception: ReflectiveOperationException) {
        throw unsupported(exception)
    } catch (error: LinkageError) {
        throw unsupported(error)
    }

    private fun collectorFunctions(
        resourcesBySourceSet: Map<String, Map<ComposeResourceType, Map<String, List<ComposeResourceItem>>>>,
    ): Map<ComposeResourceType, List<String>> = ComposeResourceType.values().associateWith { type ->
        resourcesBySourceSet.entries
            .filter { (_, resources) -> resources[type].orEmpty().isNotEmpty() }
            .sortedBy { it.key }
            .flatMap { (sourceSetName, resources) ->
                val chunkCount = (resources.getValue(type).size + ITEMS_PER_FILE - 1) / ITEMS_PER_FILE
                (0 until chunkCount).map { index -> collectorFunction(sourceSetName, type, index) }
            }
    }

    private fun collectorFunction(sourceSetName: String, type: ComposeResourceType, index: Int): String =
        "_collect${sourceSetName.uppercaseFirst()}${type.accessorName()}${index}Resources"

    private fun String.uppercaseFirst(): String = replaceFirstChar { it.uppercase() }

    private fun ComposeResourceType.accessorName(): String = when (this) {
        ComposeResourceType.DRAWABLE -> "Drawable"
        ComposeResourceType.STRING -> "String"
        ComposeResourceType.STRING_ARRAY -> "Array"
        ComposeResourceType.PLURAL_STRING -> "Plurals"
        ComposeResourceType.FONT -> "Font"
    }

    private fun unsupported(cause: Throwable) = IllegalArgumentException(UNSUPPORTED_MESSAGE, cause)

    private data class ClasspathKey(val entries: List<ClasspathEntry>)
    private data class ClasspathEntry(val path: String, val length: Long, val modified: Long)

    private companion object {
        const val UNSUPPORTED_MESSAGE =
            "Unsupported Compose resource generator API."
        const val COMMON_RES_CLASS_DIR = "commonResClass"
        const val COMMON_COLLECTORS_DIR = "commonMainResourceCollectors"
        const val ANDROID_COLLECTORS_DIR = "androidMainResourceCollectors"
        const val ITEMS_PER_FILE = 500
    }

    /** Owns reflection handles and loader-local conversions for one Compose generator classloader. */
    private class GeneratorApi(loader: ClassLoader) {
        private val resourceTypeClass = loader.loadClass(RESOURCE_TYPE_CLASS)
        private val resourceItemConstructor = loader.loadClass(RESOURCE_ITEM_CLASS).constructors
            .filterNot { constructor -> constructor.parameterTypes.lastOrNull()?.name == "kotlin.jvm.internal.DefaultConstructorMarker" }
            .single { constructor -> constructor.parameterCount in setOf(4, 6, 7) }
        private val valueOf = resourceTypeClass.getMethod("valueOf", String::class.java)
        private val generatorClass = runCatching { loader.loadClass(GENERATOR_CLASS) }.getOrNull()
        private val legacyGeneratorClass = runCatching { loader.loadClass(LEGACY_GENERATOR_CLASS) }.getOrNull()
        val isLegacy = generatorClass == null && legacyGeneratorClass != null
        private val resSpec = generatorMethod("getResFileSpec")
        private val accessorSpecs = generatorMethod("getAccessorsSpecs")
        private val expectCollectorsSpec = generatorMethod("getExpectResourceCollectorsFileSpec")
        private val actualCollectorsSpec = generatorMethod("getActualResourceCollectorsFileSpec")
        private val legacySpecs = legacyGeneratorClass?.methods?.singleOrNull { it.name == "getResFileSpecs" }
        private val writeToPath = loader.loadClass(FILE_SPEC_CLASS).getMethod("writeTo", Path::class.java)

        private fun generatorMethod(name: String): Method? = generatorClass?.methods?.single { it.name == name }

        fun writeLegacy(
            info: ComposeResourceInfo,
            resources: Map<ComposeResourceType, Map<String, List<ComposeResourceItem>>>,
            directory: File,
        ): List<File> = writeSpecs(
            invokeStatic(requireNotNull(legacySpecs), convertResources(resources), info.packageName) as List<*>,
            directory,
        )

        fun writeRes(info: ComposeResourceInfo, directory: File): List<File> = writeSpecs(
            listOf(
                invokeStatic(
                    requireNotNull(resSpec),
                    info.packageName,
                    info.resClassName,
                    info.assetRelativePath.replace(File.separatorChar, '/') + "/",
                    info.publicResClass,
                ),
            ),
            directory,
        )

        fun writeAccessors(
            info: ComposeResourceInfo,
            sourceSetName: String,
            resources: Map<ComposeResourceType, Map<String, List<ComposeResourceItem>>>,
            directory: File,
        ): List<File> = writeSpecs(
            invokeStatic(requireNotNull(accessorSpecs), *accessorArguments(info, sourceSetName, resources)) as List<*>,
            directory,
        )

        fun writeExpectCollectors(info: ComposeResourceInfo, directory: File): List<File> = writeSpecs(
            listOf(invokeStatic(
                requireNotNull(expectCollectorsSpec),
                *collectorArguments(requireNotNull(expectCollectorsSpec), info, "ExpectResourceCollectors"),
            )),
            directory,
        )

        fun writeActualCollectors(
            info: ComposeResourceInfo,
            sourceSets: Map<ComposeResourceType, List<String>>,
            directory: File,
        ): List<File> = writeSpecs(
            listOf(
                invokeStatic(requireNotNull(actualCollectorsSpec), *actualCollectorArguments(info, sourceSets)),
            ),
            directory,
        )

        private fun accessorArguments(
            info: ComposeResourceInfo,
            sourceSetName: String,
            resources: Map<ComposeResourceType, Map<String, List<ComposeResourceItem>>>,
        ): Array<Any?> = if (requireNotNull(accessorSpecs).parameterCount == 5) {
            arrayOf(convertResources(resources), info.packageName, sourceSetName, "", info.publicResClass)
        } else {
            arrayOf(
                convertResources(resources),
                info.packageName,
                sourceSetName,
                "",
                info.resClassName,
                info.publicResClass,
                info.generateResourceContentHash,
            )
        }

        private fun collectorArguments(method: Method, info: ComposeResourceInfo, fileName: String): Array<Any?> =
            if (method.parameterCount == 3) {
                arrayOf(info.packageName, fileName, info.publicResClass)
            } else {
                arrayOf(info.packageName, fileName, info.resClassName, info.publicResClass)
            }

        private fun actualCollectorArguments(
            info: ComposeResourceInfo,
            sourceSets: Map<ComposeResourceType, List<String>>,
        ): Array<Any?> = if (requireNotNull(actualCollectorsSpec).parameterCount == 5) {
            arrayOf(info.packageName, "ActualResourceCollectors", info.publicResClass, true, convertTypes(sourceSets))
        } else {
            arrayOf(
                info.packageName,
                "ActualResourceCollectors",
                info.resClassName,
                info.publicResClass,
                true,
                convertTypes(sourceSets),
            )
        }

        private fun convertResources(
            resources: Map<ComposeResourceType, Map<String, List<ComposeResourceItem>>>,
        ): Map<Any, Map<String, List<Any>>> = resources.mapKeys { enumValue(it.key) }.mapValues { (_, namedItems) ->
            namedItems.mapValues { (_, items) -> items.map(::resourceItem) }
        }

        private fun convertTypes(sourceSets: Map<ComposeResourceType, List<String>>): Map<Any, List<String>> =
            sourceSets.mapKeys { enumValue(it.key) }

        private fun resourceItem(item: ComposeResourceItem): Any {
            val arguments = mutableListOf<Any?>(enumValue(item.type), item.qualifiers, item.name, item.path)
            if (resourceItemConstructor.parameterCount == 4) {
                return resourceItemConstructor.newInstance(*arguments.toTypedArray())
            }
            if (resourceItemConstructor.parameterCount == 7) arguments += item.contentHash
            arguments += item.offset
            arguments += item.size
            return resourceItemConstructor.newInstance(*arguments.toTypedArray())
        }

        private fun enumValue(type: ComposeResourceType): Any = valueOf.invoke(null, type.name)

        private fun writeSpecs(specs: List<*>, directory: File): List<File> {
            directory.mkdirs()
            specs.forEach { spec -> invokeInstance(writeToPath, spec, directory.toPath()) }
            return directory.walkTopDown().filter { it.isFile && it.extension == "kt" }.toList()
        }

        private fun invokeStatic(method: Method, vararg arguments: Any?): Any = try {
            method.invoke(null, *arguments) ?: Unit
        } catch (exception: InvocationTargetException) {
            val cause = exception.targetException
            if (cause is LinkageError) throw IllegalArgumentException(UNSUPPORTED_MESSAGE, cause)
            throw cause
        }

        private fun invokeInstance(method: Method, receiver: Any?, vararg arguments: Any?): Any = try {
            method.invoke(receiver, *arguments) ?: Unit
        } catch (exception: InvocationTargetException) {
            val cause = exception.targetException
            if (cause is LinkageError) throw IllegalArgumentException(UNSUPPORTED_MESSAGE, cause)
            throw cause
        }

        private companion object {
            const val RESOURCE_TYPE_CLASS = "org.jetbrains.compose.resources.ResourceType"
            const val RESOURCE_ITEM_CLASS = "org.jetbrains.compose.resources.ResourceItem"
            const val GENERATOR_CLASS = "org.jetbrains.compose.resources.GeneratedResClassSpecKt"
            const val LEGACY_GENERATOR_CLASS = "org.jetbrains.compose.resources.ResourcesSpecKt"
            const val FILE_SPEC_CLASS = "org.jetbrains.compose.internal.com.squareup.kotlinpoet.FileSpec"
        }
    }
}
