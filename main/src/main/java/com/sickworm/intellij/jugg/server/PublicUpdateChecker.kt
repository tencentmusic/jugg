package com.sickworm.intellij.jugg.server

import com.google.gson.Gson
import com.google.gson.reflect.TypeToken
import com.sickworm.intellij.jugg.ide.logic.PluginVersionComparator
import com.sickworm.intellij.jugg.logger.JuggLogger
import kotlinx.coroutines.Dispatchers
import kotlinx.coroutines.async
import kotlinx.coroutines.withContext
import kotlinx.coroutines.withTimeoutOrNull
import okhttp3.OkHttpClient
import okhttp3.Request
import java.util.concurrent.TimeUnit

/**
 * Supported public update channels for Jugg when self-hosted backend server is not available.
 */
enum class UpdateChannel(val displayName: String) {
    MARKETPLACE("JetBrains Marketplace"),
    GITHUB("GitHub Releases"),
}

/**
 * Details of an available update detected from a public channel.
 */
data class PublicUpdateInfo(
    val channel: UpdateChannel,
    val targetVersion: String,
    val downloadUrl: String?,
    val releaseNotes: String?,
    val updateId: Long? = null,
    val webUrl: String? = null,
)

/**
 * Overall check outcome produced by [PublicUpdateChecker].
 */
data class PublicCheckResult(
    val updateInfo: PublicUpdateInfo?,
    val latestCheckedVersion: String?,
    val isAlreadyLatest: Boolean,
    val failedReason: String? = null,
)

/**
 * Checks for plugin updates from public sources (JetBrains Marketplace and GitHub Releases)
 * concurrently with timeout protection and fallback arbitration.
 */
class PublicUpdateChecker(
    private val marketplaceUrl: String = DEFAULT_MARKETPLACE_URL,
    private val githubUrl: String = DEFAULT_GITHUB_URL,
    private val timeoutMs: Long = DEFAULT_TIMEOUT_MS,
    client: OkHttpClient? = null,
) {
    private val logger = JuggLogger.getGlobalLogger("PublicUpdateChecker")
    private val httpClient: OkHttpClient = (client?.newBuilder() ?: OkHttpClient.Builder())
        .connectTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .readTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .callTimeout(timeoutMs, TimeUnit.MILLISECONDS)
        .build()

    /**
     * Concurrently queries Marketplace and GitHub for newer versions than [currentVersion].
     */
    suspend fun check(currentVersion: String): PublicCheckResult = withContext(Dispatchers.IO) {
        val result = withTimeoutOrNull(timeoutMs) {
            val marketplaceDeferred = async { queryMarketplace(currentVersion) }
            val githubDeferred = async { queryGithub(currentVersion) }

            val marketplaceResult = marketplaceDeferred.await()
            val githubResult = githubDeferred.await()

            arbitrate(marketplaceResult, githubResult)
        }

        result ?: PublicCheckResult(
            updateInfo = null,
            latestCheckedVersion = null,
            isAlreadyLatest = false,
            failedReason = "Check updates timed out after ${timeoutMs / 1000}s.",
        )
    }

    private fun queryMarketplace(currentVersion: String): ChannelQueryResult {
        logger.debug("Querying JetBrains Marketplace: $marketplaceUrl")
        return try {
            val request = Request.Builder().url(marketplaceUrl).get().build()
            httpClient.newCall(request).execute().use { response ->
                if (!response.isSuccessful) {
                    return ChannelQueryResult.Failure("HTTP ${response.code}")
                }
                val body = response.body?.string().orEmpty()
                val type = object : TypeToken<List<MarketplaceUpdateItem>>() {}.type
                val items: List<MarketplaceUpdateItem> = Gson().fromJson(body, type) ?: emptyList()
                val latest = items.firstOrNull { it.listed && it.version != null }
                    ?: return ChannelQueryResult.Failure("No listed release found")

                val rawVersion = latest.version.orEmpty()
                val cleanVersion = normalizeVersion(rawVersion)
                val downloadUrl = if (!latest.file.isNullOrBlank()) {
                    "https://plugins.jetbrains.com/files/${latest.file}"
                } else if (latest.id != null) {
                    "https://plugins.jetbrains.com/plugin/download?rel=true&updateId=${latest.id}"
                } else null

                ChannelQueryResult.Success(
                    channel = UpdateChannel.MARKETPLACE,
                    version = cleanVersion,
                    downloadUrl = downloadUrl,
                    notes = latest.notes,
                    updateId = latest.id,
                    webUrl = MARKETPLACE_PAGE_URL,
                    hasNewer = PluginVersionComparator.compare(cleanVersion, currentVersion) > 0,
                )
            }
        } catch (e: Throwable) {
            logger.debug("Query Marketplace failed: $e")
            ChannelQueryResult.Failure(e.message ?: e.toString())
        }
    }

    private fun queryGithub(currentVersion: String): ChannelQueryResult {
        logger.debug("Querying GitHub Releases: $githubUrl")
        return try {
            val request = Request.Builder().url(githubUrl).get().build()
            httpClient.newCall(request).execute().use { response ->
                val redirectLocation = response.header("Location")
                val finalUrl = redirectLocation ?: response.request.url.toString()
                val tag = extractTagFromUrl(finalUrl)
                    ?: return ChannelQueryResult.Failure("Unable to parse release tag from URL: $finalUrl")

                val cleanVersion = normalizeVersion(tag)
                val downloadUrl = "https://github.com/tencentmusic/jugg/releases/download/$tag/jugg-$cleanVersion.zip"

                ChannelQueryResult.Success(
                    channel = UpdateChannel.GITHUB,
                    version = cleanVersion,
                    downloadUrl = downloadUrl,
                    notes = null,
                    updateId = null,
                    webUrl = GITHUB_RELEASES_URL,
                    hasNewer = PluginVersionComparator.compare(cleanVersion, currentVersion) > 0,
                )
            }
        } catch (e: Throwable) {
            logger.debug("Query GitHub failed: $e")
            ChannelQueryResult.Failure(e.message ?: e.toString())
        }
    }

    private fun arbitrate(
        marketplaceResult: ChannelQueryResult,
        githubResult: ChannelQueryResult,
    ): PublicCheckResult {
        // Priority 1: Marketplace has a newer version
        if (marketplaceResult is ChannelQueryResult.Success && marketplaceResult.hasNewer) {
            return PublicCheckResult(
                updateInfo = marketplaceResult.toPublicUpdateInfo(),
                latestCheckedVersion = marketplaceResult.version,
                isAlreadyLatest = false,
            )
        }

        // Priority 2: GitHub has a newer version
        if (githubResult is ChannelQueryResult.Success && githubResult.hasNewer) {
            return PublicCheckResult(
                updateInfo = githubResult.toPublicUpdateInfo(),
                latestCheckedVersion = githubResult.version,
                isAlreadyLatest = false,
            )
        }

        // If either succeeded and not newer, it means current is already latest
        val highestVersion = listOfNotNull(
            (marketplaceResult as? ChannelQueryResult.Success)?.version,
            (githubResult as? ChannelQueryResult.Success)?.version,
        ).maxWithOrNull { a, b -> PluginVersionComparator.compare(a, b) }

        if (highestVersion != null) {
            return PublicCheckResult(
                updateInfo = null,
                latestCheckedVersion = highestVersion,
                isAlreadyLatest = true,
            )
        }

        // Both failed
        val failureDetails = mutableListOf<String>().apply {
            if (marketplaceResult is ChannelQueryResult.Failure) add("Marketplace: ${marketplaceResult.reason}")
            if (githubResult is ChannelQueryResult.Failure) add("GitHub: ${githubResult.reason}")
        }.joinToString("; ")

        return PublicCheckResult(
            updateInfo = null,
            latestCheckedVersion = null,
            isAlreadyLatest = false,
            failedReason = "Failed to fetch updates from public channels ($failureDetails)",
        )
    }

    private fun normalizeVersion(version: String): String {
        return version
            .removePrefix("v")
            .removePrefix("V")
            .substringBefore("-")
            .trim()
    }

    private fun extractTagFromUrl(url: String): String? {
        val tagPattern = Regex(".*/releases/tag/([^/?#]+)")
        return tagPattern.find(url)?.groupValues?.getOrNull(1)
    }

    private sealed interface ChannelQueryResult {
        data class Success(
            val channel: UpdateChannel,
            val version: String,
            val downloadUrl: String?,
            val notes: String?,
            val updateId: Long?,
            val webUrl: String?,
            val hasNewer: Boolean,
        ) : ChannelQueryResult {
            fun toPublicUpdateInfo() = PublicUpdateInfo(
                channel = channel,
                targetVersion = version,
                downloadUrl = downloadUrl,
                releaseNotes = notes,
                updateId = updateId,
                webUrl = webUrl,
            )
        }

        data class Failure(val reason: String) : ChannelQueryResult
    }

    private data class MarketplaceUpdateItem(
        val id: Long? = null,
        val version: String? = null,
        val listed: Boolean = true,
        val file: String? = null,
        val notes: String? = null,
    )

    companion object {
        const val DEFAULT_MARKETPLACE_URL = "https://plugins.jetbrains.com/api/plugins/34099/updates"
        const val DEFAULT_GITHUB_URL = "https://github.com/tencentmusic/jugg/releases/latest"
        const val MARKETPLACE_PAGE_URL = "https://plugins.jetbrains.com/plugin/34099-jugg"
        const val GITHUB_RELEASES_URL = "https://github.com/tencentmusic/jugg/releases/latest"
        const val DEFAULT_TIMEOUT_MS = 5000L
    }
}
