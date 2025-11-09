package com.sickworm.intellij.jugg.compiler

/**
 * Try resolve dependency missing problem, retry if resolve success
 */
interface IDependencyMissingResolver {
    /**
     * @return is can retry
     */
    fun resolve(compileResult: CompileResult): Boolean
}