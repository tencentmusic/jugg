package com.sickworm.intellij.jugg.project.data

import java.io.File
import java.util.zip.CRC32

data class JuggProjectInfo(
    val modules: Map<String, ModuleInfo>,
)

data class ModuleInfo(
    val name: String,
    val moduleRootDir: File,
    val projectRootDir: File,
    val sourceDirs: List<File>,
    val resourceDirs: List<File>,
    val assetsDirs: List<File>,
    val manifestFile: File?,
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
) {

    // e.g. name = example.lib_common.main
    // simpleName = lib_common
    val simpleName: String get() {
        val splits = name.split('.')
        return when (splits.size) {
            0 -> name
            1 -> name
            else -> splits[splits.size - 2]
        }
    }

    companion object {

        const val DEFAULT_BUILD_VARIANT = "debug"

        // virtual module that not physical exists
        val virtualModule = ModuleInfo(
            name = "virtual_module",
            moduleRootDir = File(""),
            projectRootDir = File(""),
            sourceDirs = emptyList(),
            resourceDirs = emptyList(),
            assetsDirs = emptyList(),
            manifestFile = null,
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
) {

    /** build root dir */
    val buildDir: File get() = File(moduleRootDir, "build")

    /** java class path */
    private val javaClassPathNew get() = File(buildDir, "intermediates/javac/$buildVariant/classes")
    /** on AGP 3.2.1 has different java class path */
    private val javaClassPathOld get() = File(
        buildDir,
        "intermediates/javac/$buildVariant/compileDebugJavaWithJavac/classes"
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

    // compatible with gradle 8.x, which path like merged_manifests/debug/processDebugManifest/AndroidManifest.xml
    val mergedManifest get() = listOf(oldLibraryMergedManifestDir, libraryMergedManifestDir, applicationMergedManifestDir)
        .firstNotNullOfOrNull { it.findManifestInDir() } ?: File(libraryMergedManifestDir, "AndroidManifest.xml")

    val allClassPath get() = listOf(javaClassPathNew, javaClassPathOld, rFilePath, kotlinClassPath, javaClassPathForJavaLibrary, kotlinClassPathForJavaLibrary)

    // use to fetch all class path after full build
    val allBuildPathRelative get() = listOf(javaClassPathNew, javaClassPathOld, rFilePathDir, kotlinClassPath,
        javaClassPathForJavaLibrary, kotlinClassPathForJavaLibrary, generatedSourcePath,
        oldLibraryMergedManifestDir, libraryMergedManifestDir, applicationMergedManifestDir
    ).map { it.relativeTo(moduleRootDir) }

    val modulePathRelative get() = moduleRootDir.relativeTo(projectRootDir)

    private fun File.findManifestInDir(): File? {
        return File(this, "AndroidManifest.xml").takeIf(File::exists)
            ?: File(this, "process${buildVariant.camel}Manifest/AndroidManifest.xml").takeIf(File::exists)
            ?: this.listFilesRecursively().find { it.name == "AndroidManifest.xml" }
    }

    private val String.camel: String get() {
        return this.replaceFirstChar { it.uppercaseChar() }
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
    }
}

data class LibraryDependency(
    val name: String,
    val file: File,
    val lastModifiedTime: Long = file.lastModified(),
    val crc32: Long = file.crc32
) {

    val isValid get() = file.exists()

    val isRes get() = file.name == "res"

    val isAndroidManifest get() = file.name == "AndroidManifest.xml"

    val isJar get() = file.extension == "jar"

    companion object {

        private val crc32Digest = CRC32()

        private val File.crc32: Long get() {
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
)