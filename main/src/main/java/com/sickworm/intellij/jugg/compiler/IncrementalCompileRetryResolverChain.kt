package com.sickworm.intellij.jugg.compiler

/**
 * Chains multiple [IIncrementalCompileRetryResolver] implementations.
 * Returns true (and stops) as soon as one resolver reports a successful fix.
 */
class IncrementalCompileRetryResolverChain(
    private val resolvers: List<IIncrementalCompileRetryResolver>,
) : IIncrementalCompileRetryResolver {

    override fun resolve(compileResult: CompileResult): Boolean {
        return resolvers.any { it.resolve(compileResult) }
    }
}
