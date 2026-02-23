package com.sickworm.intellij.jugg.compiler.constref

import com.intellij.openapi.diagnostic.Logger

/**
 * Coordinate cleanup for const-ref sqlite storages.
 *
 * Cleanup failures are isolated and logged as warnings so compile/deploy main flow is never blocked.
 */
internal class ConstRefCacheCleaner(
    private val logger: Logger,
) {
    fun cleanupIfNeeded(
        cacheDatabase: ConstRefCacheDatabase,
        fingerprintStore: RepoSharedFingerprintStore,
    ) {
        val nowMs = System.currentTimeMillis()
        runCatching {
            cacheDatabase.cleanupIfNeeded(nowMs)
        }.onFailure {
            logger.warn("const-ref cache db cleanup failed", it)
        }.onSuccess { result ->
            if (result.executed) {
                logger.debug(
                    "const-ref cache db cleanup done, " +
                        "mtimeExpired=${result.removedExpiredMtimeRows}, " +
                        "mtimeOverflow=${result.removedOverflowMtimeRows}, " +
                        "analysisExpired=${result.removedExpiredAnalysisRows}, " +
                        "analysisOverflow=${result.removedOverflowAnalysisRows}, " +
                        "orphanMtime=${result.removedOrphanMtimeRows}, " +
                        "checkpoint=${result.checkpointExecuted}, vacuum=${result.vacuumExecuted}"
                )
            }
        }

        runCatching {
            fingerprintStore.cleanupIfNeeded(nowMs)
        }.onFailure {
            logger.warn("const-ref fingerprint db cleanup failed", it)
        }.onSuccess { result ->
            if (result.executed) {
                logger.debug(
                    "const-ref fingerprint cleanup done, " +
                        "expired=${result.removedExpiredRows}, overflow=${result.removedOverflowRows}, " +
                        "checkpoint=${result.checkpointExecuted}, vacuum=${result.vacuumExecuted}"
                )
            }
        }
    }
}
