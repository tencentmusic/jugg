package com.sickworm.intellij.jugg.git

import org.eclipse.jgit.ignore.FastIgnoreRule
import org.eclipse.jgit.ignore.IgnoreNode
import java.io.File

class FileMatcher : IFileMatcher {

    private var node = IgnoreNode()
    private var rootDir: File? = null

    override fun init(rootDir: File?, rules: List<String>) {
        this.rootDir = rootDir
        node = IgnoreNode(rules.map { FastIgnoreRule(it) })
    }

    override fun isMatch(file: File, isDirectory: Boolean): Boolean {
        try {
            val rootDir = rootDir
            val path = if (rootDir != null) {
                file.relativeTo(rootDir).path
            } else {
                file.path
            }
            return isMatch(path)
        } catch (e: Exception) {
            return false
        }
    }

    override fun isMatch(relativePath: String, isDirectory: Boolean): Boolean {
        return node.isIgnored(relativePath, isDirectory) == IgnoreNode.MatchResult.IGNORED
    }
}