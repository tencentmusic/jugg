package com.sickworm.intellij.jugg.compiler.source.kotlin

import com.intellij.openapi.diagnostic.Logger
import com.sickworm.intellij.jugg.gradle.script.Reflector
import com.sickworm.intellij.jugg.gradle.script.get
import java.io.File

class KotlinConstRefReader(
    private val logger: Logger,
) {

    fun read(lookupsDir: File, relativeSourceFiles: List<String>): Map<String, Set<String>> {
        val map = processLookups(lookupsDir)!!
        val result = mutableMapOf<String, MutableSet<String>>()
        processKotlinFiles(relativeSourceFiles, this::class.java.classLoader, emptyMap(), map, result)
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

            // read key.scopeHash
            val scopeHash = try {
                val scopeHashField = key::class.java.getDeclaredField("scopeHash").apply { isAccessible = true }
                scopeHashField.get(key).toString()
            } catch (e: NoSuchFieldException) {
                // try getter：key.getScopeHash()
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

    private fun processKotlinFiles(
        kotlinFilesPath: Collection<String>,
        kotlinClassLoader: ClassLoader,
        srcDirReplacements: Map<String, String>,
        filesByScopeHash: Map<String, Set<String>>,
        graph: MutableMap<String, MutableSet<String>>
    ) {
        for (it in kotlinFilesPath) {
            val scopeHash = it.scopeHash(kotlinClassLoader, srcDirReplacements).toString()

            val foundRefs = filesByScopeHash[scopeHash] ?: continue

            val matchGraphClass = it.pathToClass(false)

            val refBy = graph.getOrPut(matchGraphClass) { mutableSetOf() }
            foundRefs.forEach { ref ->
                val path = ref.pathToClass(true)
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
        val withDotPath = this.pathToFqName()

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

    private val pathPrefixStartIndex = "\$PROJECT_DIR\$/".length
    private val pathEndIndex = ".kt".length
    private fun String.pathToClass(hasPrefix: Boolean): String {
        val pathStartIndex = if (hasPrefix) pathPrefixStartIndex else 0
        val needLength = this.length - pathStartIndex - pathEndIndex
        val charLength = 1 + needLength + 1
        val chars = CharArray(charLength)
        chars[0] = 'L'
        for (index in 0 until needLength) {
            val char = this[pathStartIndex + index]
            chars[index + 1] = char
        }
        chars[charLength - 1] = ';'
        return String(chars)
    }

    private fun String.pathToFqName(): String {
        val charLength = this.length
        val startIndex = 0
        val chars = CharArray(charLength)
        for (index in 0 until charLength) {
            var char = this[startIndex + index]
            if (char == '/') {
                char = '.'
            }
            chars[index] = char
        }
        return String(chars)
    }

}