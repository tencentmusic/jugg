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
         * The implementation of an inlined method has changed.
         *
         * When R8 inlines a method into caller classes, if the inlined method
         * implementation changes, all classes containing the inlined code will be affected.
         * Handled by DexMinifyCompiler to generate _jugg_fix classes and redirect calls.
         */
        INLINE_IMPL_CHANGE,

        /**
         * R8/ProGuard minification removed a class entirely or stripped some of its
         * methods/fields. The incremental dex still references the missing members,
         * so a _jugg_fix class must be generated from the original .class bytecode.
         *
         * Detected by [DeployDataDatabaseSqLiteHelper.getEffectedClassNodesForMinify].
         */
        MINIFY_MEMBER_REMOVED,
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

val Collection<EffectedClassNode>.minifyMemberRemoved: List<EffectedClassNode> get() {
    return this.filter { it.effectedType == EffectedClassNode.EffectedType.MINIFY_MEMBER_REMOVED }
}
