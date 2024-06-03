package com.sickworm.intellij.jugg.gradle.script


object TraceLogger {

    @Suppress("MemberVisibilityCanBePrivate")
    var enabled = false

    private const val MIN_COST_PRINT_MS = 100

    private val notDumpTagMap: MutableMap<String, Long> = mutableMapOf()

    private val costTimeMap: MutableMap<String, Long> = mutableMapOf()

    fun start(tag: String) {
        if (!enabled) return
        notDumpTagMap[tag] = System.currentTimeMillis()
    }

    fun end(tag: String) {
        if (!enabled) return
        val startTime = notDumpTagMap.remove(tag) ?: run {
            println("$indent$tag:-1ms")
            return
        }
        val costTime = System.currentTimeMillis() - startTime
        if (costTime >= MIN_COST_PRINT_MS) {
            println("$indent$tag:${costTime}ms")
        }

        costTimeMap[tag] = (costTimeMap[tag] ?: 0) + costTime
    }

    private val indent: String get()  {
        val currentLevel = notDumpTagMap.size
        val indent = "--".repeat(currentLevel)
        return indent
    }

    fun printAllCost() {
        if (!enabled) return
        println("cost time:")
        costTimeMap.entries.sortedBy { -it.value }.map {
            if (it.value >= MIN_COST_PRINT_MS) {
                println("${"%-30s".format(it.key)}: ${it.value}ms")
            }
        }
    }
}