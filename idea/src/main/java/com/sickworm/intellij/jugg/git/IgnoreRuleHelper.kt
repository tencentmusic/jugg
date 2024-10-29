package com.sickworm.intellij.jugg.git

import org.gradle.internal.impldep.org.eclipse.jgit.ignore.FastIgnoreRule
import org.gradle.internal.impldep.org.eclipse.jgit.lib.Repository
import java.io.File

class IgnoreRuleHelper(
    private val workTree: File,
    private val fastIgnoreRules: List<FastIgnoreRule>,
) {

    fun isIgnored(file: File): Boolean {
        val path = file.relativeTo(workTree).path
        return fastIgnoreRules.any { it.isMatch(path, file.isDirectory) }
    }

    companion object {

        private var cache: IgnoreRuleHelper? = null
        private var cacheTime = 0L

        fun get(repository: Repository?): IgnoreRuleHelper {
            if (repository == null) {
                return IgnoreRuleHelper(File(""), emptyList())
            }
            val ignoreFile = File(repository.workTree, ".gitignore")
            if (!ignoreFile.exists()) {
                return IgnoreRuleHelper(repository.workTree, emptyList())
            }

            return get(repository.workTree, ignoreFile)
        }

        fun get(workTree: File, ignoreFile: File): IgnoreRuleHelper {
            val cache = cache
            val lastModified = ignoreFile.lastModified()
            if (cache != null && cacheTime == lastModified) {
                return cache
            }

            val rules = ignoreFile.readLines()
                .map { it.trim() }
                .map { FastIgnoreRule(it) }
            val helper = IgnoreRuleHelper(workTree, rules)
            this.cacheTime = lastModified
            this.cache = helper

            return helper
        }
    }
}