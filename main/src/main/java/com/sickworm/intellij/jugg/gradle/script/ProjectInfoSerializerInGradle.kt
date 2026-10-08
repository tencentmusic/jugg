package com.sickworm.intellij.jugg.gradle.script

import com.sickworm.intellij.jugg.project.data.*
import groovy.json.JsonBuilder
import groovy.json.JsonGenerator
import groovy.json.JsonSlurper
import java.io.File


/**
 * ProjectInfoSerializerInGradle persists and restores compact project info within Gradle scripts.
 */
class ProjectInfoSerializerInGradle(private val dataFile: File) {

    @Synchronized
    fun save(projectInfo: JuggProjectInfo) {
//        val startTime = System.currentTimeMillis()

        dataFile.parentFile?.mkdirs()
        val juggProjectInfoSerialize = JuggProjectInfoSerialize.serialize(projectInfo)
        val generator = getJsonGenerator()
        val builder = JsonBuilder(juggProjectInfoSerialize, generator)
        val result = builder.toString()
        dataFile.writeText(result)

//        val costTime = System.currentTimeMillis() - startTime
//        println("Jugg: Save project info to ${dataFile.absolutePath} cost $costTime ms")
    }

    @Suppress("UNCHECKED_CAST")
    @Synchronized
    fun load(): JuggProjectInfoSerialize? {
        if (!dataFile.exists()) {
            return null
        }
        try {
//            val startTime = System.currentTimeMillis()
            val jsonSlurper = JsonSlurper()
            var juggProjectInfoSerialize: JuggProjectInfoSerialize? = null
            dataFile.inputStream().use { inputStream ->
                // we can not invoke gson in init.gradle.kts, so...
                // JsonSlurper is not a ORM tool, so we just read what we need: build variant, library dependency
                val json = jsonSlurper.parse(inputStream) as Map<String, Any> // JuggProjectInfoSerialize
                val modules: List<ModuleInfoSerialize> = (json["modules"] as List<Map<String, Any>>).map {
                    val module = it["moduleInfoExceptLibraries"] as Map<String, Any>
                    val projectRootDir = File(module["projectRootDir"] as String)
                    val moduleRootDir = File(module["moduleRootDir"] as String)
                    val buildVariant = module["buildVariant"] as String
                    val buildPath = module["buildPathInfo"] as? Map<String, Any>
                    val moduleInfo = ModuleInfo.virtualModule.copy(
                        name = module["name"] as String,
                        buildVariant = buildVariant,
                        moduleRootDir = moduleRootDir,
                        projectRootDir = projectRootDir,
                        kotlinCommonSourceDirs = (module["kotlinCommonSourceDirs"] as? List<String>)?.map(::File)
                            ?: emptyList(),
                        kotlinFragmentSourceDirs = (module["kotlinFragmentSourceDirs"] as? Map<String, List<String>>)
                            ?.mapValues { (_, paths) -> paths.map(::File) }
                            ?: emptyMap(),
                        kotlinFragmentRefines = module["kotlinFragmentRefines"] as? Map<String, List<String>>
                            ?: emptyMap(),
                        kotlinDefaultFragmentName = module["kotlinDefaultFragmentName"] as? String,
                        buildPathInfo = ModuleBuildPathInfo(
                            projectRootDir,
                            moduleRootDir,
                            buildVariant,
                            buildDirRelativePath = buildPath?.get("buildDirRelativePath") as? String
                                ?: ""
                        ),
                        moduleType = ModuleInfo.Type.valueOf(module["moduleType"] as String),
                        runtimeModuleDependencies = (module["runtimeModuleDependencies"] as? List<Map<String, Any>>)
                            ?.mapNotNull { dependency ->
                                (dependency["moduleName"] as? String)?.let(::ModuleDependency)
                            },
                        instrumentationTargetPackage = module["instrumentationTargetPackage"] as? String,
                        composeResourceInfo = parseComposeResourceInfo(module["composeResourceInfo"]),
                        externalBuildInfos = parseExternalBuildInfos(module["externalBuildInfos"]),
                    )
                    ModuleInfoSerialize(
                        moduleInfo,
                        it["libraryDependencies"] as? List<Int>,
                        it["runtimeLibraryDependencies"] as? List<Int>,
                        it["annotationProcessorDependencies"] as? List<Int>,
                        it["kaptDependencies"] as? List<Int>,
                        it["kotlinPlugins"] as? List<Int>,
                        it["kotlinExtensions"] as? List<Int>,
                        it["kspDependencies"] as? List<Int>,
                    )
                }
                val dependencyList = (json["dependencyList"] as List<Map<String, Any>>).map {
                    LibraryDependency(
                        name = it["name"] as String,
                        file = File(it["file"] as String),
                        lastModifiedTime = (it["lastModifiedTime"] as Number).toLong(),
                        crc32 = (it["crc32"] as Number).toLong(), // you will get Int and Long, so convert to Number
                        rPackageName = it["rPackageName"] as? String, // missing field in old project info
                    )
                }
                val rootInfo = json["juggProjectInfoExceptModules"] as? Map<String, Any>
                juggProjectInfoSerialize = JuggProjectInfoSerialize(
                    juggProjectInfoExceptModules = JuggProjectInfo(
                        modules = modules.associate {
                            it.moduleInfoExceptLibraries.name to it.moduleInfoExceptLibraries
                        },
                        agpR8Classpath = (rootInfo?.get("agpR8Classpath") as? String)?.let(::File),
                    ),
                    modules = modules,
                    dependencyList = dependencyList)
            }
//            val costTime = System.currentTimeMillis() - startTime
//            println("Jugg: Load project info to ${dataFile.absolutePath} cost $costTime ms")
            return juggProjectInfoSerialize
        } catch (e: Exception) {
            println("Jugg: Failed to load project info from ${dataFile.absolutePath}, $e")
            printException(e)
            return null
        }
    }

    /**
     * Reads the external build records of one module. Input directories are only accepted in the
     * `{directory, filterRules}` form: a snapshot written by an older Jugg version can not describe
     * which file kinds its roots accept, and guessing them would restore the false positives this
     * schema exists to prevent. The caller treats the failure as unavailable project info and one
     * full Gradle build rewrites the snapshot.
     */
    @Suppress("UNCHECKED_CAST")
    private fun parseExternalBuildInfos(value: Any?): List<ExternalBuildInfo> {
        return (value as? List<Map<String, Any>>).orEmpty().mapNotNull { info ->
            val type = (info["type"] as? String)?.let {
                runCatching { ExternalBuildType.valueOf(it) }.getOrNull()
            } ?: return@mapNotNull null
            val rawInputDirs = info["inputDirs"] as? List<Any>
                ?: throw IllegalStateException(EXTERNAL_BUILD_INPUT_SCHEMA_ERROR)
            val inputDirs = rawInputDirs.map { element ->
                val dir = element as? Map<String, Any> ?: throw IllegalStateException(EXTERNAL_BUILD_INPUT_SCHEMA_ERROR)
                val directory = (dir["directory"] as? String)?.let(::File)
                    ?: throw IllegalStateException(EXTERNAL_BUILD_INPUT_SCHEMA_ERROR)
                val rules = (dir["filterRules"] as? List<String>).orEmpty().mapNotNull { name ->
                    runCatching { ExternalBuildInputFilterRule.valueOf(name) }.getOrNull()
                }
                if (rules.isEmpty()) throw IllegalStateException(EXTERNAL_BUILD_INPUT_SCHEMA_ERROR)
                ExternalBuildInputDir(directory, rules.toSet())
            }
            if (inputDirs.isEmpty()) return@mapNotNull null
            // Snapshots written before the outputs were unified are restored here: legacy Flutter kept
            // the assets directory in outputDir and the native output in nativeLibsArchive (a Jar) or
            // nativeLibsDir (a jniLibs directory); legacy C++ kept its native output in outputDir.
            val legacyOutputDir = (info["outputDir"] as? String)?.let(::File)
            val isFlutter = type == ExternalBuildType.Flutter
            ExternalBuildInfo(
                type = type,
                inputDirs = inputDirs,
                taskPath = info["taskPath"] as? String,
                assetsOutputDir = (info["assetsOutputDir"] as? String)?.let(::File)
                    ?: legacyOutputDir?.takeIf { isFlutter },
                nativeOutput = (info["nativeOutput"] as? String)?.let(::File) ?: if (isFlutter) {
                    (info["nativeLibsArchive"] as? String)?.let(::File)
                        ?: (info["nativeLibsDir"] as? String)?.let(::File)
                } else {
                    legacyOutputDir
                },
                unsupportedReason = info["unsupportedReason"] as? String,
                configFiles = (info["configFiles"] as? List<String>).orEmpty().map(::File),
                excludedDirs = (info["excludedDirs"] as? List<String>).orEmpty().map(::File),
                prerequisites = parseExternalBuildPrerequisites(info["prerequisites"]),
            )
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseExternalBuildPrerequisites(value: Any?): List<ExternalBuildPrerequisite> {
        return (value as? List<Map<String, Any>>).orEmpty().mapNotNull { entry ->
            val taskPath = entry["taskPath"] as? String ?: return@mapNotNull null
            val triggerGlobs = (entry["triggerGlobs"] as? List<String>).orEmpty().filter { it.isNotEmpty() }
            if (taskPath.isEmpty() || triggerGlobs.isEmpty()) {
                return@mapNotNull null
            }
            val prefixes = (entry["beforeNativeTaskPrefixes"] as? List<String>).orEmpty()
                .filter { it.isNotEmpty() }
                .ifEmpty { listOf("merge", "buildCMake", "externalNativeBuild") }
            ExternalBuildPrerequisite(
                taskPath = taskPath,
                triggerGlobs = triggerGlobs,
                generatedSourceDirs = parseGeneratedSourceDirs(entry["generatedSourceDirs"]),
                beforeNativeTaskPrefixes = prefixes,
            )
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseGeneratedSourceDirs(value: Any?): List<ExternalBuildGeneratedSourceDir> {
        return (value as? List<Map<String, Any>>).orEmpty().mapNotNull { entry ->
            val directory = readPrerequisiteFile(entry["directory"]) ?: return@mapNotNull null
            val language = (entry["language"] as? String ?: entry["type"] as? String)?.let {
                runCatching { ExternalBuildGeneratedLanguage.valueOf(it) }.getOrNull()
            } ?: return@mapNotNull null
            ExternalBuildGeneratedSourceDir(directory, language)
        }
    }

    private fun readPrerequisiteFile(value: Any?): File? {
        return when (value) {
            is File -> value
            is String -> File(value)
            else -> null
        }
    }

    @Suppress("UNCHECKED_CAST")
    private fun parseComposeResourceInfo(value: Any?): ComposeResourceInfo? {
        val composeInfo = value as? Map<String, Any> ?: return null
        val classpath = (composeInfo["generatorClasspath"] as? List<String>)?.map(::File) ?: return null
        val resourceDirectories = (composeInfo["resourceDirectories"] as? List<Map<String, Any>>)?.map {
            ComposeResourceDirectory(
                sourceSetName = it["sourceSetName"] as? String ?: return null,
                directory = File(it["directory"] as? String ?: return null),
            )
        } ?: return null
        return ComposeResourceInfo(
            generatorClasspath = classpath,
            packageName = composeInfo["packageName"] as? String ?: return null,
            publicResClass = composeInfo["publicResClass"] as? Boolean ?: return null,
            resourceDirectories = resourceDirectories,
            assetRelativePath = composeInfo["assetRelativePath"] as? String ?: return null,
            resClassName = composeInfo["resClassName"] as? String ?: "Res",
            generateResourceContentHash = composeInfo["generateResourceContentHash"] as? Boolean ?: false,
            usesLegacyGenerator = composeInfo["usesLegacyGenerator"] as? Boolean ?: false,
            supportStatus = (composeInfo["supportStatus"] as? String)
                ?.let(ComposeResourceSupportStatus::valueOf)
                ?: ComposeResourceSupportStatus.Supported,
            unsupportedReason = composeInfo["unsupportedReason"] as? String
        )
    }

    companion object {

        fun getJsonGenerator() : JsonGenerator {
            val fileConverter = object : JsonGenerator.Converter {
                override fun handles(p0: Class<*>?): Boolean {
                    return p0 == File::class.java
                }

                override fun convert(p0: Any?, p1: String?): Any? {
                    return (p0 as File).path
                }
            }
            // JsonGenerator will create "valid", "res" which are getter property, so we manually handle it there
            val libraryConverter = object : JsonGenerator.Converter {
                override fun handles(p0: Class<*>?): Boolean {
                    return p0 == LibraryDependency::class.java
                }

                override fun convert(p0: Any?, p1: String?): Any? {
                    val libraryDependency = p0 as? LibraryDependency ?: return "null"
                    val result = mutableMapOf<String, Any>()
                    result["name"] = libraryDependency.name
                    result["file"] = libraryDependency.file
                    result["lastModifiedTime"] = libraryDependency.lastModifiedTime
                    result["crc32"] = libraryDependency.crc32
                    // Only resources consume the namespace; avoid repeating it on manifest and jar entries.
                    if (libraryDependency.isRes) {
                        libraryDependency.rPackageName?.let {
                            result["rPackageName"] = it
                        }
                    }
                    return result
                }
            }
            val buildPathConverter = object : JsonGenerator.Converter {
                override fun handles(p0: Class<*>?): Boolean {
                    return p0 == ModuleBuildPathInfo::class.java
                }

                override fun convert(p0: Any?, p1: String?): Any? {
                    val moduleBuildPathInfo = p0 as? ModuleBuildPathInfo ?: return null
                    val result = mutableMapOf<String, Any>()
                    result["projectRootDir"] = moduleBuildPathInfo.projectRootDir
                    result["moduleRootDir"] = moduleBuildPathInfo.moduleRootDir
                    result["buildVariant"] = moduleBuildPathInfo.buildVariant
                    result["buildDirRelativePath"] = moduleBuildPathInfo.buildDirRelativePath
                    return result
                }
            }
            val generator = JsonGenerator.Options()
                .excludeFieldsByName("contentHash", "originalClassName")
                .excludeNulls()
                .addConverter(fileConverter)
                .addConverter(libraryConverter)
                .addConverter(buildPathConverter)
                .build()
            return generator
        }
    }
}
