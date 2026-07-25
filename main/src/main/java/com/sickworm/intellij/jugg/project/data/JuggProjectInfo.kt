package com.sickworm.intellij.jugg.project.data

import com.sickworm.intellij.jugg.gradle.script.camelCompat
import java.io.File
import java.util.zip.CRC32

/**
 * Root project snapshot used by compile/deploy planning.
 */
data class JuggProjectInfo(
    val modules: Map<String, ModuleInfo>,
)

/**
 * Compose resource generator configuration captured from supported Gradle plugin tasks.
 */
data class ComposeResourceInfo(
    val generatorClasspath: List<File>,
    val packageName: String,
    val publicResClass: Boolean,
    val resourceDirectories: List<ComposeResourceDirectory>,
    val assetRelativePath: String,
    val resClassName: String = "Res",
    val generateResourceContentHash: Boolean = false,
    val usesLegacyGenerator: Boolean = false,
    val supportStatus: ComposeResourceSupportStatus = ComposeResourceSupportStatus.Supported,
    val unsupportedReason: String? = null
)

/** Records whether detected Compose resource configuration can use Jugg incremental compilation. */
enum class ComposeResourceSupportStatus {
    Supported,
    Unsupported,
}

/**
 * Associates a Compose resource directory with its Kotlin source set.
 */
data class ComposeResourceDirectory(
    val sourceSetName: String,
    val directory: File,
)

/**
 * Gradle module snapshot used to resolve sources, manifests, classpaths, and dependencies.
 */
data class ModuleInfo(
    val name: String, // unique name with parent module name
    val moduleType: Type,
    val moduleRootDir: File,
    val projectRootDir: File,
    val sourceDirs: List<File>,
    /** Kotlin source roots treated as common sources by the selected Android compilation. */
    val kotlinCommonSourceDirs: List<File> = emptyList(),
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
    val coreLibraryDesugaring: List<LibraryDependency>? = null,
    val isUseCompose: Boolean? = null,
    val isUseViewBinding: Boolean? = null,
    val isUseDataBinding: Boolean? = null,
    val kspDependencies: List<LibraryDependency>? = null,
    val instrumentationTargetPackage: String? = null,
    val composeResourceInfo: ComposeResourceInfo? = null,
) {
    // do not add unnecessary content before ") {", for kotlin 1.3 compat: buildReadProjectInfoScript.gradle
    // if adds new fields, also updates:
    // JuggProjectInfoSerialize, JuggProjectInfoMerger, ProjectInfoSerializerInGradle
    // CmdLineContextManager, LibrariesBackupHelper
    // :(

    val moduleStdPath: String get() = moduleRootDir.relativeTo(projectRootDir).path.replace("\\", "/")

    /** Returns true when this module represents an androidTest source set. */
    val isAndroidTestModule: Boolean get() = instrumentationTargetPackage != null

    /**
     * Type enumerates supported Gradle module categories.
     */
    enum class Type {
        Application,
        Library,
        JavaLibrary,
        DynamicFeature,
        Unknown,
        ;

        val isAndroidModule get() = this == Application || this == Library || this == DynamicFeature
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
            buildPathInfo = ModuleBuildPathInfo(
                File(""),
                File(""),
                DEFAULT_BUILD_VARIANT,
                buildDirRelativePath = "",
            ),
            moduleDependencies = emptyList(),
            libraryDependencies = emptyList(),
            kotlinFreeCompilerArgs = emptyList(),
            runtimeLibraryDependencies = emptyList(),
            annotationProcessorDependencies = emptyList(),
            kaptDependencies = emptyList(),
        )
    }
}

/**
 * Resolves AGP-compatible build output paths for one module/build variant.
 */
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
    /** build directory path relative to project root; empty keeps the conventional module/build path */
    val buildDirRelativePath: String,
) {

    /** build root dir */
    val buildDir: File get() = if (buildDirRelativePath.isEmpty()) {
        File(moduleRootDir, "build")
    } else {
        File(projectRootDir, buildDirRelativePath).normalize()
    }

    /** java class path */
    private val javaClassPathNew get() = File(buildDir, "intermediates/javac/$buildVariant/classes")

    /** on AGP 3.2.1 has different java class path */
    private val javaClassPathOld
        get() = File(
            buildDir,
            "intermediates/javac/$buildVariant/compile${buildVariant.camelCompat}JavaWithJavac/classes"
        )

    val javaClassPathCandidates get() = (listOfNotNull(javaClassPathOld.takeIf(File::exists)) +
        listOfNotNull(javaClassPathNew.takeIf(File::exists)))
        .distinctByAbsolutePath()

    /** java class path */
    val javaClassPath get() = javaClassPathCandidates.newestFile() ?: javaClassPathNew
    /** after AGP 4.1.1, R.class not storage in buildClassPath */

    // AGP 9.0+ uses compile_and_runtime_r_class_jar (without not_namespaced)
    private val rFilePathDirAgp9 get() = File(buildDir, "intermediates/compile_and_runtime_r_class_jar/$buildVariant")
    // AGP 8.x and below uses compile_and_runtime_not_namespaced_r_class_jar
    private val rFilePathDir get() = File(buildDir, "intermediates/compile_and_runtime_not_namespaced_r_class_jar/$buildVariant")

    // compatible with AGP 9.0+ (no not_namespaced), gradle 8.x, gradle 7.x
    val rFilePath get() = rFilePathCandidates.newestFile() ?: File(rFilePathDir, "R.jar")

    val rFilePathCandidates get() = (rFilePathDirAgp9.listFilesRecursively().filter { it.name == "R.jar" } +
        listOfNotNull(File(rFilePathDir, "R.jar").takeIf(File::exists)) +
        listOfNotNull(File(rFilePathDir, "process${buildVariant.camelCompat}Resources/R.jar").takeIf(File::exists)) +
        rFilePathDir.listFilesRecursively().filter { it.name == "R.jar" })
        .distinctByAbsolutePath()

    // e.g. AGP 3.4.3 don't have rFilePath, so need use R.jar in library module
    private val libraryRFileDirInLowAgp get() = File(buildDir, "intermediates/compile_only_not_namespaced_r_class_jar/$buildVariant")

    val libraryRFilePathInLowAgp get() = File(libraryRFileDirInLowAgp, "generate${buildVariant.camelCompat}RFile/R.jar").takeIf(File::exists) // AGP 3.4.3
        ?: File(libraryRFileDirInLowAgp, "R.jar") // AGP 3.5.4

    /** kotlin class path */
    val kotlinClassPath get() = File(buildDir, "tmp/kotlin-classes/$buildVariant")

    /** generated source path, used in java-source-roots in KotlinCompiler */
    val generatedSourcePath get() = File(buildDir, "generated")
    val generatedKspSourcePath get() = File(generatedSourcePath, "ksp/$buildVariant/kotlin")

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
    // prefer the newest manifest within each directory to avoid stale AGP 7 outputs shadowing the current AGP 8 output
    val mergedManifest get() = listOf(oldLibraryMergedManifestDir, applicationMergedManifestDir, libraryMergedManifestDir)
        .flatMap { it.findManifestCandidates() }
        .newestFile() ?: File(libraryMergedManifestDir, "AndroidManifest.xml")

    val dataBindingInfoDir get() = File(buildDir, "intermediates/data_binding_base_class_log_artifact/$buildVariant")
    val dataBindingDependencyInfoDir get() = File(buildDir, "intermediates/data_binding_base_class_logs_dependency_artifacts/$buildVariant")
    val dataBindingArtifactDir get() = File(buildDir, "intermediates/data_binding_artifact/$buildVariant")
    val applicationDataBindingIntoTypeDir get() = File(buildDir, "intermediates/data_binding_layout_info_type_merge/$buildVariant")
    val libraryDataBindingIntoTypeDir get() = File(buildDir, "intermediates/data_binding_layout_info_type_package/$buildVariant")

    val mappingFile get() = File(buildDir, "outputs/mapping/$buildVariant/mapping.txt")
    val usageFile get() = File(buildDir, "outputs/mapping/$buildVariant/usage.txt")
    val aabResGuardMappingFile get() = File(buildDir, "outputs/bundle/$buildVariant/resources-mapping.txt")

    private val customClasspathFiles get() = customClasspath?.map { File(moduleRootDir, it) } ?: emptyList()
    private val customSyncFiles get() = customSyncFilePath?.map { File(moduleRootDir, it) } ?: emptyList()

    val syncToLocalPathList get() = customSyncFiles + listOf(generatedSourcePath)

    val allClassPath get() = customClasspathFiles + listOf(kotlinClassPath, javaClassPath, rFilePath, kotlinClassPathForJavaLibrary, javaClassPathForJavaLibrary, libraryRFilePathInLowAgp)

    // use to fetch all class path after full build
    val allBuildPaths get() = listOf(kotlinClassPath, javaClassPathNew, javaClassPathOld, rFilePathDir,
        kotlinClassPathForJavaLibrary, javaClassPathForJavaLibrary, generatedSourcePath,
        oldLibraryMergedManifestDir, libraryMergedManifestDir, applicationMergedManifestDir, libraryRFileDirInLowAgp,
        dataBindingInfoDir, dataBindingDependencyInfoDir, dataBindingArtifactDir,
        applicationDataBindingIntoTypeDir, libraryDataBindingIntoTypeDir,
        mappingFile, usageFile, aabResGuardMappingFile
    ) + customClasspathFiles + customSyncFiles

    val allBuildPathRelative get() = allBuildPaths.map { it.relativeTo(projectRootDir) }

    val modulePathRelative get() = moduleRootDir.relativeTo(projectRootDir)

    private fun File.findManifestCandidates(): List<File> {
        return listOf(
            File(this, "process${buildVariant.camelCompat}Manifest/AndroidManifest.xml"),
            File(this, "AndroidManifest.xml"),
        ).filter { it.exists() } + this.listFilesRecursively().filter { it.name == "AndroidManifest.xml" }
    }

    private fun List<File>.newestFile(): File? {
        var newestFile: File? = null
        for (file in this.distinctByAbsolutePath()) {
            val currentNewestFile = newestFile
            if (currentNewestFile == null || file.lastModified() > currentNewestFile.lastModified()) {
                newestFile = file
            }
        }
        return newestFile
    }

    private fun List<File>.distinctByAbsolutePath(): List<File> {
        val result = mutableListOf<File>()
        val paths = mutableSetOf<String>()
        for (file in this) {
            if (paths.add(file.absolutePath)) {
                result.add(file)
            }
        }
        return result
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
    }
}

/**
 * One resolved library artifact with lightweight file fingerprint helpers.
 */
data class LibraryDependency(
    val name: String,
    val file: File,
    val lastModifiedTime: Long,
    val crc32: Long
) : Dependency {

    // secondary constructor provides defaults to avoid Kotlin 1.5 script codegen crash:
    // primary constructor default values referencing top-level functions in .kts files
    // trigger "Error generating constructors" in Kotlin 1.5 (Gradle 7 / AGP 3.5)
    constructor(name: String, file: File) : this(name, file, file.lastModified(), computeCrc32(file))

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
        private fun computeCrc32(file: File): Long {
            if (!file.exists()) {
                return -1L
            }
            if (file.isDirectory) {
                return -2L
            }
            return crc32Digest.run {
                reset()
                update(file.readBytes())
                value
            }
        }
    }
}

/**
 * Reference to another module by module name.
 */
data class ModuleDependency(
    val moduleName: String,
) : Dependency {

    override fun toString(): String {
        return moduleName
    }
}

/**
 * Run-configuration options for one Android module.
 */
data class AndroidRunConfig(
    val moduleName: String,
    val variants: List<Variant>,
    val signingConfigList: List<SigningConfig>,
)

/**
 * Build variant descriptor and optional signing-config name.
 */
data class Variant(
    val name: String,
    val signingConfigName: String?,
)

/**
 * Signing material and flags for APK signing tasks.
 */
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

/**
 * Dependency is a marker interface for dependency model entries.
 */
interface Dependency
