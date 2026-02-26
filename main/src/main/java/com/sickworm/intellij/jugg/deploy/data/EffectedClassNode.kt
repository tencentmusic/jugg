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
        const val SUFFIX = "_jugg_fix"  // Unified management of rename suffix
    }

    /**
     * EffectedType marks whether follow-up work should recompile source or re-dex class bytes.
     */
    enum class EffectedType {
        SOURCE, // need recompile source file

        /**
         * The implementation of an inlined method has changed
         *
         * When R8 inlines a method into caller classes, if the inlined method
         * implementation changes, all classes containing the inlined code will be affected.
         *
         * Phase 1: Used only for detection and logging
         * Phase 2: Will be used to generate _jugg_fix classes and redirect calls
         */
        INLINE_IMPL_CHANGE,
    }
}

val Collection<EffectedClassNode>.sources: List<EffectedClassNode> get() {
    return this.filter {
        it.effectedType == EffectedClassNode.EffectedType.SOURCE
    }
}

val Collection<EffectedClassNode>.inlineImplChanges: List<EffectedClassNode> get() {
    return this.filter { it.effectedType == EffectedClassNode.EffectedType.INLINE_IMPL_CHANGE }
}
