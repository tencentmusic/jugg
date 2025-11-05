package com.sickworm.intellij.jugg.git

import org.eclipse.jgit.ignore.FastIgnoreRule
import org.eclipse.jgit.ignore.IgnoreNode
import java.io.File

class FileMatcher : IFileMatcher {

    private var node = IgnoreNode()
    private var rootDir: File? = null

    override fun init(rootDir: File, rules: List<String>) {
        this.rootDir = rootDir
        node = IgnoreNode(rules.map { FastIgnoreRule(it) })
    }

    override fun isMatch(file: File, isDirectory: Boolean): Boolean {
        val rootDir = rootDir ?: return false
        try {
            val path = file.relativeTo(rootDir).path
            return node.isIgnored(path, isDirectory) == IgnoreNode.MatchResult.IGNORED
        } catch (e: Exception) {
            return false
        }
    }
}