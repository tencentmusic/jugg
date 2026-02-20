package com.sickworm.intellij.jugg.deploy.data

/**
 * EffectedClassNode carries className, sourceFileName, effectedByClasses, and effectedType.
 */
data class EffectedClassNode(
    val className: String,
    val sourceFileName: String,
    val effectedByClasses: List<String>,
    val effectedType: EffectedType
) {
    companion object {
        const val SOURCE_NOT_FOUND = "source_not_found"
    }

    /**
     * EffectedType marks whether follow-up work should recompile source or re-dex class bytes.
     */
    enum class EffectedType {
        SOURCE, // need recompile source file
        CLASS, // need to redex class file
    }
}

val Collection<EffectedClassNode>.sources: List<EffectedClassNode> get() {
    return this.filter { it.effectedType == EffectedClassNode.EffectedType.SOURCE }
}

val Collection<EffectedClassNode>.classes: List<EffectedClassNode> get() {
    return this.filter { it.effectedType == EffectedClassNode.EffectedType.CLASS }
}
