package com.sickworm.intellij.jugg.compiler.source.kotlin

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.gradle.script.Reflector
import com.sickworm.intellij.jugg.gradle.script.get
import java.io.File

class KotlinConstRefReader(
    private val logger: Logger,
) {

    fun read(lookupsDir: File, sourceDir: List<String>): Map<String, GraphNode> {
        val map = processLookups(lookupsDir)!!
        val result = mutableMapOf<String, GraphNode>()
        processKotlinFiles(sourceDir, this::class.java.classLoader, emptyMap(), map, result)
        return result
    }

    private fun processLookups(
        lookupsDir: File,
    ): Map<String, MutableSet<String>>? {
        val filesByScopeHash = mutableMapOf<String, MutableSet<String>>()

        val idToFileTabFile = File(lookupsDir, "id-to-file.tab")
        if (!idToFileTabFile.exists()) {
            logger.debug("id-to-file.tab not exists: $idToFileTabFile")
            return null
        }

        val icContext = Reflector.newInstance("org.jetbrains.kotlin.incremental.IncrementalCompilationContext")
        if (icContext?.value == null) {
            logger.debug("failed to construct IncrementalCompilationContext, icContext: $icContext")
            return null
        }

        val lookupsTabFile = File(lookupsDir, "lookups.tab")
        val lookups = Reflector.newInstance(
            "org.jetbrains.kotlin.incremental.storage.LookupMap",
            lookupsTabFile, icContext.value
        )
        if (lookups?.value == null) {
            logger.debug("failed to construct LookupMap, lookups: $lookups")
            return null
        }
        val lookupsStorage = lookups.fieldP("storage")
        if (lookupsStorage?.value == null) {
            logger.debug("failed to construct LookupMap, lookups: $lookups")
            return null
        }

        val idToFileMap = Reflector.newInstance(
            "org.jetbrains.kotlin.incremental.storage.IdToFileMap",
            idToFileTabFile, icContext.value
        )
        val idToFileStorage = idToFileMap?.fieldP("storage")
        if (idToFileStorage?.value == null) {
            logger.debug("failed to construct IdToFileMap, idToFileMap: $idToFileMap")
            return null
        }

        // 遍历 keys，对 values 逐项取出文件路径并按 scopeHash 聚合
        @Suppress("UNCHECKED_CAST")
        val keys = lookupsStorage["keys"]?.value as? Collection<Any>
        if (keys == null) {
            logger.debug("failed to get keys from lookupsStorage, lookupsStorage: $lookupsStorage")
            return null
        }
        for (key in keys) {
            val value = lookupsStorage.invoke("get", Reflector.Value(Object::class.java, key))?.value
            if (value == null) {
                logger.debug("failed to get value from lookupsStorage, key: $key")
                return null
            }

            // 读取 key.scopeHash
            val scopeHash = try {
                val scopeHashField = key::class.java.getDeclaredField("scopeHash").apply { isAccessible = true }
                scopeHashField.get(key).toString()
            } catch (e: NoSuchFieldException) {
                // 如果字段名或可见性不同，可改为调用 getter：key.getScopeHash()
                val maybeGetter = key::class.java.methods.firstOrNull { it.name == "getScopeHash" && it.parameterCount == 0 }
                (maybeGetter?.invoke(key) ?: "UNKNOWN").toString()
            }

            if (value is Collection<*>) {
                val filesSameScopeHash = filesByScopeHash.getOrPut(scopeHash) { mutableSetOf() }
                for (subValue in value) {
                    val filePath = idToFileStorage.invoke("get", Reflector.Value(Object::class.java, subValue!!))?.value?.toString()
                    if (filePath != null) {
                        filesSameScopeHash.add(filePath.toString())
                    }
                }
            }
        }

        return filesByScopeHash
    }

    data class GraphNode(
        val refByClasses: MutableSet<String> = mutableSetOf(),
        val refToClasses: MutableSet<String> = mutableSetOf()
    )

    private fun replaceByMap(input: String, replacements: Map<String, String>): String {
        var out = input
        replacements.forEach { (k, v) -> out = out.replace(k, v) }
        return out
    }

    private fun processKotlinFiles(
        kotlinFilesPath: Iterable<String>,
        kotlinClassLoader: ClassLoader,
        srcDirReplacements: Map<String, String>,
        filesByScopeHash: Map<String, Set<String>>,
        graph: MutableMap<String, GraphNode>
    ) {
        for (it in kotlinFilesPath) {
            val scopeHash = it.scopeHash(kotlinClassLoader, srcDirReplacements).toString()

            val foundRefs = filesByScopeHash[scopeHash] ?: continue

            val resultReplacements = HashMap<String, String>()
            resultReplacements.putAll(srcDirReplacements)
            resultReplacements[".kt"] = ""
            val matchGraphClass = replaceByMap(it, resultReplacements).removePrefix("/")


            val resolvedClass = graph.getOrPut(matchGraphClass) { GraphNode() }
            val refBy = resolvedClass.refByClasses
            foundRefs.forEach { ref ->
                val path = replaceByMap(ref, resultReplacements).removePrefix("/")
                val node = graph[path]
                if (node != null) {
                    node.refToClasses.add(matchGraphClass)
                }
                if (path != matchGraphClass) {
                    refBy.add(path)
                }
            }
        }
    }

    private fun String.scopeHash(kotlinClassLoader: ClassLoader, srcDirReplacements: Map<String, String>): Int {
        val fqNameReplacements = HashMap<String, String>()
        fqNameReplacements.putAll(srcDirReplacements)
        fqNameReplacements["/"] = "."
        val withDotPath = replaceByMap(this, fqNameReplacements).removePrefix(".")

        val fqNameClass = kotlinClassLoader.loadClass("org.jetbrains.kotlin.name.FqName")
        val fqName = fqNameClass.getConstructor(String::class.java).newInstance(withDotPath)
        val shortName = fqNameClass.getMethod("shortName").invoke(fqName)
        val identifier = shortName.javaClass.getMethod("getIdentifier").invoke(shortName) as String
        val parent = fqNameClass.getMethod("parent").invoke(fqName)
        val scopeStr = parent.javaClass.getMethod("asString").invoke(parent) as String

        val lookupSymbolClass = kotlinClassLoader.loadClass("org.jetbrains.kotlin.incremental.LookupSymbol")
        val lookupSymbol = lookupSymbolClass
            .getConstructor(String::class.java, String::class.java)
            .newInstance(identifier, scopeStr)
        val lookupName = lookupSymbolClass.getMethod("getName").invoke(lookupSymbol) as String
        val lookupScope = lookupSymbolClass.getMethod("getScope").invoke(lookupSymbol) as String

        val lookupSymbolKeyClass = kotlinClassLoader.loadClass("org.jetbrains.kotlin.incremental.storage.LookupSymbolKey")
        val lookupSymbolKey = lookupSymbolKeyClass
            .getConstructor(String::class.java, String::class.java)
            .newInstance(lookupName, lookupScope)
        val scopeHash = lookupSymbolKeyClass.getMethod("getScopeHash").invoke(lookupSymbolKey)
        return scopeHash as Int
    }
}