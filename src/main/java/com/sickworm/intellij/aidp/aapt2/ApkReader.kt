package com.sickworm.intellij.aidp.aapt2

import com.intellij.openapi.diagnostic.Logger
import java.io.File
import java.lang.IllegalStateException

class ApkReader(
    private val apkFile: File,
    private val logger: Logger
) {

    private val aapt2Invoker = Aapt2DaemonInvoker(logger)

    private var apkResInfo: ApkResInfo? = null

    fun getRFile(outputDir: File) {
        val apkResInfo = parse() ?: throw IllegalStateException("parse apk failed")

        var rFile = TEMPLATE.replaceFirst("#$PACKAGE_NAME#", apkResInfo.packageName)
        listOf(
            ANIM, ATTR, BOOL, COLOR, DIMEN, DRAWABLE, ID, STRING, STYLE, STYLEABLE
        ).forEach { type ->
            val group = apkResInfo.groupList.find { it.type == type }
            val typeBlock = "#${type}#"
            rFile = if (group == null) {
                rFile.replaceFirst(typeBlock, "")
            } else {
                val fields = group.itemList.joinToString(separator = "\n") {
                    val name = it.name.split("/")[1].replace(".", "_")
                    val id = it.id.toString(16)
                    "        public static final int $name = 0x$id;"
                }
                rFile.replaceFirst(typeBlock, fields)
            }
        }

        val outputDirWithClassPath = File(outputDir, apkResInfo.packageName.replace(".", "/"))
        outputDirWithClassPath.mkdirs()
        val outputFile = File(outputDirWithClassPath, "R.java")
        outputFile.writeText(rFile)
    }

    fun parse(): ApkResInfo? {
        val result = aapt2Invoker.invoke("dump resources ${apkFile.absolutePath}")
        if (!result.isSuccess) {
            logger.warn(result.errorOutput)
            return null
        }

        return try {
            doParse(result.output)
        } catch (e: Exception) {
            logger.warn("analyze failed", e)
            null
        }
    }

    private fun doParse(dumpResource: String): ApkResInfo? {
        val lines = dumpResource.split("\n")
        if (lines[0] != "Binary APK") {
            logger.warn("first line not Binary APK, actual: ${lines[0]}")
            return null
        }
        val packages = lines[1].split(" ")
        if (packages.size != 3 ||
            packages[0] != "Package" ||
            !packages[1].startsWith("name=") ||
            !packages[2].startsWith("id=")) {
            logger.warn("second line invalid Package format, actual: ${lines[1]}")
            return null
        }
        val packageName = packages[1].substring("name=".length)
        val packageId = packages[2].substring("id=".length).toInt(16)

        val resGroups = mutableListOf<ResGroup>()
        var currentGroupIds = mutableListOf<ResId>()
        var currentIdItems: MutableList<ResItem>

        for (index in 2 until lines.size) {
            val line = lines[index]
            val layer = line.layer
            val values = line.substring(layer).split(" ")
            when (layer) {
                0 -> {}
                2 -> {
                    // group
                    if (values.size != 4 ||
                        values[0] != "type" ||
                        !values[2].startsWith("id=") ||
                        !values[3].startsWith("entryCount=")) {
                        logger.warn("second line invalid group format, actual: $line")
                        return null
                    }

                    currentGroupIds = mutableListOf()
                    val resGroup = ResGroup(values[1],
                        values[2].substring("id=".length).toInt(16),
                        values[3].substring("entryCount=".length).toInt(),
                        currentGroupIds)
                    resGroups.add(resGroup)
                }
                4 -> {
                    // id
                    if (values.size != 3 ||
                        values[0] != "resource" ||
                        !values[1].startsWith("0x")
                    ) {
                        logger.warn("second line invalid id format, actual: $line")
                        return null
                    }
                    currentIdItems = mutableListOf()
                    val resId = ResId(
                        values[0],
                        values[1].substring("0x".length).toInt(16),
                        values[2],
                        currentIdItems
                    )
                    currentGroupIds.add(resId)
                }
                6 -> {
                    // item
                }
                8 -> {
                    // unknown
                }
                else -> {
                    logger.warn("invalid layer $layer $line")
                    return null
                }
            }
        }

        return ApkResInfo(packageName, packageId, resGroups)
    }

    private inline val String.layer: Int get() {
        var spaceCount = 0
        forEach {
            if (it == ' ') spaceCount++
            else return spaceCount
        }
        return spaceCount
    }

    companion object {
        private const val PACKAGE_NAME = "package"
        private const val ANIM = "anim"
        private const val ATTR = "attr"
        private const val BOOL = "bool"
        private const val COLOR = "color"
        private const val DIMEN = "dimen"
        private const val DRAWABLE = "drawable"
        private const val ID = "id"
        private const val STRING = "string"
        private const val STYLE = "style"
        private const val STYLEABLE = "styleable"

        private const val TEMPLATE = """
/* AUTO-GENERATED FILE.  DO NOT MODIFY.
 *
 * This class was automatically generated by the
 * gradle plugin from the resource data it found. It
 * should not be modified by hand.
 */
package #$PACKAGE_NAME#;

public final class R {
    private R() {}

    public static final class anim {
        private anim() {}

#$ANIM#
    }
    public static final class attr {
        private attr() {}

#$ATTR#
    }
    public static final class bool {
        private bool() {}

#$BOOL#
    }
    public static final class color {
        private color() {}

#$COLOR#
    }
    public static final class dimen {
        private dimen() {}

#$DIMEN#
    }
    public static final class drawable {
        private drawable() {}

#$DRAWABLE#
    }
    public static final class id {
        private id() {}

#$ID#
    }
    public static final class string {
        private string() {}

#$STRING#
    }
    public static final class style {
        private style() {}

#$STYLE#
    }
    public static final class styleable {
        private styleable() {}

#$STYLEABLE#
    }
}

"""
    }
}