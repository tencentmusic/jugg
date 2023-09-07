package com.sickworm.intellij.jugg.project

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.compiler.LibraryDependency
import com.sickworm.intellij.jugg.compiler.ModuleBuildPathInfo
import com.sickworm.intellij.jugg.compiler.ModuleDependency
import com.sickworm.intellij.jugg.compiler.ModuleInfo
import java.io.File


class ProjectInfoSerializer(private val dataFile: File, private val logger: Logger) {

    private var memoryCache: Map<String, ModuleInfo>? = null

    @Synchronized
    fun save(modules: Map<String, ModuleInfo>) {
        val startTime = System.currentTimeMillis()

        dataFile.parentFile?.mkdirs()
        val serializeText = ProjectInfoSerialize.create(modules).serialize()
        dataFile.writeText(serializeText)
        memoryCache = modules

        val costTime = System.currentTimeMillis() - startTime
        logger.debug("Save project info to ${dataFile.absolutePath} cost $costTime ms")
    }

    @Synchronized
    fun load(): Map<String, ModuleInfo>? {
        if (!dataFile.exists()) {
            return null
        }
        if (memoryCache != null) {
            return memoryCache
        }
        @Suppress("LiftReturnOrAssignment")
        try {
            val startTime = System.currentTimeMillis()
            val dataString = dataFile.readText()
            val modules = ProjectInfoSerialize.deserialize(dataString)
            val costTime = System.currentTimeMillis() - startTime
            logger.debug("Load project info to ${dataFile.absolutePath} cost $costTime ms")
            memoryCache = modules
            return modules
        } catch (e: Exception) {
            logger.warn("Failed to load project info from ${dataFile.absolutePath}", e)
            return null
        }
    }
}

private class ProjectInfoSerialize(
    val serializeVersion: String = SERIALIZE_VERSION,
    val stringList: List<String>,
    val moduleInfos: List<ModuleInfoSerialize>,
) {

    fun serialize(): String {
        val stringBuilder = StringBuilder()
        stringBuilder.append(serializeVersion)
        stringBuilder.append("\n")
        stringBuilder.append(stringList.size)
        stringBuilder.append("\n")
        stringList.forEach { string ->
            stringBuilder.append(string)
            stringBuilder.append("\n")
        }
        stringBuilder.append(moduleInfos.size)
        stringBuilder.append("\n")
        moduleInfos.forEach {
            it.fill(stringBuilder)
            stringBuilder.append("\n")
        }
        return stringBuilder.toString()
    }

    companion object {

        private const val SERIALIZE_VERSION: String = "1"

        fun create(modules: Map<String, ModuleInfo>): ProjectInfoSerialize {
            val stringMap = mutableMapOf<String, Int>()
            var index = 0
            val moduleInfos = modules.values.map {
                ModuleInfoSerialize(
                    name = stringMap.getOrPut(it.name) { index++ },
                    projectRootDir = stringMap.getOrPut(it.moduleRootDir.absolutePath) { index++ },
                    rootDir = stringMap.getOrPut(it.projectRootDir.absolutePath) { index++ },
                    sourceDirs = it.sourceDirs.map { dir -> stringMap.getOrPut(dir.absolutePath) { index++ } },
                    resourceDirs = it.resourceDirs.map { dir -> stringMap.getOrPut(dir.absolutePath) { index++ } },
                    assetsDirs = it.assetsDirs.map { dir -> stringMap.getOrPut(dir.absolutePath) { index++ } },
                    compileVersion = stringMap.getOrPut(it.compileVersion ?: "null") { index++ },
                    buildToolsVersion = stringMap.getOrPut(it.buildToolsVersion ?: "null") { index++ },
                    kotlinJvmTarget = stringMap.getOrPut(it.kotlinJvmTarget ?: "null") { index++ },
                    javaSourceCompatibility = stringMap.getOrPut(it.javaSourceCompatibility ?: "null") { index++ },
                    javaTargetCompatibility = stringMap.getOrPut(it.javaTargetCompatibility ?: "null") { index++ },
                    buildPathInfo = Pair(
                        stringMap.getOrPut(it.buildPathInfo.projectRootDir.absolutePath) { index++ },
                        stringMap.getOrPut(it.buildPathInfo.moduleRootDir.absolutePath) { index++ }
                    ),
                    moduleDependencies = it.moduleDependencies.map { moduleDependency ->
                        stringMap.getOrPut(moduleDependency.moduleName) { index++ }
                    },
                    libraryDependencies = it.libraryDependencies.map { libraryDependency ->
                        stringMap.getOrPut(libraryDependency.file.absolutePath) { index++ }
                    },
                )
            }

            val stringList = ArrayList<String>(stringMap.size)
            stringMap.forEach { (string, index) ->
                stringList.add(index, string)
            }
            return ProjectInfoSerialize(
                stringList = stringList,
                moduleInfos = moduleInfos,
            )
        }

        fun deserialize(text: String): Map<String, ModuleInfo> {
            val lines = text.split("\n")
            val serializeVersion = lines[0]
            if (serializeVersion != SERIALIZE_VERSION) {
                throw IllegalArgumentException("Unsupported serialize version: $serializeVersion")
            }
            val stringListSize = lines[1].toInt()
            val stringList = lines.subList(2, 2 + stringListSize)
            val stringMap = mutableMapOf<String, String>()
            stringList.forEachIndexed { index, s ->
                stringMap[index.toString()] = s
            }

            val moduleInfosSize = lines[2 + stringListSize].toInt()
            val moduleInfoStrings = lines.subList(3 + stringListSize, 3 + stringListSize + moduleInfosSize)
            return moduleInfoStrings.associate {
                val parts = it.split(";")
                val moduleInfo = ModuleInfo(
                    name = stringMap[parts[0]]!!,
                    moduleRootDir = File(stringMap[parts[1]]!!),
                    projectRootDir = File(stringMap[parts[2]]!!),
                    sourceDirs = if (parts[3].isEmpty()) emptyList() else parts[3].split(",").map { dir -> File(stringMap[dir]!!) },
                    resourceDirs = if (parts[4].isEmpty()) emptyList() else parts[4].split(",").map { dir -> File(stringMap[dir]!!) },
                    assetsDirs = if (parts[5].isEmpty()) emptyList() else parts[5].split(",").map { dir -> File(stringMap[dir]!!) },
                    compileVersion = stringMap[parts[6]]!!.nullIfNull(),
                    buildToolsVersion = stringMap[parts[7]]!!.nullIfNull(),
                    kotlinJvmTarget = stringMap[parts[8]]!!.nullIfNull(),
                    javaSourceCompatibility = stringMap[parts[9]]!!.nullIfNull(),
                    javaTargetCompatibility = stringMap[parts[10]]!!.nullIfNull(),
                    buildPathInfo = ModuleBuildPathInfo(
                        File(stringMap[parts[11]]!!),
                        File(stringMap[parts[12]]!!)
                    ),
                    moduleDependencies = if (parts[13].isEmpty()) emptyList() else parts[13].split(",").map { moduleDependency ->
                        ModuleDependency(
                            moduleName = stringMap[moduleDependency]!!
                        )
                    },
                    libraryDependencies = if (parts[14].isEmpty()) emptyList() else parts[14].split(",").map { libraryDependency ->
                        LibraryDependency(
                            file = File(stringMap[libraryDependency]!!)
                        )
                    },
                )
                moduleInfo.name to moduleInfo
            }
        }

        private fun String.nullIfNull(): String? = if (this == "null") null else this
    }
}

private typealias StringIndex = Int

private class ModuleInfoSerialize(
    val name: StringIndex,
    val projectRootDir: StringIndex,
    val rootDir: StringIndex,
    val sourceDirs: List<StringIndex>,
    val resourceDirs: List<StringIndex>,
    val assetsDirs: List<StringIndex>,
    val compileVersion: StringIndex,
    val buildToolsVersion: StringIndex,
    val kotlinJvmTarget: StringIndex,
    val javaSourceCompatibility: StringIndex,
    val javaTargetCompatibility: StringIndex,
    val buildPathInfo: Pair<StringIndex, StringIndex>,
    val moduleDependencies: List<StringIndex>,
    val libraryDependencies: List<StringIndex>,
) {

    fun fill(stringBuilder: StringBuilder) {
        stringBuilder.append(name).append(";")
        stringBuilder.append(projectRootDir).append(";")
        stringBuilder.append(rootDir).append(";")
        stringBuilder.append(sourceDirs.joinToString(",")).append(";")
        stringBuilder.append(resourceDirs.joinToString(",")).append(";")
        stringBuilder.append(assetsDirs.joinToString(",")).append(";")
        stringBuilder.append(compileVersion).append(";")
        stringBuilder.append(buildToolsVersion).append(";")
        stringBuilder.append(kotlinJvmTarget).append(";")
        stringBuilder.append(javaSourceCompatibility).append(";")
        stringBuilder.append(javaTargetCompatibility).append(";")
        stringBuilder.append(buildPathInfo.first).append(";")
        stringBuilder.append(buildPathInfo.second).append(";")
        stringBuilder.append(moduleDependencies.joinToString(",")).append(";")
        stringBuilder.append(libraryDependencies.joinToString(","))
    }
}