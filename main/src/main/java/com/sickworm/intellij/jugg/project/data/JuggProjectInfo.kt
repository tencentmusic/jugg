package com.sickworm.intellij.jugg.project.data

import java.io.File
import java.util.zip.CRC32

data class JuggProjectInfo(
    val modules: Map<String, ModuleInfo>,
)

data class ModuleInfo(
    val name: String, // unique name with parent module name
    val moduleType: Type,
    val moduleRootDir: File,
    val projectRootDir: File,
    val sourceDirs: List<File>,
    val resourceDirs: List<File>,
    val assetsDirs: List<File>,
    val manifestFile: File?,
    val manifestPlaceHolders: Map<String, String>?,
    val buildVariant: String,
    val compileVersion: String?,
    val minSdkVersion: String?,
    val buildToolsVersion: String?,
    val kotlinJvmTarget: String?,
    val kotlinFreeCompilerArgs: List<String>,
    val javaSourceCompatibility: String?,
    val javaTargetCompatibility: String?,
    val buildPathInfo: ModuleBuildPathInfo,
    val moduleDependencies: List<ModuleDependency>,
    val libraryDependencies: List<LibraryDependency>,
    val runtimeLibraryDependencies: List<LibraryDependency>,
    val annotationProcessorDependencies: List<LibraryDependency>,
    val kaptDependencies: List<LibraryDependency>,
    val javaAnnotationProcessorOptions: Map<String, String>? = null,
    val kaptArguments: Map<String, String>? = null,
    val applicationId: String? = null,
    val namespace: String? = null,
    val variants: List<Variant> = emptyList(),
    val signingConfigs: List<SigningConfig>? = null,
    val gradleModuleName: String? = null, // module name in gradle which will be used in kotlin -module-name
    val kotlinPlugins: List<File>? = null,
    val kotlinExtensions: List<File>? = null,
) {

    val moduleStdPath: String get() = moduleRootDir.relativeTo(projectRootDir).path.replace("\\", "/")

    enum class Type {
        Application,
        Library,
        JavaLibrary,
        Unknown,
        ;

        val isAndroidModule get() = this == Application || this == Library
    }

    companion object {

        const val DEFAULT_BUILD_VARIANT = "debug"

        // virtual module that not physical exists
        val virtualModule = ModuleInfo(
            name = "virtual_module",
            moduleType = Type.Library,
            moduleRootDir = File(""),
            projectRootDir = File(""),
            sourceDirs = emptyList(),
            resourceDirs = emptyList(),
            assetsDirs = emptyList(),
            manifestFile = null,
            manifestPlaceHolders = null,
            buildVariant = DEFAULT_BUILD_VARIANT,
            compileVersion = null,
            minSdkVersion = null,
            buildToolsVersion = null,
            kotlinJvmTarget = null,
            javaSourceCompatibility = null,
            javaTargetCompatibility = null,
            buildPathInfo = ModuleBuildPathInfo(File(""), File(""), DEFAULT_BUILD_VARIANT),
            moduleDependencies = emptyList(),
            libraryDependencies = emptyList(),
            kotlinFreeCompilerArgs = emptyList(),
            runtimeLibraryDependencies = emptyList(),
            annotationProcessorDependencies = emptyList(),
            kaptDependencies = emptyList(),
        )
    }
}

data class ModuleBuildPathInfo(
    /** project root dir */
    val projectRootDir: File,
    /** module root dir */
    val moduleRootDir: File,
    /** build variant. e.g. debug, release, developmentDebug */
    val buildVariant: String,
    /** custom classpath specific by project config */
    val customClasspath: List<String>? = null,
    /** custom sync file path specific by project config */
    val customSyncFilePath: List<String>? = null,
) {

    /** build root dir */
    val buildDir: File get() = File(moduleRootDir, "build")

    /** java class path */
    private val javaClassPathNew get() = File(buildDir, "intermediates/javac/$buildVariant/classes")

    /** on AGP 3.2.1 has different java class path */
    @Suppress("DEPRECATION")
    private val javaClassPathOld
    @Suppress("DefaultLocale") // for kotlin 1.4
    get() = File(
        buildDir,
        "intermediates/javac/$buildVariant/compile${buildVariant.capitalize()}JavaWithJavac/classes"
    )
    /** java class path */
    val javaClassPath get() = if (javaClassPathOld.exists()) javaClassPathOld else javaClassPathNew
    /** after AGP 4.1.1, R.class not storage in buildClassPath */

    private val rFilePathDir get() = File(
        buildDir,
        "intermediates/compile_and_runtime_not_namespaced_r_class_jar/$buildVariant"
    )

    // compatible with gradle 8.x, which path like merged_manifests/debug/processDebugResources/R.jar
    val rFilePath get() = File(rFilePathDir, "R.jar").takeIf(File::exists)
        ?: File(rFilePathDir, "process${buildVariant.camel}Resources/R.jar").takeIf(File::exists)
        ?: rFilePathDir.listFilesRecursively().find { it.name == "R.jar" }
        ?: File(rFilePathDir, "R.jar")

    // e.g. AGP 3.4.3 don't have rFilePath, so need use R.jar in library module
    private val libraryRFileDirInLowAgp get() = File(buildDir, "intermediates/compile_only_not_namespaced_r_class_jar/$buildVariant")

    val libraryRFilePathInLowAgp get() = File(libraryRFileDirInLowAgp, "generate${buildVariant.camel}RFile/R.jar").takeIf(File::exists) // AGP 3.4.3
        ?: File(libraryRFileDirInLowAgp, "R.jar") // AGP 3.5.4

    /** kotlin class path */
    val kotlinClassPath get() = File(buildDir, "tmp/kotlin-classes/$buildVariant")

    /** generated source path, used in java-source-roots in KotlinCompiler */
    val generatedSourcePath get() = File(buildDir, "generated")

    /** java classpath for java library */
    private val javaClassPathForJavaLibrary get() = File(buildDir, "classes/java/main")
    /** kotlin classpath for java library */
    private val kotlinClassPathForJavaLibrary get() = File(buildDir, "classes/kotlin/main")

    // compatible with AGP 3.x 4.x
    private val oldLibraryMergedManifestDir get() = File(buildDir, "intermediates/library_manifest/$buildVariant")
    private val libraryMergedManifestDir get() = File(buildDir, "intermediates/merged_manifest/$buildVariant")
    // in AGP 8.x, application module has both merged_manifests and merged_manifest directory,
    // so it cannot use to detect application module
    private val applicationMergedManifestDir get() = File(buildDir, "intermediates/merged_manifests/$buildVariant")

    // compatible with AGP 8.x, which path like merged_manifests/debug/processDebugManifest/AndroidManifest.xml
    // use merged_manifests first, which is the value of multiApkManifestOutputDirectory in AGP 7.X, and Jugg deploy compat mode may modify it instead of merged_manifest
    val mergedManifest get() = listOf(oldLibraryMergedManifestDir, applicationMergedManifestDir, libraryMergedManifestDir)
        .firstNotNullOfOrNull { it.findManifestInDir() } ?: File(libraryMergedManifestDir, "AndroidManifest.xml")

    private val customClasspathFiles get() = customClasspath?.map { File(moduleRootDir, it) } ?: emptyList()
    private val customSyncFiles get() = customSyncFilePath?.map { File(moduleRootDir, it) } ?: emptyList()

    val syncToLocalPathList get() = customSyncFiles + listOf(generatedSourcePath)

    val allClassPath get() = customClasspathFiles + listOf(kotlinClassPath, javaClassPathNew, javaClassPathOld, rFilePath, kotlinClassPathForJavaLibrary, javaClassPathForJavaLibrary, libraryRFilePathInLowAgp)

    // use to fetch all class path after full build
    val allBuildPathRelative get() = (listOf(kotlinClassPath, javaClassPathNew, javaClassPathOld, rFilePathDir,
        kotlinClassPathForJavaLibrary, javaClassPathForJavaLibrary, generatedSourcePath,
        oldLibraryMergedManifestDir, libraryMergedManifestDir, applicationMergedManifestDir, libraryRFileDirInLowAgp
    ) + customClasspathFiles + customSyncFiles).map { it.relativeTo(moduleRootDir) }

    val modulePathRelative get() = moduleRootDir.relativeTo(projectRootDir)

    private fun File.findManifestInDir(): File? {
        return File(this, "AndroidManifest.xml").takeIf(File::exists)
            ?: File(this, "process${buildVariant.camel}Manifest/AndroidManifest.xml").takeIf(File::exists)
            ?: this.listFilesRecursively().find { it.name == "AndroidManifest.xml" }
    }

    companion object {
        private fun File.listFilesRecursively(): List<File> {
            if (!exists()) {
                return emptyList()
            }

            if (isFile) {
                return listOf(this)
            }

            return listFiles()?.flatMap {
                it.listFilesRecursively()
            }?: emptyList()
        }

        private fun <T, R : Any> Iterable<T>.firstNotNullOfOrNull(transform: (T) -> R?): R? {
            for (element in this) {
                val result = transform(element)
                if (result != null) {
                    return result
                }
            }
            return null
        }

        private val String.camel: String get() {
            return this.replaceFirstChar { it.uppercaseChar() }
        }

        private fun String.replaceFirstChar(transform: (Char) -> Char): String {
            return if (isNotEmpty()) transform(this[0]) + substring(1) else this
        }

        private fun Char.uppercaseChar(): Char {
            @Suppress("DEPRECATION") // build.gradle.kts need this
            return if (isLowerCase()) toUpperCase() else this
        }
    }
}

data class LibraryDependency(
    val name: String,
    val file: File,
    val lastModifiedTime: Long = file.lastModified(),
    val crc32: Long = file.toCrc32
) : Dependency {

    val isValid get() = file.exists()

    val isRes get() = file.name == "res"

    val isAndroidManifest get() = file.name == "AndroidManifest.xml"

    val isJar get() = file.extension == "jar"

    val isKlib get() = file.extension == "klib"

    val type get() = when {
        isRes -> "res"
        isAndroidManifest -> "manifest"
        isJar -> "jar"
        isKlib -> "klib"
        else -> "unknown"
    }

    /**
     * Finds out relative old dependency jar file
     * e.g.
     * jetified-sdk-for-jugg-7.29-release/jars/classes.jar
     * matches
     * jetified-sdk-for-jugg-7.28-release/jars/classes.jar
     *
     * jetified-sdk-for-jugg-7.29-release/jars/libs/classes.jar
     * matches
     * jetified-sdk-for-jugg-7.28-release/jars/libs/classes.jar
     */
    @Suppress("RedundantIf")
    fun isRelativeOldDependencyJar(other: LibraryDependency): Boolean {
        if (!isJar || !other.isJar) {
            return false
        }
        if (file.name != other.file.name) {
            return false
        }
        val myParentFile: File = file.parentFile
        val otherParentFile: File = other.file.parentFile
        if (myParentFile.name != otherParentFile.name) {
            return false
        }
        return true
    }

    override fun toString(): String {
        if (isJar) {
            return if (file.parentFile.name == "jars" || !file.path.contains("caches${File.separator}transforms-")) {
                // e.g. classes.jar in aar, or jar not in aar
                "$name(${file.name})"
            } else {
                // e.g. libs/micro_annotation.jar in aar
                "$name(${file.parentFile.name}/${file.name})"
            }
        }
        return "$name/$type"
    }

    companion object {

        private val crc32Digest = CRC32()

        @Suppress("Since15", "RedundantSuppression") // required by build.gradle.kts
        private val File.toCrc32: Long get() {
            if (!exists()) {
                return -1L
            }
            if (isDirectory) {
                return -2L
            }
            return crc32Digest.run {
                reset()
                update(readBytes())
                value
            }
        }
    }
}

data class ModuleDependency(
    val moduleName: String,
) : Dependency {

    override fun toString(): String {
        return moduleName
    }
}

data class AndroidRunConfig(
    val moduleName: String,
    val variants: List<Variant>,
    val signingConfigList: List<SigningConfig>,
)

data class Variant(
    val name: String,
    val signingConfigName: String?,
)

data class SigningConfig(
    val configName: String,
    val keystore: File?,
    val storePassword: String?,
    val keyAlias: String?,
    val keyPassword: String?,
    val storeType: String?,
    val enableV1Signing: Boolean,
    val enableV2Signing: Boolean,
    val enableV3Signing: Boolean,
    val enableV4Signing: Boolean,
    val isSigningReady: Boolean,
) {
    val isInvalid: Boolean get() {
        return keystore == null || !keystore.exists() || storePassword == null
    }

    override fun toString(): String {
        return configName // do not print sensitive info
    }

    companion object {
        val EMPTY = SigningConfig("Empty", null, null, null, null, null, false, false, false, false, false)
    }
}

interface Dependency
